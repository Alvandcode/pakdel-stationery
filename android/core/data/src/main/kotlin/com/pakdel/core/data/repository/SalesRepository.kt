package com.pakdel.core.data.repository

import androidx.room.withTransaction
import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.common.id.Uuids
import com.pakdel.core.common.result.AppError
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.common.result.runCatchingApp
import com.pakdel.core.data.local.PakdelDatabase
import com.pakdel.core.data.local.LedgerId
import com.pakdel.core.data.local.entity.InventoryTxEntity
import com.pakdel.core.data.local.entity.OutboxEntity
import com.pakdel.core.data.local.entity.SaleEntity
import com.pakdel.core.data.local.entity.SaleItemEntity
import com.pakdel.core.data.remote.SaleDto
import com.pakdel.core.data.remote.SaleItemDto
import com.pakdel.core.data.session.SessionManager
import com.pakdel.core.data.session.parseTimestampOrNull
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

data class CartLine(
    val productId: String,
    val name: String,
    val qty: Int,
    val unitPrice: Long,
    val unitCost: Long,
    val discount: Long = 0L,
) {
    val lineTotal: Long get() = unitPrice * qty - discount
}

data class CartPayment(
    val method: String,
    val amount: Long,
    val reference: String? = null,
)

data class SaleDraft(
    val customerId: String?,
    val customerName: String?,
    val lines: List<CartLine>,
    val invoiceDiscount: Long = 0L,
    val payments: List<CartPayment> = emptyList(),
    val note: String? = null,
    val paymentMethod: String = "cash",
)

@Singleton
class SalesRepository @Inject constructor(
    private val database: PakdelDatabase,
    private val supabase: SupabaseClient,
    private val sessionManager: SessionManager,
    private val dispatchers: DispatcherProvider,
) {
    private val saleDao = database.saleDao()
    private val productDao = database.productDao()
    private val customerDao = database.customerDao()
    private val outboxDao = database.outboxDao()

    fun observeRecentSales(): Flow<List<SaleEntity>> = saleDao.observeRecent()

    fun observeSalesTotal(from: Long, to: Long): Flow<Long> = saleDao.observeSalesTotal(from, to)

    fun observeInvoiceCount(from: Long, to: Long): Flow<Int> = saleDao.observeInvoiceCount(from, to)

    fun observeCogs(from: Long, to: Long): Flow<Long> = saleDao.observeCogs(from, to)

    suspend fun saleWithItems(id: String): Pair<SaleEntity, List<SaleItemEntity>>? =
        withContext(dispatchers.io) {
            val sale = saleDao.byId(id) ?: return@withContext null
            sale to saleDao.itemsOf(id)
        }

    /**
     * ثبت فروش به‌صورت آفلاین‌اول:
     * ۱) فاکتور و اقلام و گردش انبار در یک تراکنش محلی ثبت می‌شوند
     * ۲) یک رکورد Outbox برای ارسال به سرور ساخته می‌شود
     * ۳) UI بلافاصله نتیجه می‌گیرد؛ همگام‌سازی در پس‌زمینه انجام می‌شود
     *
     * مبلغ و موجودی نهایی در سرور دوباره محاسبه و اعتبارسنجی می‌شود.
     */
    suspend fun checkout(draft: SaleDraft): AppResult<SaleEntity> = withContext(dispatchers.io) {
        runCatchingApp {
            if (draft.lines.isEmpty()) {
                throw IllegalArgumentException("VALIDATION: سبد خرید خالی است.")
            }
            if (draft.lines.any { it.qty <= 0 }) {
                throw IllegalArgumentException("VALIDATION: تعداد اقلام نامعتبر است.")
            }

            val session = sessionManager.requireSession()
            val now = System.currentTimeMillis()

            val subtotal = draft.lines.sumOf { it.unitPrice * it.qty }
            val itemDiscount = draft.lines.sumOf { it.discount }
            if (draft.invoiceDiscount < 0 || draft.invoiceDiscount > subtotal - itemDiscount) {
                throw IllegalArgumentException("INVALID_DISCOUNT: تخفیف فاکتور نامعتبر است.")
            }
            val discountTotal = itemDiscount + draft.invoiceDiscount
            val grandTotal = (subtotal - discountTotal).coerceAtLeast(0)
            val paidTotal = draft.payments.sumOf { it.amount }
            if (paidTotal > grandTotal) {
                throw IllegalArgumentException("INVALID_PAYMENT: مبلغ پرداخت بیش از مبلغ فاکتور است.")
            }
            val dueTotal = grandTotal - paidTotal

            // فروش نسیه بدون مشتری یعنی بدهی بی‌صاحب؛ هم در کلاینت و هم در سرور مسدود است.
            if (dueTotal > 0 && draft.customerId == null) {
                throw IllegalArgumentException("VALIDATION: برای فروش نسیه، مشتری را انتخاب کنید.")
            }

            val customer = draft.customerId?.let { customerDao.byId(it) }
            if (draft.customerId != null) {
                if (customer == null) {
                    throw IllegalArgumentException("NOT_FOUND: مشتری یافت نشد.")
                }
                if (customer.storeId != session.storeId) {
                    throw IllegalArgumentException("FORBIDDEN: مشتری متعلق به این فروشگاه نیست.")
                }
                if (dueTotal > 0 && customer.creditLimit > 0 &&
                    customer.balanceCached + dueTotal > customer.creditLimit
                ) {
                    throw IllegalArgumentException(
                        "CREDIT_LIMIT: سقف اعتبار مشتری تکمیل است " +
                            "(مانده: ${customer.balanceCached}، سقف: ${customer.creditLimit})."
                    )
                }
            }

            // کنترل موجودی پیش از ثبت محلی (سرور هم دوباره کنترل می‌کند)
            draft.lines.forEach { line ->
                val product = productDao.byId(line.productId)
                    ?: throw IllegalArgumentException("PRODUCT_NOT_FOUND: کالا یافت نشد.")
                if (product.stockCached < line.qty) {
                    throw IllegalArgumentException(
                        "INSUFFICIENT_STOCK: موجودی «${product.name}» کافی نیست " +
                            "(موجودی: ${product.stockCached})."
                    )
                }
            }

            val saleId = Uuids.newId()
            val operationId = Uuids.newId()
            val method = when {
                dueTotal > 0 && paidTotal > 0 -> "mixed"
                dueTotal > 0 -> "credit"
                draft.paymentMethod.isNotBlank() -> draft.paymentMethod
                else -> "cash"
            }

            val invoiceNumber = "OFF-${now}"

            val sale = SaleEntity(
                id = saleId,
                storeId = session.storeId,
                customerId = draft.customerId,
                customerName = draft.customerName,
                invoiceNumber = invoiceNumber,
                subtotal = subtotal,
                discountTotal = discountTotal,
                grandTotal = grandTotal,
                paidTotal = paidTotal,
                dueTotal = dueTotal,
                paymentMethod = method,
                status = "completed",
                note = draft.note,
                soldAt = now,
                syncState = "pending",
            )

            val items = draft.lines.map { line ->
                SaleItemEntity(
                    id = Uuids.newId(),
                    saleId = saleId,
                    productId = line.productId,
                    nameSnapshot = line.name,
                    qty = line.qty,
                    unitPrice = line.unitPrice,
                    unitCost = line.unitCost,
                    discount = line.discount,
                    lineTotal = line.lineTotal,
                )
            }

            val inventory = draft.lines.map { line ->
                InventoryTxEntity(
                    id = LedgerId.forSale(
                        saleId = saleId,
                        productId = line.productId,
                        type = "sale",
                        qtyDelta = -line.qty,
                    ),
                    storeId = session.storeId,
                    productId = line.productId,
                    qtyDelta = -line.qty,
                    type = "sale",
                    referenceType = "sale",
                    referenceId = saleId,
                    reason = "فروش آفلاین",
                    createdAt = now,
                    syncState = "pending",
                )
            }

            val payload = buildJsonObject {
                put("id", saleId)
                put("store_id", session.storeId)
                put("customer_id", draft.customerId)
                put("payment_method", method)
                put("invoice_discount", draft.invoiceDiscount)
                put("tax_total", 0)
                put("note", draft.note)
                put("client_operation_id", operationId)
                put("items", buildJsonArray {
                    draft.lines.forEach { line ->
                        add(buildJsonObject {
                            put("product_id", line.productId)
                            put("variant_id", null as String?)
                            put("qty", line.qty)
                            put("unit_price", line.unitPrice)
                            put("discount", line.discount)
                        })
                    }
                })
                put("payments", buildJsonArray {
                    draft.payments.forEach { payment ->
                        add(buildJsonObject {
                            put("method", payment.method)
                            put("amount", payment.amount)
                            put("reference", payment.reference)
                        })
                    }
                })
            }

            database.withTransaction {
                saleDao.insertSale(sale)
                saleDao.insertItems(items)
                saleDao.insertInventory(inventory)
                draft.lines.forEach { line ->
                    val updated = productDao.decreaseStock(line.productId, line.qty, now)
                    if (updated == 0) {
                        throw IllegalStateException(
                            "INSUFFICIENT_STOCK: موجودی کالا هم‌زمان تغییر کرد؛ دوباره تلاش کنید."
                        )
                    }
                }
                if (customer != null && dueTotal != 0L) {
                    customerDao.upsert(customer.copy(balanceCached = customer.balanceCached + dueTotal))
                }
                outboxDao.enqueue(
                    OutboxEntity(
                        id = operationId,
                        entityId = saleId,
                        rpc = "rpc_checkout_sale",
                        payload = payload.toString(),
                        createdAt = now,
                    )
                )
            }

            sale
        }
    }

    /** لغو محلی فاکتور؛ ارسال به سرور در مرحله همگام‌سازی (نیازمند مجوز sale.cancel) */
    suspend fun cancelLocally(saleId: String, reason: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingApp<Unit> {
                database.withTransaction {
                    val sale = saleDao.byId(saleId)
                        ?: throw IllegalArgumentException("NOT_FOUND: فاکتور یافت نشد.")
                    if (sale.status == "canceled") {
                        throw IllegalArgumentException("ALREADY_CANCELED: فاکتور قبلاً لغو شده است.")
                    }
                    saleDao.markCanceledLocal(saleId)
                    val items = saleDao.itemsOf(saleId)
                    items.forEach { item -> productDao.increaseStock(item.productId, item.qty) }
                    // سرور نیز در لغو فاکتور، بدهی مشتری را برمی‌گرداند؛ محلی هم باید هم‌خوان بماند
                    if (sale.customerId != null && sale.dueTotal != 0L) {
                        customerDao.byId(sale.customerId)?.let { customer ->
                            customerDao.upsert(
                                customer.copy(
                                    balanceCached = (customer.balanceCached - sale.dueTotal)
                                        .coerceAtLeast(0L)
                                )
                            )
                        }
                    }
                    val payload = buildJsonObject {
                        put("p_sale_id", saleId)
                        put("p_reason", reason)
                    }
                    outboxDao.enqueue(
                        OutboxEntity(
                            id = Uuids.newId(),
                            entityId = saleId,
                            rpc = "rpc_cancel_sale",
                            payload = payload.toString(),
                        )
                    )
                }
            }
        }

    /**
     * اثرات محلی فروشی که سرور نپذیرفته را برمی‌گرداند (بدون حذف فاکتور).
     * فاکتور با وضعیت `rejected` برای حسابرسی باقی می‌ماند.
     */
    suspend fun discardRejectedSale(saleId: String): AppResult<Unit> = withContext(dispatchers.io) {
        runCatchingApp {
            database.withTransaction {
                val sale = saleDao.byId(saleId)
                    ?: throw IllegalArgumentException("NOT_FOUND: فاکتور یافت نشد.")
                if (sale.status == "rejected") return@withTransaction

                val now = System.currentTimeMillis()
                val items = saleDao.itemsOf(saleId)
                items.forEach { item ->
                    productDao.increaseStock(item.productId, item.qty, now)
                    database.inventoryDao().insert(
                        InventoryTxEntity(
                            id = LedgerId.forSale(
                                saleId = saleId,
                                productId = item.productId,
                                type = "return_sale",
                                qtyDelta = item.qty,
                            ),
                            storeId = sale.storeId,
                            productId = item.productId,
                            qtyDelta = item.qty,
                            type = "return_sale",
                            referenceType = "sale",
                            referenceId = saleId,
                            reason = "برگشت موجودی به دلیل رد فاکتور در همگام‌سازی",
                            createdAt = now,
                            syncState = "local",
                        )
                    )
                }
                if (sale.customerId != null && sale.dueTotal != 0L) {
                    customerDao.byId(sale.customerId)?.let { customer ->
                        customerDao.upsert(
                            customer.copy(
                                balanceCached = (customer.balanceCached - sale.dueTotal).coerceAtLeast(0L)
                            )
                        )
                    }
                }
                saleDao.markRejected(saleId)
            }
        }
    }

    /**
     * دریافت فاکتورها و اقلام از سرور.
     * RLS فقط فاکتورهای فروشگاه کاربر را برمی‌گرداند؛ رکوردهای در صف/تعارض بازنویسی نمی‌شوند.
     */
    suspend fun refreshFromServer(storeId: String): AppResult<Int> = withContext(dispatchers.io) {
        runCatchingApp {
            // TODO(scale): در حجم بالا صفحه‌بندی هم لازم می‌شود؛ فعلاً ۵۰۰ فاکتور اخیر.
            val cutoff = System.currentTimeMillis() -
                java.util.concurrent.TimeUnit.DAYS.toMillis(120)
            val cutoffIso = java.time.Instant.ofEpochMilli(cutoff).toString()

            val remoteSales = supabase.postgrest
                .from("sales")
                .select {
                    filter {
                        eq("store_id", storeId)
                        gte("sold_at", cutoffIso)
                    }
                    order("sold_at", Order.DESCENDING)
                    limit(500)
                }
                .decodeList<SaleDto>()
                .filter { dto ->
                    val soldAt = parseTimestampOrNull(dto.soldAt)
                    soldAt == null || soldAt >= cutoff
                }

            // اقلام به‌صورت جداگانه و در دسته‌های ۱۰۰تایی خوانده می‌شوند
            // (RLS آن‌ها را به فروشگاه خودی محدود می‌کند)
            val saleIds = remoteSales.map { it.id }
            val remoteItems = mutableListOf<SaleItemDto>()
            saleIds.chunked(100).forEach { ids ->
                remoteItems += supabase.postgrest
                    .from("sale_items")
                    .select { filter { isIn("sale_id", ids) } }
                    .decodeList<SaleItemDto>()
            }
            val itemsBySale = remoteItems.groupBy { it.saleId }

            database.withTransaction {
                val protectedIds = outboxDao.blockedEntityIds().toSet()
                val sales = remoteSales.filterNot { it.id in protectedIds }

                saleDao.upsertSales(
                    sales.map { dto ->
                        SaleEntity(
                            id = dto.id,
                            storeId = dto.storeId,
                            customerId = dto.customerId,
                            customerName = null,
                            invoiceNumber = dto.invoiceNumber,
                            subtotal = dto.subtotal,
                            discountTotal = dto.discountTotal,
                            grandTotal = dto.grandTotal,
                            paidTotal = dto.paidTotal,
                            dueTotal = dto.dueTotal,
                            paymentMethod = dto.paymentMethod,
                            status = dto.status,
                            note = dto.note,
                            soldAt = parseTimestampOrNull(dto.soldAt) ?: System.currentTimeMillis(),
                            syncState = "synced",
                        )
                    }
                )

                sales.forEach { dto ->
                    saleDao.deleteItems(dto.id)
                    saleDao.insertItems(
                        itemsBySale[dto.id].orEmpty().map { item ->
                            SaleItemEntity(
                                id = item.id,
                                saleId = dto.id,
                                productId = item.productId.orEmpty(),
                                nameSnapshot = item.nameSnapshot,
                                qty = item.qty,
                                unitPrice = item.unitPrice,
                                unitCost = item.unitCost,
                                discount = item.discount,
                                lineTotal = item.lineTotal,
                            )
                        }
                    )
                }
            }
            remoteSales.size
        }
    }
}

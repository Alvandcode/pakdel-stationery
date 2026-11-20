package com.pakdel.core.data.repository

import androidx.room.withTransaction
import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.common.id.Uuids
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.common.result.runCatchingApp
import com.pakdel.core.data.local.LedgerId
import com.pakdel.core.data.local.PakdelDatabase
import com.pakdel.core.data.local.dao.InventoryMovementRow
import com.pakdel.core.data.local.entity.InventoryTxEntity
import com.pakdel.core.data.local.entity.OutboxEntity
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.remote.InventoryTxDto
import com.pakdel.core.data.session.SessionManager
import com.pakdel.core.data.session.parseTimestampOrNull
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** انواع مجاز گردش دستی انبار؛ هم‌نام با نوع‌های سرور */
enum class StockMovementType(val code: String, val label: String) {
    In("purchase", "ورود کالا"),
    ReturnPurchase("return_purchase", "مرجوعی خرید"),
    Damage("damage", "ضایعات / معیوب"),
    Adjustment("adjustment", "اصلاح موجودی"),
}

@Singleton
class InventoryRepository @Inject constructor(
    private val database: PakdelDatabase,
    private val supabase: SupabaseClient,
    private val sessionManager: SessionManager,
    private val dispatchers: DispatcherProvider,
) {
    private val inventoryDao = database.inventoryDao()
    private val productDao = database.productDao()
    private val outboxDao = database.outboxDao()

    fun observeMovements(): Flow<List<InventoryMovementRow>> = inventoryDao.observeMovements()

    /**
     * ثبت گردش انبار به‌صورت آفلاین‌اول.
     *
     * هر تغییر موجودی دلیل، کاربر، تاریخ و شناسه عملیات دارد و به‌صورت رکورد
     * تغییرناپذیر ثبت می‌شود؛ اصلاح بعدی فقط با رکورد معکوس امکان‌پذیر است.
     */
    suspend fun adjust(
        product: ProductEntity,
        qtyDelta: Int,
        type: StockMovementType,
        reason: String,
    ): AppResult<Int> = withContext(dispatchers.io) {
        runCatchingApp {
            if (qtyDelta == 0) {
                throw IllegalArgumentException("VALIDATION: مقدار تغییر صفر است.")
            }
            if (reason.isBlank()) {
                throw IllegalArgumentException("VALIDATION: دلیل تغییر موجودی را وارد کنید.")
            }
            if (qtyDelta < 0 && product.stockCached + qtyDelta < 0) {
                throw IllegalArgumentException(
                    "INSUFFICIENT_STOCK: موجودی کافی نیست (موجودی فعلی: ${product.stockCached})."
                )
            }

            val session = sessionManager.requireSession()
            if (!session.canAdjustStock) {
                throw IllegalArgumentException("FORBIDDEN: دسترسی اصلاح موجودی وجود ندارد.")
            }
            val now = System.currentTimeMillis()
            val operationId = Uuids.newId()

            val payload = buildJsonObject {
                put("store_id", session.storeId)
                put("product_id", product.id)
                put("variant_id", null as String?)
                put("qty_delta", qtyDelta)
                put("type", type.code)
                put("reason", reason.trim())
                put("client_operation_id", operationId)
            }

            val newStock = product.stockCached + qtyDelta

            database.withTransaction {
                inventoryDao.insert(
                    InventoryTxEntity(
                        // همان شناسه عملیات، تا همگام‌سازی همان ردیف را بازنویسی کند
                        id = operationId,
                        storeId = session.storeId,
                        productId = product.id,
                        qtyDelta = qtyDelta,
                        type = type.code,
                        referenceType = "manual",
                        reason = reason.trim(),
                        createdAt = now,
                        syncState = "pending",
                    )
                )
                productDao.upsert(
                    product.copy(stockCached = newStock, updatedAt = now)
                )
                outboxDao.enqueue(
                    OutboxEntity(
                        id = operationId,
                        entityId = product.id,
                        rpc = "rpc_adjust_stock",
                        payload = payload.toString(),
                        createdAt = now,
                    )
                )
            }

            newStock
        }
    }

    /**
     * دریافت گردش انبار فروشگاه از سرور (۶۰ روز اخیر).
     *
     * گردش فقط «نمایشی/حسابرسی» است؛ موجودیِ معتبر همان `products.stock_cached`
     * است که در همگام‌سازی کاتالوگ به‌روز می‌شود. با شناسه‌های قطعی [LedgerId]
     * ردیف‌های محلی با ردیف‌های سرور تکرار نمی‌شوند.
     */
    suspend fun refreshFromServer(storeId: String): AppResult<Int> = withContext(dispatchers.io) {
        runCatchingApp {
            val cutoff = Instant.now().minus(Duration.ofDays(60)).toString()
            val remote = supabase.postgrest
                .from("inventory_transactions")
                .select {
                    filter {
                        eq("store_id", storeId)
                        gte("created_at", cutoff)
                    }
                    order("created_at", Order.DESCENDING)
                    limit(400)
                }
                .decodeList<InventoryTxDto>()
                .filter { it.storeId == storeId }

            val now = System.currentTimeMillis()
            database.withTransaction {
                inventoryDao.upsertAll(
                    remote.mapNotNull { dto ->
                        val productId = dto.productId ?: return@mapNotNull null
                        InventoryTxEntity(
                            id = LedgerId.of(
                                serverId = dto.id,
                                referenceType = dto.referenceType,
                                referenceId = dto.referenceId,
                                productId = productId,
                                type = dto.type,
                                qtyDelta = dto.qtyDelta,
                                clientOperationId = dto.clientOperationId,
                            ),
                            storeId = dto.storeId,
                            productId = productId,
                            qtyDelta = dto.qtyDelta,
                            type = dto.type,
                            referenceType = dto.referenceType,
                            referenceId = dto.referenceId,
                            reason = dto.reason,
                            createdAt = parseTimestampOrNull(dto.createdAt) ?: now,
                            syncState = "synced",
                        )
                    }
                )
                // برای جلوگیری از رشد بی‌پایان کش، ردیف‌های قدیمی حذف می‌شوند
                // (گردش انبار نمایشی/حسابرسی است و منبع حقیقت موجودی، محصولات است)
                inventoryDao.pruneOlderThan(
                    Instant.now().minus(Duration.ofDays(75)).toEpochMilli()
                )
            }
            remote.size
        }
    }
}

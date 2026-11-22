package com.pakdel.core.data.repository

import androidx.room.withTransaction
import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.common.id.Uuids
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.common.result.runCatchingApp
import com.pakdel.core.data.local.LedgerId
import com.pakdel.core.data.local.PakdelDatabase
import com.pakdel.core.data.local.entity.OutboxEntity
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.remote.CategoryDto
import com.pakdel.core.data.remote.PakdelJson
import com.pakdel.core.data.remote.ProductDto
import com.pakdel.core.data.session.SessionManager
import com.pakdel.core.data.session.toCategoryEntity
import com.pakdel.core.data.session.toProductEntity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class PublishAllDto(@SerialName("published") val published: Int = 0)

/** ورودی فرم محصول؛ همان چیزی که مدیر در صفحه ویرایش پر می‌کند */
data class ProductDraft(
    val id: String = Uuids.newId(),
    val name: String = "",
    val barcode: String? = null,
    val sku: String? = null,
    val categoryId: String? = null,
    val brand: String? = null,
    val description: String? = null,
    val unit: String = "عدد",
    val purchasePrice: Long = 0L,
    val salePrice: Long = 0L,
    val wholesalePrice: Long = 0L,
    val minStock: Int = 0,
    val initialStock: Int = 0,
    val isPublic: Boolean = false,
)

@Singleton
class ProductRepository @Inject constructor(
    private val database: PakdelDatabase,
    private val supabase: SupabaseClient,
    private val sessionManager: SessionManager,
    private val dispatchers: DispatcherProvider,
) {
    private val productDao = database.productDao()
    private val categoryDao = database.categoryDao()
    private val outboxDao = database.outboxDao()

    fun observeProducts(): Flow<List<ProductEntity>> = productDao.observeActive()

    fun search(query: String): Flow<List<ProductEntity>> = productDao.search(query.trim())

    fun observeLowStock(): Flow<List<ProductEntity>> = productDao.observeLowStock()

    fun observeCategories() = categoryDao.observeActive()

    suspend fun byBarcode(barcode: String): ProductEntity? =
        withContext(dispatchers.io) { productDao.byBarcode(barcode.trim()) }

    suspend fun byId(id: String): ProductEntity? =
        withContext(dispatchers.io) { productDao.byId(id) }

    suspend fun stockValue(): Long = withContext(dispatchers.io) { productDao.stockValue() }

    suspend fun localCount(): Int = withContext(dispatchers.io) { productDao.count() }

    /**
     * ثبت یا ویرایش محصول به‌صورت آفلاین‌اول.
     *
     * ۱) اعتبارسنجی محلی (نام، قیمت، بارکد تکراری)
     * ۲) نوشتن در Room در یک تراکنش
     * ۳) ثبت در Outbox برای ارسال به `rpc_upsert_product`
     *
     * موجودی اولیه فقط هنگام ایجاد اعمال می‌شود و سرور آن را به‌صورت
     * گردش انبار نوع `adjustment` ثبت می‌کند.
     */
    suspend fun saveProduct(draft: ProductDraft): AppResult<ProductEntity> =
        withContext(dispatchers.io) {
            runCatchingApp {
                val session = sessionManager.requireSession()
                val name = draft.name.trim()
                if (name.isBlank()) {
                    throw IllegalArgumentException("VALIDATION: نام محصول الزامی است.")
                }
                if (draft.purchasePrice < 0 || draft.salePrice < 0 || draft.wholesalePrice < 0) {
                    throw IllegalArgumentException("VALIDATION: قیمت نمی‌تواند منفی باشد.")
                }
                if (draft.salePrice <= 0) {
                    throw IllegalArgumentException("VALIDATION: قیمت فروش باید بزرگ‌تر از صفر باشد.")
                }
                if (draft.minStock < 0 || draft.initialStock < 0) {
                    throw IllegalArgumentException("VALIDATION: تعداد موجودی نمی‌تواند منفی باشد.")
                }

                val barcode = draft.barcode?.trim()?.takeIf { it.isNotBlank() }
                if (barcode != null) {
                    val conflict = productDao.byActiveBarcode(barcode)
                    if (conflict != null && conflict.id != draft.id) {
                        throw IllegalArgumentException("DUPLICATE_BARCODE: بارکد تکراری است.")
                    }
                }

                val existing = productDao.byId(draft.id)
                val now = System.currentTimeMillis()
                val effectiveInitialStock = if (existing == null) draft.initialStock else 0

                val entity = ProductEntity(
                    id = draft.id,
                    storeId = session.storeId,
                    categoryId = draft.categoryId,
                    name = name,
                    sku = draft.sku?.trim()?.takeIf { it.isNotBlank() },
                    barcode = barcode,
                    description = draft.description?.trim()?.takeIf { it.isNotBlank() },
                    brand = draft.brand?.trim()?.takeIf { it.isNotBlank() },
                    unit = draft.unit.trim().ifBlank { "عدد" },
                    purchasePrice = draft.purchasePrice,
                    salePrice = draft.salePrice,
                    wholesalePrice = draft.wholesalePrice,
                    stockCached = existing?.stockCached ?: draft.initialStock,
                    minStock = draft.minStock,
                    imagePath = existing?.imagePath,
                    isPublic = draft.isPublic,
                    isActive = true,
                    deletedAt = null,
                    updatedAt = now,
                )

                val payload = buildJsonObject {
                    put("id", entity.id)
                    put("store_id", session.storeId)
                    put("name", entity.name)
                    put("barcode", entity.barcode)
                    put("sku", entity.sku)
                    put("category_id", entity.categoryId)
                    put("brand", entity.brand)
                    put("description", entity.description)
                    put("unit", entity.unit)
                    put("purchase_price", entity.purchasePrice)
                    put("sale_price", entity.salePrice)
                    put("wholesale_price", entity.wholesalePrice)
                    put("min_stock", entity.minStock)
                    put("initial_stock", effectiveInitialStock)
                    put("is_public", entity.isPublic)
                    put("is_new", existing == null)
                }

                database.withTransaction {
                    productDao.upsert(entity)
                    if (effectiveInitialStock > 0) {
                        database.inventoryDao().insert(
                            com.pakdel.core.data.local.entity.InventoryTxEntity(
                                id = LedgerId.forProductStock(entity.id, effectiveInitialStock),
                                storeId = session.storeId,
                                productId = entity.id,
                                qtyDelta = effectiveInitialStock,
                                type = "adjustment",
                                referenceType = "product",
                                referenceId = entity.id,
                                reason = "موجودی اولیه کالا",
                                createdAt = now,
                                syncState = "pending",
                            )
                        )
                    }
                    outboxDao.enqueue(
                        OutboxEntity(
                            id = Uuids.newId(),
                            entityId = entity.id,
                            rpc = "rpc_upsert_product",
                            payload = payload.toString(),
                            createdAt = now,
                        )
                    )
                }
                entity
            }
        }

    /** حذف منطقی: محصول از کاتالوگ و اپ مشتریان خارج می‌شود ولی سوابق فروش باقی می‌ماند. */
    suspend fun archiveProduct(productId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingApp<Unit> {
                val existing = productDao.byId(productId)
                    ?: throw IllegalArgumentException("PRODUCT_NOT_FOUND: محصول یافت نشد.")

                val now = System.currentTimeMillis()
                database.withTransaction {
                    productDao.upsert(
                        existing.copy(
                            isActive = false,
                            isPublic = false,
                            deletedAt = now,
                            updatedAt = now,
                        )
                    )
                    outboxDao.enqueue(
                        OutboxEntity(
                            id = Uuids.newId(),
                            entityId = productId,
                            rpc = "rpc_delete_product",
                            payload = buildJsonObject {
                                put("p_product_id", productId)
                            }.toString(),
                            createdAt = now,
                        )
                    )
                }
            }
        }

    /**
     * دریافت کاتالوگ از سرور و جایگزینی کش محلی.
     *
     * نکته امنیتی مهم: سیاست `public_catalog` برای اپ مشتریان است. در اپ مدیر
     * صریحاً بر `store_id` فیلتر می‌کند تا حتی در صورت تغییر سیاست‌ها، کاتالوگ
     * فروشگاه‌های دیگر وارد دستگاه نشود (مهاجرت ۸ سیاست سرور را هم محدود می‌کند).
     *
     * @param storeId شناسه فروشگاه جاری مدیر؛ `null` یعنی کاتالوگ عمومی (اپ مشتری).
     */
    suspend fun refreshFromServer(storeId: String? = null): AppResult<Int> = withContext(dispatchers.io) {
        runCatchingApp {
            // صفحه‌بندی صریح: سقف پیش‌فرض PostgREST (۱۰۰۰ ردیف) نباید کاتالوگ را ناقص کند
            val remoteProducts = mutableListOf<ProductDto>()
            var offset = 0L
            val page = 500L
            while (true) {
                val chunk = supabase.postgrest
                    .from("products")
                    .select { range(offset, offset + page - 1) }
                    .decodeList<ProductDto>()
                remoteProducts += chunk
                if (chunk.size < page || offset > 20_000L) break
                offset += page
            }

            val remoteCategories = supabase.postgrest
                .from("categories")
                .select()
                .decodeList<CategoryDto>()

            val products = if (storeId == null) {
                remoteProducts
            } else {
                remoteProducts.filter { it.storeId == storeId }
            }
            val categories = if (storeId == null) {
                remoteCategories
            } else {
                remoteCategories.filter { it.storeId == storeId }
            }

            database.withTransaction {
                // رکوردهایی که تغییر محلی در صف دارند (یا تعارض دارند) بازنویسی نمی‌شوند
                val protectedIds = (outboxDao.blockedEntityIds() + pendingStockProductIds()).toSet()

                if (storeId != null) {
                    // پاک‌سازی داده فروشگاه‌های دیگر که از نسخه‌های قبلی باقی مانده است
                    productDao.deleteOutsideStore(storeId)
                }

                productDao.upsertAll(
                    products.map { it.toProductEntity() }.filterNot { it.id in protectedIds }
                )

                if (storeId != null) {
                    // کالاهای حذف‌شده در سرور به‌صورت soft-delete برمی‌گردند و همین upsert
                    // آن‌ها را غیرفعال می‌کند؛ بنابراین حذف/غیرفعال‌سازی «غایب‌ها» لازم نیست
                    // (و با صفحه‌بندی پیش‌فرض PostgREST خطرناک بود).
                    val keepCategoryIds = categories.map { it.id }
                    if (keepCategoryIds.isNotEmpty()) {
                        categoryDao.deleteNotIn(keepCategoryIds)
                    }
                }

                categoryDao.upsertAll(categories.map { it.toCategoryEntity() })
            }
            products.size
        }
    }

    /**
     * انتشار همه محصولات فعال فروشگاه در کاتالوگ اپ مشتری.
     * سرور این کار را در یک تراکنش انجام می‌دهد (به‌جای N درخواست).
     */
    suspend fun publishAll(): AppResult<Int> = withContext(dispatchers.io) {
        runCatchingApp {
            val result = supabase.postgrest.rpc("rpc_publish_all_products")
            result.decodeAs<PublishAllDto>().published
        }
    }

    /**
     * محصولاتی که فروش/اصلاح موجودی آن‌ها هنوز همگام نشده است.
     * موجودی محلی این کالاها نباید با مقدار سرور بازنویسی شود تا کسر/افزایش آفلاین از بین نرود.
     */
    private suspend fun pendingStockProductIds(): List<String> {
        val ids = mutableSetOf<String>()
        outboxDao.blocked().forEach { entry ->
            val payload = runCatching {
                PakdelJson.parseToJsonElement(entry.payload).jsonObject
            }.getOrNull() ?: return@forEach
            when (entry.rpc) {
                "rpc_adjust_stock" -> {
                    (payload["product_id"] as? JsonPrimitive)?.contentOrNull?.let(ids::add)
                }
                "rpc_checkout_sale" -> {
                    val items = payload["items"] as? JsonArray ?: return@forEach
                    items.forEach { element ->
                        val obj = element.jsonObject
                        (obj["product_id"] as? JsonPrimitive)?.contentOrNull?.let(ids::add)
                    }
                }
            }
        }
        return ids.toList()
    }
}

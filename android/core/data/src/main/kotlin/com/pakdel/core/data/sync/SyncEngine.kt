package com.pakdel.core.data.sync

import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.common.result.runCatchingApp
import com.pakdel.core.data.local.PakdelDatabase
import com.pakdel.core.data.local.entity.OutboxEntity
import com.pakdel.core.data.local.entity.SyncMetaEntity
import com.pakdel.core.data.remote.CheckoutResponseDto
import com.pakdel.core.data.remote.PakdelJson
import com.pakdel.core.data.repository.CustomerRepository
import com.pakdel.core.data.repository.InventoryRepository
import com.pakdel.core.data.repository.ProductRepository
import com.pakdel.core.data.repository.SalesRepository
import com.pakdel.core.data.session.SessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

data class SyncReport(
    val pushed: Int = 0,
    val failed: Int = 0,
    val conflicts: Int = 0,
    val pulled: Int = 0,
    val skipped: Boolean = false,
)

data class SyncStatus(
    val pendingCount: Int = 0,
    val conflictCount: Int = 0,
    val lastSuccessAt: Long? = null,
    val lastError: String? = null,
)

private const val KEY_LAST_SYNC = "last_sync_at"
private const val KEY_LAST_ERROR = "last_error"
private const val MAX_ATTEMPTS = 5

/** کدهایی که نشان‌دهنده تعارض واقعی کسب‌وکار هستند (نه خطای شبکه) */
private val conflictCodes = setOf(
    "INSUFFICIENT_STOCK",
    "PRODUCT_NOT_FOUND",
    "INVALID_DISCOUNT",
    "INVALID_PAYMENT",
    "DUPLICATE_OPERATION",
    "DUPLICATE_BARCODE",
    "DUPLICATE_SKU",
    "DUPLICATE_PHONE",
    "VALIDATION",
    "CREDIT_LIMIT",
    "ALREADY_CANCELED",
    "INVALID_TRANSITION",
)

@Singleton
class SyncEngine @Inject constructor(
    private val database: PakdelDatabase,
    private val supabase: SupabaseClient,
    private val sessionManager: SessionManager,
    private val productRepository: ProductRepository,
    private val customerRepository: CustomerRepository,
    private val salesRepository: SalesRepository,
    private val inventoryRepository: InventoryRepository,
    private val dispatchers: DispatcherProvider,
) {
    private val outboxDao = database.outboxDao()
    private val saleDao = database.saleDao()
    private val syncMetaDao = database.syncMetaDao()

    fun observeStatus(): Flow<SyncStatus> = combine(
        outboxDao.observePendingCount(),
        outboxDao.observeConflictCount(),
        syncMetaDao.observe(KEY_LAST_SYNC),
        syncMetaDao.observe(KEY_LAST_ERROR),
    ) { pending, conflicts, lastSync, lastError ->
        SyncStatus(
            pendingCount = pending,
            conflictCount = conflicts,
            lastSuccessAt = lastSync?.toLongOrNull(),
            lastError = lastError,
        )
    }

    fun observeConflicts(): Flow<List<OutboxEntity>> = outboxDao.observeConflicts()

    suspend fun syncNow(): AppResult<SyncReport> = withContext(dispatchers.io) {
        runCatchingApp {
            // بدون نشست هیچ داده‌ای ارسال یا دریافت نمی‌شود؛ در غیر این صورت ممکن است
            // کاتالوگ عمومی همه فروشگاه‌ها وارد پایگاه‌داده محلی شود.
            val session = sessionManager.current()
                ?: return@runCatchingApp SyncReport(skipped = true)

            var pushed = 0
            var conflicts = 0
            var stopped = false
            var lastError: String? = null

            // ---- Push ----
            for (entry in outboxDao.pending(limit = 50)) {
                if (stopped) break
                try {
                    val dto = execute(entry)
                    val entityId = entry.entityId
                    when (entry.rpc) {
                        "rpc_checkout_sale" -> {
                            if (entityId != null) {
                                val invoice = dto?.invoiceNumber
                                if (invoice != null) {
                                    saleDao.markSynced(entityId, invoice)
                                } else {
                                    saleDao.updateSyncState(entityId, "synced")
                                }
                            }
                        }
                        "rpc_cancel_sale" -> {
                            if (entityId != null) saleDao.markCanceledSynced(entityId)
                        }
                        else -> Unit
                    }
                    outboxDao.remove(entry.seq)
                    pushed++
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val code = t.message.orEmpty().substringBefore(':').trim()
                    val message = t.message ?: "خطای نامشخص"
                    if (isNetworkFailure(t)) {
                        lastError = "اتصال برقرار نیست؛ عملیات در صف باقی ماند."
                        stopped = true
                    } else if (code in conflictCodes) {
                        // تعارض: رکورد محلی حذف نمی‌شود، فقط علامت‌گذاری و اطلاع به مدیر
                        outboxDao.mark(entry.seq, "conflict", message)
                        if (entry.rpc == "rpc_checkout_sale" && entry.entityId != null) {
                            saleDao.updateSyncState(entry.entityId, "conflict")
                        }
                        conflicts++
                    } else if (entry.attempts + 1 >= MAX_ATTEMPTS) {
                        outboxDao.mark(entry.seq, "conflict", message)
                        if (entry.rpc == "rpc_checkout_sale" && entry.entityId != null) {
                            saleDao.updateSyncState(entry.entityId, "conflict")
                        }
                        conflicts++
                        lastError = message
                    } else {
                        // خطای موقت: در صف می‌ماند و در چرخه بعد دوباره تلاش می‌شود
                        outboxDao.mark(entry.seq, "pending", message)
                        lastError = message
                    }
                }
            }

            // ---- Pull ----
            var pulled = 0
            if (!stopped) {
                productRepository.refreshFromServer(session.storeId).let { result ->
                    when (result) {
                        is AppResult.Success -> pulled += result.data
                        is AppResult.Failure -> if (lastError == null) lastError = result.error.message
                    }
                }
            }
            if (!stopped) {
                customerRepository.refreshFromServer(session.storeId).let { result ->
                    when (result) {
                        is AppResult.Success -> pulled += result.data
                        is AppResult.Failure -> if (lastError == null) lastError = result.error.message
                    }
                }
                salesRepository.refreshFromServer(session.storeId).let { result ->
                    when (result) {
                        is AppResult.Success -> pulled += result.data
                        is AppResult.Failure -> if (lastError == null) lastError = result.error.message
                    }
                }
                inventoryRepository.refreshFromServer(session.storeId).let { result ->
                    when (result) {
                        is AppResult.Success -> pulled += result.data
                        is AppResult.Failure -> if (lastError == null) lastError = result.error.message
                    }
                }
            }

            syncMetaDao.put(SyncMetaEntity(KEY_LAST_SYNC, System.currentTimeMillis().toString()))
            syncMetaDao.put(SyncMetaEntity(KEY_LAST_ERROR, lastError.orEmpty()))

            SyncReport(pushed = pushed, conflicts = conflicts, pulled = pulled)
        }
    }

    /** تلاش دوباره برای یک عملیات تعارض‌دار (پس از اصلاح موجودی/اطلاعات) */
    suspend fun retryConflict(seq: Long): AppResult<Unit> = withContext(dispatchers.io) {
        runCatchingApp {
            val entry = outboxDao.bySeq(seq)
                ?: throw IllegalArgumentException("NOT_FOUND: عملیات یافت نشد.")
            if (entry.status != "conflict") {
                throw IllegalArgumentException("VALIDATION: این عملیات در وضعیت تعارض نیست.")
            }
            outboxDao.retry(seq)
        }
    }

    /**
     * رد کردن یک عملیات تعارض‌دار.
     *
     * فاکتور هرگز حذف نمی‌شود؛ اثرات محلی آن (موجودی و بدهی) برگشت می‌خورد و
     * فاکتور با وضعیت `rejected` برای حسابرسی باقی می‌ماند.
     */
    suspend fun discardConflict(seq: Long): AppResult<Unit> = withContext(dispatchers.io) {
        runCatchingApp {
            val entry = outboxDao.bySeq(seq)
                ?: throw IllegalArgumentException("NOT_FOUND: عملیات یافت نشد.")
            when (entry.rpc) {
                "rpc_checkout_sale" -> {
                    if (entry.entityId != null) {
                        when (val result = salesRepository.discardRejectedSale(entry.entityId)) {
                            is AppResult.Failure -> throw IllegalStateException(result.error.message)
                            is AppResult.Success -> Unit
                        }
                    }
                }
                "rpc_upsert_product" -> {
                    // اگر ایجاد کالا رد شده باشد، کالای محلی نباید قابل فروش بماند؛
                    // برای ویرایش، همگام‌سازی بعدی نسخه سرور را جایگزین می‌کند.
                    val isNew = runCatching {
                        (PakdelJson.parseToJsonElement(entry.payload).jsonObject["is_new"]
                            as? JsonPrimitive)?.booleanOrNull
                    }.getOrNull()
                    if (isNew == true && entry.entityId != null) {
                        database.productDao().deactivateById(entry.entityId, System.currentTimeMillis())
                    }
                }
            }
            outboxDao.remove(seq)
        }
    }

    /** فراخوانی RPC سرور؛ فقط برای checkout پاسخ رمزگشایی می‌شود */
    private suspend fun execute(entry: OutboxEntity): CheckoutResponseDto? {
        val parsed = PakdelJson.parseToJsonElement(entry.payload)
        val body: JsonObject = when (entry.rpc) {
            "rpc_checkout_sale", "rpc_place_order", "rpc_adjust_stock",
            "rpc_record_payment", "rpc_upsert_product", "rpc_upsert_customer" ->
                JsonObject(mapOf("p_payload" to parsed))
            else -> parsed.jsonObject
        }

        val result = supabase.postgrest.rpc(entry.rpc, body)
        return if (entry.rpc == "rpc_checkout_sale") {
            runCatching { result.decodeAs<CheckoutResponseDto>() }.getOrNull()
        } else {
            null
        }
    }

    private fun isNetworkFailure(t: Throwable): Boolean {
        val text = (t.message ?: "") + (t.cause?.message ?: "")
        return listOf("connect", "timeout", "unreachable", "resolve host", "socket", "SSL")
            .any { text.contains(it, ignoreCase = true) }
    }
}

package com.pakdel.core.data.repository

import androidx.room.withTransaction
import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.common.id.Uuids
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.common.result.runCatchingApp
import com.pakdel.core.data.local.PakdelDatabase
import com.pakdel.core.data.local.entity.CustomerEntity
import com.pakdel.core.data.local.entity.OutboxEntity
import com.pakdel.core.data.remote.CustomerDto
import com.pakdel.core.data.session.SessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** ورودی فرم مشتری */
data class CustomerDraft(
    val id: String = Uuids.newId(),
    val fullName: String = "",
    val phone: String? = null,
    val creditLimit: Long = 0L,
    val discountPercent: Double = 0.0,
)

/**
 * مشتریان: منبع حقیقت برای فروش نسیه و مانده حساب.
 *
 * ثبت/ویرایش آفلاین‌اول است و با `rpc_upsert_customer` به سرور می‌رود؛
 * مانده حساب (`balanceCached`) فقط از سرور معتبر است و در Pull جایگزین می‌شود.
 */
@Singleton
class CustomerRepository @Inject constructor(
    private val database: PakdelDatabase,
    private val supabase: SupabaseClient,
    private val sessionManager: SessionManager,
    private val dispatchers: DispatcherProvider,
) {
    private val customerDao = database.customerDao()
    private val outboxDao = database.outboxDao()

    fun observeAll(): Flow<List<CustomerEntity>> = customerDao.observeAll()

    fun search(query: String): Flow<List<CustomerEntity>> = customerDao.search(query.trim())

    suspend fun byId(id: String): CustomerEntity? =
        withContext(dispatchers.io) { customerDao.byId(id) }

    suspend fun save(draft: CustomerDraft): AppResult<CustomerEntity> =
        withContext(dispatchers.io) {
            runCatchingApp {
                val session = sessionManager.requireSession()
                if (!session.canManageCustomers) {
                    throw IllegalArgumentException("FORBIDDEN: دسترسی مدیریت مشتری وجود ندارد.")
                }

                val name = draft.fullName.trim()
                if (name.isBlank()) {
                    throw IllegalArgumentException("VALIDATION: نام مشتری الزامی است.")
                }
                if (draft.creditLimit < 0) {
                    throw IllegalArgumentException("VALIDATION: سقف اعتبار نمی‌تواند منفی باشد.")
                }

                val phone = draft.phone?.trim()?.takeIf { it.isNotBlank() }
                if (phone != null) {
                    val duplicate = customerDao.byPhone(session.storeId, phone)
                    if (duplicate != null && duplicate.id != draft.id) {
                        throw IllegalArgumentException("DUPLICATE_PHONE: مشتری دیگری با این شماره ثبت شده است.")
                    }
                }
                val existing = customerDao.byId(draft.id)
                val now = System.currentTimeMillis()

                val entity = CustomerEntity(
                    id = draft.id,
                    storeId = session.storeId,
                    userId = existing?.userId,
                    fullName = name,
                    phone = phone,
                    groupCode = existing?.groupCode ?: "regular",
                    discountPercent = draft.discountPercent.coerceIn(0.0, 100.0),
                    creditLimit = draft.creditLimit,
                    balanceCached = existing?.balanceCached ?: 0L,
                    isActive = true,
                    updatedAt = now,
                )

                val payload = buildJsonObject {
                    put("id", entity.id)
                    put("full_name", entity.fullName)
                    put("phone", entity.phone)
                    put("group_code", entity.groupCode)
                    put("discount_percent", entity.discountPercent)
                    put("credit_limit", entity.creditLimit)
                }

                database.withTransaction {
                    customerDao.upsert(entity)
                    outboxDao.enqueue(
                        OutboxEntity(
                            id = Uuids.newId(),
                            entityId = entity.id,
                            rpc = "rpc_upsert_customer",
                            payload = payload.toString(),
                            createdAt = now,
                        )
                    )
                }
                entity
            }
        }

    /** دریافت مشتریان فروشگاه از سرور (RLS فقط همین فروشگاه را برمی‌گرداند). */
    suspend fun refreshFromServer(storeId: String): AppResult<Int> = withContext(dispatchers.io) {
        runCatchingApp {
            val remote = mutableListOf<CustomerDto>()
            var offset = 0L
            val page = 500L
            while (true) {
                val chunk = supabase.postgrest
                    .from("customers")
                    .select { range(offset, offset + page - 1) }
                    .decodeList<CustomerDto>()
                remote += chunk
                if (chunk.size < page || offset > 20_000L) break
                offset += page
            }
            remote.retainAll { it.storeId == storeId }

            val now = System.currentTimeMillis()
            database.withTransaction {
                val protectedIds = outboxDao.blockedEntityIds().toSet()
                customerDao.upsertAll(
                    remote
                        .filterNot { it.id in protectedIds }
                        .map { dto ->
                            CustomerEntity(
                                id = dto.id,
                                storeId = dto.storeId,
                                userId = dto.userId,
                                fullName = dto.fullName,
                                phone = dto.phone,
                                groupCode = dto.groupCode,
                                discountPercent = dto.discountPercent,
                                creditLimit = dto.creditLimit,
                                balanceCached = dto.balanceCached,
                                isActive = dto.isActive && dto.deletedAt == null,
                                updatedAt = com.pakdel.core.data.session.parseTimestampOrNull(dto.updatedAt)
                                    ?: now,
                            )
                        }
                )
            }
            remote.size
        }
    }
}

package com.pakdel.core.data.repository

import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.common.result.AppError
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.common.result.runCatchingApp
import com.pakdel.core.data.local.PakdelDatabase
import com.pakdel.core.data.remote.MyContextDto
import com.pakdel.core.data.session.Session
import com.pakdel.core.data.session.SessionManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class MemberFallbackDto(
    val id: String,
    @SerialName("store_id") val storeId: String,
    @SerialName("role_id") val roleId: String? = null,
)

@Serializable
private data class StoreDto(
    val id: String,
    val name: String,
)

@Singleton
class AuthRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val sessionManager: SessionManager,
    private val database: PakdelDatabase,
    private val dispatchers: DispatcherProvider,
) {

    val session: Flow<Session?> = sessionManager.session
    val storeName: Flow<String> = sessionManager.storeName

    suspend fun signIn(email: String, password: String): AppResult<Session> =
        withContext(dispatchers.io) {
            runCatchingApp {
                val normalizedEmail = email.trim().lowercase()
                if (normalizedEmail.isBlank() || password.isBlank()) {
                    throw IllegalArgumentException("VALIDATION: ایمیل و رمز عبور را وارد کنید.")
                }

                supabase.auth.signInWith(Email) {
                    this.email = normalizedEmail
                    this.password = password
                }

                val user = supabase.auth.currentUserOrNull()
                    ?: throw IllegalStateException("UNAUTHORIZED: ورود ناموفق بود.")

                val context = resolveContext(user.id)
                val storeId = context?.storeId
                    ?: user.appMetadata?.get("store_id")?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException(
                        "FORBIDDEN: حساب شما به هیچ فروشگاهی متصل نیست. با مدیر سیستم تماس بگیرید."
                    )

                val role = context?.role?.takeIf { it.isNotBlank() }
                    ?: user.appMetadata?.get("role")?.jsonPrimitive?.contentOrNull
                    ?: "cashier"

                val displayName = context?.displayName?.takeIf { it.isNotBlank() }
                    ?: user.userMetadata?.get("full_name")?.jsonPrimitive?.contentOrNull
                    ?: normalizedEmail.substringBefore('@')

                val storeLabel = context?.storeName?.takeIf { it.isNotBlank() }
                    ?: runCatching {
                        supabase.postgrest.from("stores").select()
                            .decodeList<StoreDto>().firstOrNull { it.id == storeId }?.name
                    }.getOrNull().orEmpty()

                // اگر دستگاه قبلاً در فروشگاه دیگری بوده، داده محلی پاک می‌شود (جداسازی فروشگاه)
                val previous = sessionManager.current()
                if (previous != null && previous.storeId != storeId) {
                    database.clearAllTables()
                }

                Session(
                    userId = user.id,
                    email = normalizedEmail,
                    storeId = storeId,
                    role = role,
                    displayName = displayName,
                ).also { sessionManager.save(it, storeLabel) }
            }
        }

    /**
     * خروج امن:
     * - اگر عملیات همگام‌نشده (در صف یا تعارض) وجود داشته باشد، خروج انجام نمی‌شود
     *   تا فروش آفلاین از بین نرود.
     * - پس از خروج موفق، پایگاه‌داده محلی پاک می‌شود تا داده فروشگاه قبلی باقی نماند.
     */
    suspend fun signOut(): AppResult<Unit> = withContext(dispatchers.io) {
        runCatchingApp {
            val unresolved = database.outboxDao().unresolvedCount()
            if (unresolved > 0) {
                throw IllegalStateException(
                    "VALIDATION: $unresolved عملیات همگام‌نشده در دستگاه وجود دارد. " +
                        "ابتدا همگام‌سازی را انجام دهید یا تعارض‌ها را تعیین تکلیف کنید."
                )
            }
            runCatching { supabase.auth.signOut() }
            database.clearAllTables()
            sessionManager.clear()
        }
    }

    /** خواندن مجدد نشست از حافظه (بدون فراخوانی شبکه) */
    suspend fun currentSession(): AppResult<Session> = withContext(dispatchers.io) {
        val session = sessionManager.current()
        if (session == null) AppResult.Failure(AppError.Unauthorized) else AppResult.Success(session)
    }

    /**
     * خواندن زمینه کاربر از دیتابیس (فروشگاه/نقش/نام).
     *
     * اگر مهاجرت `20260101000008_fixes.sql` اجرا نشده باشد، به رفتار قبلی
     * (claim در app_metadata و سپس store_members) برمی‌گردیم.
     */
    private suspend fun resolveContext(userId: String): MyContextDto? {
        val fromRpc = runCatching {
            supabase.postgrest.rpc("rpc_my_context").decodeAs<MyContextDto>()
        }.getOrNull()
        if (fromRpc != null) return fromRpc

        val member = runCatching {
            supabase.postgrest.from("store_members").select()
                .decodeList<MemberFallbackDto>().firstOrNull()
        }.getOrNull() ?: return null

        return MyContextDto(
            userId = userId,
            storeId = member.storeId,
            role = "",
            storeName = "",
            displayName = "",
            email = "",
        )
    }
}

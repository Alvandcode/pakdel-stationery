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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    /** آیا کاربر در Supabase Auth نشست دارد (حتی اگر به فروشگاهی وصل نباشد)؟ */
    fun hasAuthSession(): Boolean = supabase.auth.currentSessionOrNull() != null

    suspend fun signIn(email: String, password: String): AppResult<Session> =
        withContext(dispatchers.io) {
            runCatchingApp {
                val normalizedEmail = email.trim().lowercase()
                if (normalizedEmail.isBlank() || password.isBlank()) {
                    throw IllegalArgumentException("VALIDATION: ایمیل و رمز عبور را وارد کنید.")
                }

                try {
                    supabase.auth.signInWith(Email) {
                        this.email = normalizedEmail
                        this.password = password
                    }
                } catch (authError: Exception) {
                    throw IllegalArgumentException("AUTH_FAILED: ${translateAuthError(authError)}")
                }

                establishSession(normalizedEmail, fallbackName = null)
            }
        }

    /**
     * ثبت‌نام خودکار: ساخت حساب + (ساخت فروشگاه جدید | پیوستن با کد دعوت).
     *
     * اگر تنظیمات پروژه تأیید ایمیل را اجباری کرده باشد، پس از ثبت‌نام نشست
     * ساخته نمی‌شود و پیام روشن «ایمیل خود را تأیید کنید» برگردانده می‌شود.
     */
    suspend fun signUp(
        email: String,
        password: String,
        fullName: String,
        inviteCode: String?,
        storeName: String?,
    ): AppResult<Session> = withContext(dispatchers.io) {
        runCatchingApp {
            val normalizedEmail = email.trim().lowercase()
            val name = fullName.trim()
            val code = inviteCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            val store = storeName?.trim()?.takeIf { it.isNotBlank() }

            if (normalizedEmail.isBlank() || !normalizedEmail.contains('@')) {
                throw IllegalArgumentException("VALIDATION: ایمیل معتبر وارد کنید.")
            }
            if (password.length < 6) {
                throw IllegalArgumentException("VALIDATION: رمز عبور باید حداقل ۶ کاراکتر باشد.")
            }
            if (code == null && store == null) {
                throw IllegalArgumentException(
                    "VALIDATION: یا کد دعوت فروشگاه را وارد کنید یا نام فروشگاه جدید را."
                )
            }

            val alreadyRegistered: Boolean = try {
                supabase.auth.signUpWith(Email) {
                    this.email = normalizedEmail
                    this.password = password
                    data = buildJsonObject {
                        put("full_name", name)
                    }
                }
                false
            } catch (authError: Exception) {
                val raw = authError.message.orEmpty()
                // حساب از قبل وجود دارد: به‌جای خطا، با همان رمز وارد می‌شویم و
                // سپس فروشگاه ساخته/کد دعوت اعمال می‌شود (مسیر کاربران موجود).
                if (raw.contains("already registered", true) ||
                    raw.contains("already exists", true) ||
                    raw.contains("User already", true)
                ) {
                    true
                } else {
                    throw IllegalArgumentException("AUTH_FAILED: ${translateAuthError(authError)}")
                }
            }

            if (alreadyRegistered) {
                try {
                    supabase.auth.signInWith(Email) {
                        this.email = normalizedEmail
                        this.password = password
                    }
                } catch (authError: Exception) {
                    throw IllegalArgumentException("AUTH_FAILED: ${translateAuthError(authError)}")
                }
            }

            if (supabase.auth.currentSessionOrNull() == null) {
                throw IllegalArgumentException(
                    "AUTH_CONFIRM: حساب ساخته شد. لینک تأیید به ایمیل شما ارسال شد؛ " +
                        "پس از تأیید، با همان ایمیل و رمز وارد شوید."
                )
            }

            if (code != null) {
                supabase.postgrest.rpc(
                    "rpc_join_store",
                    buildJsonObject { put("p_code", code) },
                )
            } else {
                supabase.postgrest.rpc(
                    "rpc_create_store",
                    buildJsonObject { put("p_name", store!!) },
                )
            }

            establishSession(normalizedEmail, fallbackName = name)
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
     * ساخت Session کامل از کاربر جاری: فروشگاه، نقش و نام.
     * اگر کاربر به فروشگاهی متصل نباشد، خطای FORBIDDEN با پیام فارسی برگردانده می‌شود.
     */
    private suspend fun establishSession(
        normalizedEmail: String,
        fallbackName: String?,
    ): Session {
        val user = supabase.auth.currentUserOrNull()
            ?: throw IllegalStateException("UNAUTHORIZED: ورود ناموفق بود.")

        val context = resolveContext(user.id)
            ?: throw IllegalStateException(
                "FORBIDDEN: حساب شما به هیچ فروشگاهی متصل نیست. " +
                    "با کد دعوت به فروشگاه بپیوندید یا فروشگاه جدید بسازید."
            )

        val storeId = context.storeId
        val role = context.role.takeIf { it.isNotBlank() }
            ?: user.appMetadata?.get("role")?.jsonPrimitive?.contentOrNull
            ?: "cashier"

        val displayName = context.displayName.takeIf { it.isNotBlank() }
            ?: fallbackName
            ?: user.userMetadata?.get("full_name")?.jsonPrimitive?.contentOrNull
            ?: normalizedEmail.substringBefore('@')

        val storeLabel = context.storeName.takeIf { it.isNotBlank() }
            ?: runCatching {
                supabase.postgrest.from("stores").select()
                    .decodeList<StoreDto>().firstOrNull { it.id == storeId }?.name
            }.getOrNull().orEmpty()

        // اگر دستگاه قبلاً در فروشگاه دیگری بوده، داده محلی پاک می‌شود (جداسازی فروشگاه)
        val previous = sessionManager.current()
        if (previous != null && previous.storeId != storeId) {
            database.clearAllTables()
        }

        return Session(
            userId = user.id,
            email = normalizedEmail,
            storeId = storeId,
            role = role,
            displayName = displayName,
        ).also { sessionManager.save(it, storeLabel) }
    }

    /** ترجمهٔ خطاهای خام Supabase Auth به پیام فارسی قابل‌فهم. */
    private fun translateAuthError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("Invalid login credentials", true) ->
                "ایمیل یا رمز عبور اشتباه است."
            raw.contains("already registered", true) ||
                raw.contains("already exists", true) ->
                "این ایمیل قبلاً ثبت شده است؛ وارد شوید یا رمز را بازیابی کنید."
            raw.contains("Email not confirmed", true) ->
                "ایمیل شما هنوز تأیید نشده است. لینک تأیید را در ایمیل خود باز کنید."
            raw.contains("Password should be", true) ->
                "رمز عبور ضعیف است؛ حداقل ۶ کاراکتر بگذارید."
            raw.contains("Plugin", true) ->
                "اتصال به سرور پیکربندی نشده است. نسخهٔ جدید برنامه را نصب کنید."
            raw.contains("timeout", true) || raw.contains("connect", true) ->
                "اتصال به سرور برقرار نشد. اینترنت گوشی را بررسی کنید."
            else -> "خطای سرور: $raw"
        }
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

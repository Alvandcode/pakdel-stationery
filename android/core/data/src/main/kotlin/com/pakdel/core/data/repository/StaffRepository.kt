package com.pakdel.core.data.repository

import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.common.result.runCatchingApp
import com.pakdel.core.data.remote.MemberDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مدیریت کارکنان.
 *
 * دو راه برای افزودن همکار:
 *   ۱) کد دعوت فروشگاه (`rpc_store_invite_code`) که همکار در اپ خودش
 *      «ساخت حساب + پیوستن با کد» را انجام می‌دهد — بدون دخالت مدیر.
 *   ۲) افزودن با ایمیل (`rpc_invite_member`) برای حسابی که از قبل ساخته شده.
 * همه عملیات با مجوز `staff.manage` در سرور کنترل می‌شوند و کلید
 * `service_role` هرگز در اپ قرار نمی‌گیرد.
 */
@Serializable
private data class InviteCodeDto(
    @SerialName("invite_code") val inviteCode: String = "",
)

@Singleton
class StaffRepository @Inject constructor(
    private val supabase: SupabaseClient,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun list(): AppResult<List<MemberDto>> = withContext(dispatchers.io) {
        runCatchingApp {
            supabase.postgrest.rpc("rpc_list_members").decodeList<MemberDto>()
        }
    }

    suspend fun setRole(memberId: String, roleCode: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingApp {
                supabase.postgrest.rpc(
                    "rpc_set_member_role",
                    buildJsonObject {
                        put("p_member_id", memberId)
                        put("p_role_code", roleCode)
                    },
                )
                Unit
            }
        }

    suspend fun setActive(memberId: String, active: Boolean): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingApp {
                supabase.postgrest.rpc(
                    "rpc_set_member_active",
                    buildJsonObject {
                        put("p_member_id", memberId)
                        put("p_active", active)
                    },
                )
                Unit
            }
        }

    /** افزودن/فعال‌سازی عضویت کاربری که قبلاً در Supabase Auth ساخته شده است. */
    suspend fun invite(email: String, roleCode: String, fullName: String): AppResult<MemberDto> =
        withContext(dispatchers.io) {
            runCatchingApp {
                supabase.postgrest.rpc(
                    "rpc_invite_member",
                    buildJsonObject {
                        put("p_email", email.trim())
                        put("p_role_code", roleCode)
                        put("p_full_name", fullName.trim().ifBlank { null })
                    },
                ).decodeAs<MemberDto>()
            }
        }

    /** کد دعوت فروشگاه جاری (فقط با مجوز staff.manage). */
    suspend fun inviteCode(): AppResult<String> = withContext(dispatchers.io) {
        runCatchingApp {
            supabase.postgrest.rpc("rpc_store_invite_code")
                .decodeAs<InviteCodeDto>().inviteCode
        }
    }

    /** ساخت کد دعوت جدید؛ کد قبلی باطل می‌شود. */
    suspend fun rotateInviteCode(): AppResult<String> = withContext(dispatchers.io) {
        runCatchingApp {
            supabase.postgrest.rpc("rpc_rotate_invite_code")
                .decodeAs<InviteCodeDto>().inviteCode
        }
    }
}

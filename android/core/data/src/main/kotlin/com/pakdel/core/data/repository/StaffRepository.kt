package com.pakdel.core.data.repository

import com.pakdel.core.common.coroutines.DispatcherProvider
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.common.result.runCatchingApp
import com.pakdel.core.data.remote.MemberDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * مدیریت کارکنان.
 *
 * ساخت کاربر Auth بیرون از اپ (داشبورد Supabase) انجام می‌شود تا کلید
 * `service_role` هرگز در اپ قرار نگیرد؛ سپس `rpc_invite_member` فقط
 * عضویت و نقش همان ایمیل را در فروشگاه ثبت می‌کند.
 * همه عملیات با مجوز `staff.manage` در سرور کنترل می‌شوند.
 */
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
}

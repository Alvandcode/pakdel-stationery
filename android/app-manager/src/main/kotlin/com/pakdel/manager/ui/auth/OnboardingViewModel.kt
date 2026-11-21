package com.pakdel.manager.ui.auth

import androidx.lifecycle.ViewModel
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * ثبت‌نام خودکار: کاربر یا فروشگاه جدید می‌سازد (مالک می‌شود)
 * یا با کد دعوت به فروشگاه موجود می‌پیوندد.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** @return پیام خطا یا null در صورت موفقیت (تغییر Session صفحه را عوض می‌کند). */
    suspend fun submit(
        fullName: String,
        email: String,
        password: String,
        inviteCode: String,
        storeName: String,
    ): String? = when (
        val result = authRepository.signUp(
            email = email,
            password = password,
            fullName = fullName,
            inviteCode = inviteCode.takeIf { it.isNotBlank() },
            storeName = storeName.takeIf { it.isNotBlank() },
        )
    ) {
        is AppResult.Success -> null
        is AppResult.Failure -> result.error.message
    }
}

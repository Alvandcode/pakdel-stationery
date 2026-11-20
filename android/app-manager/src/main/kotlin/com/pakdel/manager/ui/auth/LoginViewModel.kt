package com.pakdel.manager.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** @return پیام خطا یا null در صورت موفقیت؛ تغییر Session باعث جابه‌جایی صفحه می‌شود. */
    suspend fun signIn(email: String, password: String): String? {
        return when (val result = authRepository.signIn(email, password)) {
            is AppResult.Success -> null
            is AppResult.Failure -> result.error.message
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}

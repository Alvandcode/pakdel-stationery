package com.pakdel.manager.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    init {
        // نقش و نام از سرور تازه می‌شود تا ارتقا/غیرفعال‌سازی کارمند
        // بدون خروج و ورود دوباره در رابط کاربری اعمال شود.
        viewModelScope.launch { authRepository.refreshContext() }
    }

    val state: StateFlow<RootState> = authRepository.session
        .map { session ->
            if (session == null) RootState.LoggedOut else RootState.LoggedIn(session)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RootState.Loading,
        )
}

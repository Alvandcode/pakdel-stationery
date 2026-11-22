package com.pakdel.manager.ui.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.data.local.entity.SaleEntity
import com.pakdel.core.data.local.entity.SaleItemEntity
import com.pakdel.core.data.repository.SalesRepository
import com.pakdel.core.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class InvoiceDetail(
    val sale: SaleEntity,
    val items: List<SaleItemEntity>,
)

@HiltViewModel
class InvoicesViewModel @Inject constructor(
    private val salesRepository: SalesRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    val canCancel: StateFlow<Boolean> = sessionManager.session
        .map { it?.canCancelSale == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sales: StateFlow<List<SaleEntity>> = salesRepository.observeRecentSales()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _detail = MutableStateFlow<InvoiceDetail?>(null)
    val detail: StateFlow<InvoiceDetail?> = _detail.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun open(saleId: String) {
        viewModelScope.launch {
            val pair = salesRepository.saleWithItems(saleId) ?: return@launch
            _detail.value = InvoiceDetail(pair.first, pair.second)
        }
    }

    fun close() { _detail.value = null }

    /**
     * لغو فاکتور (برگشت کالا به انبار و برگشت بدهی مشتری).
     * سرور همین کار را اتمیک تکرار می‌کند؛ عملیات در صف همگام‌سازی می‌ماند.
     */
    fun cancel(saleId: String, reason: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            when (val result = salesRepository.cancelLocally(saleId, reason.trim())) {
                is AppResult.Success -> {
                    _message.value = "فاکتور لغو شد؛ کالاها به انبار برگشتند."
                    _detail.value = null
                }
                is AppResult.Failure -> _error.value = result.error.message
            }
            _busy.value = false
        }
    }

    fun consumeMessage() { _message.value = null }
    fun consumeError() { _error.value = null }
}

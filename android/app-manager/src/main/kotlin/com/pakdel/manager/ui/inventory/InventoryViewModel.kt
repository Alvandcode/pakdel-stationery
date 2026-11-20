package com.pakdel.manager.ui.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.common.money.PersianDigits
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.data.local.dao.InventoryMovementRow
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.repository.InventoryRepository
import com.pakdel.core.data.repository.ProductRepository
import com.pakdel.core.data.repository.StockMovementType
import com.pakdel.core.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StockDialogState(
    val product: ProductEntity? = null,
    val type: StockMovementType = StockMovementType.In,
    val qty: String = "",
    val reason: String = "",
    val saving: Boolean = false,
    val error: String? = null,
) {
    val isOpen: Boolean get() = product != null
    val previewStock: Int
        get() {
            val product = product ?: return 0
            val amount = qty.toIntOrNull() ?: 0
            return when (type) {
                StockMovementType.In, StockMovementType.ReturnPurchase -> product.stockCached + amount
                StockMovementType.Damage -> product.stockCached - amount
                StockMovementType.Adjustment -> amount
            }
        }
    val delta: Int
        get() {
            val product = product ?: return 0
            val amount = qty.toIntOrNull() ?: 0
            return previewStock - product.stockCached
        }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    productRepository: ProductRepository,
    sessionManager: SessionManager,
) : ViewModel() {

    /** فقط نقش‌های دارای مجوز inventory.adjust می‌توانند موجودی را تغییر دهند. */
    val canAdjust: StateFlow<Boolean> = sessionManager.session
        .map { it?.canAdjustStock == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val query = MutableStateFlow("")

    private val _dialog = MutableStateFlow(StockDialogState())
    val dialog: StateFlow<StockDialogState> = _dialog.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val products: StateFlow<List<ProductEntity>> = query
        .debounce(200)
        .flatMapLatest { text ->
            if (text.isBlank()) productRepository.observeProducts() else productRepository.search(text)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val movements: StateFlow<List<InventoryMovementRow>> = inventoryRepository.observeMovements()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) { query.value = value }

    fun open(product: ProductEntity, type: StockMovementType) {
        if (!canAdjust.value) {
            _message.value = "شما مجوز اصلاح موجودی ندارید."
            return
        }
        _dialog.value = StockDialogState(
            product = product,
            type = type,
            qty = when (type) {
                StockMovementType.Adjustment -> product.stockCached.toString()
                else -> ""
            },
        )
    }

    fun close() { _dialog.value = StockDialogState() }

    fun onTypeChange(type: StockMovementType) = _dialog.update {
        it.copy(
            type = type,
            qty = if (type == StockMovementType.Adjustment) it.product?.stockCached?.toString().orEmpty() else it.qty,
            error = null,
        )
    }

    fun onQtyChange(value: String) = _dialog.update {
        it.copy(qty = PersianDigits.toLatin(value).filter { ch -> ch.isDigit() }, error = null)
    }

    fun onReasonChange(value: String) = _dialog.update { it.copy(reason = value, error = null) }

    fun submit() {
        val state = _dialog.value
        val product = state.product ?: return
        if (state.saving) return
        if (!canAdjust.value) {
            _dialog.update { it.copy(error = "شما مجوز اصلاح موجودی ندارید.") }
            return
        }

        val amount = state.qty.toIntOrNull()
        if (amount == null || amount < 0) {
            _dialog.update { it.copy(error = "تعداد را به‌درستی وارد کنید.") }
            return
        }
        val delta = when (state.type) {
            StockMovementType.In, StockMovementType.ReturnPurchase -> amount
            StockMovementType.Damage -> -amount
            StockMovementType.Adjustment -> amount - product.stockCached
        }
        if (delta == 0) {
            _dialog.update { it.copy(error = "مقدار وارد‌شده با موجودی فعلی یکسان است.") }
            return
        }

        _dialog.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = inventoryRepository.adjust(
                product = product,
                qtyDelta = delta,
                type = state.type,
                reason = state.reason,
            )
            when (result) {
                is AppResult.Success -> {
                    _message.value = "موجودی «${product.name}» به ${result.data} تغییر کرد."
                    _dialog.value = StockDialogState()
                }
                is AppResult.Failure -> _dialog.update {
                    it.copy(saving = false, error = result.error.message)
                }
            }
        }
    }

    fun consumeMessage() { _message.value = null }
}

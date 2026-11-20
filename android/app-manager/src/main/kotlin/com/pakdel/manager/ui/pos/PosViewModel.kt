package com.pakdel.manager.ui.pos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.data.local.entity.CustomerEntity
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.repository.CartLine
import com.pakdel.core.data.repository.CartPayment
import com.pakdel.core.data.repository.CustomerDraft
import com.pakdel.core.data.repository.CustomerRepository
import com.pakdel.core.data.repository.ProductRepository
import com.pakdel.core.data.repository.SaleDraft
import com.pakdel.core.data.repository.SalesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CartState(
    val lines: List<CartLine> = emptyList(),
    val invoiceDiscount: Long = 0L,
) {
    val subtotal: Long get() = lines.sumOf { it.unitPrice * it.qty }
    val itemDiscount: Long get() = lines.sumOf { it.discount }
    val discountTotal: Long get() = itemDiscount + invoiceDiscount
    val grandTotal: Long get() = (subtotal - discountTotal).coerceAtLeast(0L)
    val quantity: Int get() = lines.sumOf { it.qty }
    val isEmpty: Boolean get() = lines.isEmpty()
}

data class CustomerFormState(
    val open: Boolean = false,
    val query: String = "",
    val name: String = "",
    val phone: String = "",
    val saving: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class PosViewModel @Inject constructor(
    private val productRepository: ProductRepository,
    private val salesRepository: SalesRepository,
    private val customerRepository: CustomerRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _cart = MutableStateFlow(CartState())
    val cart: StateFlow<CartState> = _cart.asStateFlow()

    private val _paidInput = MutableStateFlow("")
    val paidInput: StateFlow<String> = _paidInput.asStateFlow()

    private val _selectedCustomer = MutableStateFlow<CustomerEntity?>(null)
    val selectedCustomer: StateFlow<CustomerEntity?> = _selectedCustomer.asStateFlow()

    private val _customerForm = MutableStateFlow(CustomerFormState())
    val customerForm: StateFlow<CustomerFormState> = _customerForm.asStateFlow()

    /** موجودی کالا در لحظه افزودن به سبد؛ برای جلوگیری از فروش بیش از موجودی */
    private val stockLimit = mutableMapOf<String, Int>()

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val results: StateFlow<List<ProductEntity>> = _query
        .debounce(200)
        .flatMapLatest { text ->
            if (text.isBlank()) emptyFlow() else productRepository.search(text)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customers: StateFlow<List<CustomerEntity>> = customerRepository
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) { _query.value = value }

    fun addProduct(product: ProductEntity) {
        val current = _cart.value.lines.find { it.productId == product.id }?.qty ?: 0
        if (current + 1 > product.stockCached) {
            _error.value = "موجودی «${product.name}» کافی نیست (${product.stockCached} ${product.unit})."
            return
        }
        stockLimit[product.id] = product.stockCached
        _cart.update { state ->
            state.copy(
                lines = if (current > 0) {
                    state.lines.map {
                        if (it.productId == product.id) it.copy(qty = it.qty + 1) else it
                    }
                } else {
                    state.lines + CartLine(
                        productId = product.id,
                        name = product.name,
                        qty = 1,
                        unitPrice = product.salePrice,
                        unitCost = product.purchasePrice,
                    )
                }
            )
        }
        _error.value = null
    }

    fun increase(productId: String) {
        val line = _cart.value.lines.find { it.productId == productId } ?: return
        val limit = stockLimit[productId] ?: Int.MAX_VALUE
        if (line.qty + 1 > limit) {
            _error.value = "موجودی این کالا بیشتر از ${limit} عدد نیست."
            return
        }
        _cart.update { state ->
            state.copy(lines = state.lines.map {
                if (it.productId == productId) it.copy(qty = it.qty + 1) else it
            })
        }
    }

    fun decrease(productId: String) = _cart.update { state ->
        state.copy(
            lines = state.lines.mapNotNull {
                if (it.productId != productId) it
                else if (it.qty <= 1) null
                else it.copy(qty = it.qty - 1)
            }
        )
    }

    fun remove(productId: String) = _cart.update { state ->
        state.copy(lines = state.lines.filterNot { it.productId == productId })
    }

    fun setInvoiceDiscount(raw: String) {
        val normalized = com.pakdel.core.common.money.PersianDigits.toLatin(raw)
            .filter { it.isDigit() }
            .toLongOrNull() ?: 0L
        _cart.update { it.copy(invoiceDiscount = normalized) }
        _error.value = null
    }

    fun setPaidInput(raw: String) {
        _paidInput.value = com.pakdel.core.common.money.PersianDigits.toLatin(raw)
            .filter { it.isDigit() }
    }

    // ---------- مشتری ----------
    fun selectCustomer(customer: CustomerEntity) {
        _selectedCustomer.value = customer
        _customerForm.update { it.copy(open = false, error = null) }
        _error.value = null
    }

    fun clearCustomer() { _selectedCustomer.value = null }

    fun openCustomerPicker() = _customerForm.update {
        it.copy(open = true, error = null, saving = false)
    }

    fun closeCustomerPicker() = _customerForm.update {
        it.copy(open = false, error = null, saving = false)
    }

    fun onCustomerQueryChange(value: String) = _customerForm.update {
        it.copy(query = value, error = null)
    }

    fun onNewCustomerName(value: String) = _customerForm.update {
        it.copy(name = value, error = null)
    }

    fun onNewCustomerPhone(value: String) = _customerForm.update {
        it.copy(
            phone = com.pakdel.core.common.money.PersianDigits.toLatin(value).filter { ch -> ch.isDigit() },
            error = null,
        )
    }

    fun createCustomer() {
        val form = _customerForm.value
        if (form.saving) return
        if (form.name.isBlank()) {
            _customerForm.update { it.copy(error = "نام مشتری را وارد کنید.") }
            return
        }
        _customerForm.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = customerRepository.save(
                CustomerDraft(
                    fullName = form.name,
                    phone = form.phone.takeIf { it.isNotBlank() },
                )
            )) {
                is AppResult.Success -> {
                    _selectedCustomer.value = result.data
                    _customerForm.value = CustomerFormState()
                }
                is AppResult.Failure -> _customerForm.update {
                    it.copy(saving = false, error = result.error.message)
                }
            }
        }
    }

    fun consumeMessage() { _message.value = null }
    fun consumeError() { _error.value = null }

    fun checkout() {
        val cart = _cart.value
        if (cart.isEmpty || _submitting.value) return

        if (cart.invoiceDiscount > cart.subtotal - cart.itemDiscount) {
            _error.value = "تخفیف فاکتور بیش از مبلغ قابل تخفیف است."
            return
        }

        val paid = _paidInput.value.toLongOrNull() ?: 0L
        if (paid > cart.grandTotal) {
            _error.value = "مبلغ پرداختی بیش از مبلغ فاکتور است."
            return
        }

        val customer = _selectedCustomer.value
        if (paid < cart.grandTotal && customer == null) {
            _error.value = "برای فروش نسیه، مشتری را انتخاب یا ثبت کنید."
            return
        }

        val payments = if (paid > 0) {
            listOf(CartPayment(method = "cash", amount = paid))
        } else {
            emptyList()
        }

        _submitting.value = true
        viewModelScope.launch {
            val result = salesRepository.checkout(
                SaleDraft(
                    customerId = customer?.id,
                    customerName = customer?.fullName,
                    lines = cart.lines,
                    invoiceDiscount = cart.invoiceDiscount,
                    payments = payments,
                    note = null,
                    paymentMethod = if (paid > 0) "cash" else "credit",
                )
            )
            _submitting.value = false
            when (result) {
                is AppResult.Success -> {
                    _message.value = "فاکتور ${result.data.invoiceNumber} ثبت شد؛ " +
                        "همگام‌سازی به‌صورت خودکار انجام می‌شود."
                    _cart.value = CartState()
                    _paidInput.value = ""
                    _query.value = ""
                    _selectedCustomer.value = null
                    stockLimit.clear()
                }
                is AppResult.Failure -> _error.value = result.error.message
            }
        }
    }
}

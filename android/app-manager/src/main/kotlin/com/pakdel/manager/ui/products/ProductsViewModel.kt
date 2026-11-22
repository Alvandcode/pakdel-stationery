package com.pakdel.manager.ui.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.common.id.Uuids
import com.pakdel.core.common.money.PersianDigits
import com.pakdel.core.common.result.AppResult
import com.pakdel.core.data.local.entity.CategoryEntity
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.repository.ProductDraft
import com.pakdel.core.data.repository.ProductRepository
import com.pakdel.core.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class ProductFormState(
    val open: Boolean = false,
    val id: String = Uuids.newId(),
    val editingId: String? = null,
    val name: String = "",
    val barcode: String = "",
    val sku: String = "",
    val brand: String = "",
    val description: String = "",
    val unit: String = "عدد",
    val categoryId: String? = null,
    val purchasePrice: String = "",
    val salePrice: String = "",
    val wholesalePrice: String = "",
    val minStock: String = "0",
    val initialStock: String = "0",
    val isPublic: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val isNew: Boolean get() = editingId == null
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProductsViewModel @Inject constructor(
    private val repository: ProductRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    val query = MutableStateFlow("")
    val refreshing = MutableStateFlow(false)

    val publishing = MutableStateFlow(false)

    /** فقط نقش‌های دارای مجوز product.manage می‌توانند کالا ثبت/ویرایش/حذف کنند. */
    val canManage: StateFlow<Boolean> = sessionManager.session
        .map { it?.canManageProducts == true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _form = MutableStateFlow(ProductFormState())
    val form: StateFlow<ProductFormState> = _form.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val products: StateFlow<List<ProductEntity>> = query
        .debounce(200)
        .flatMapLatest { text ->
            if (text.isBlank()) repository.observeProducts() else repository.search(text)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) { query.value = value }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            val storeId = sessionManager.current()?.storeId
            when (val result = repository.refreshFromServer(storeId)) {
                is AppResult.Success -> _message.value = "${result.data} کالا به‌روزرسانی شد."
                is AppResult.Failure -> _message.value = result.error.message
            }
            refreshing.value = false
        }
    }

    /** انتشار همه کالاهای فعال در کاتالوگ اپ مشتری (یک تراکنش در سرور). */
    fun publishAll() {
        if (publishing.value) return
        if (!canManage.value) {
            _message.value = "شما مجوز مدیریت محصولات ندارید."
            return
        }
        publishing.value = true
        viewModelScope.launch {
            when (val result = repository.publishAll()) {
                is AppResult.Success -> {
                    _message.value = if (result.data == 0) {
                        "همهٔ کالاهای فعال از قبل منتشر شده بودند."
                    } else {
                        "${result.data} کالا در فروشگاه آنلاین منتشر شد."
                    }
                    refresh()
                }
                is AppResult.Failure -> _message.value = result.error.message
            }
            publishing.value = false
        }
    }

    fun openNew() {
        if (!canManage.value) {
            _message.value = "شما مجوز مدیریت محصولات ندارید."
            return
        }
        _form.value = ProductFormState(open = true, id = Uuids.newId())
    }

    fun openEdit(product: ProductEntity) {
        if (!canManage.value) {
            _message.value = "شما مجوز مدیریت محصولات ندارید."
            return
        }
        _form.value = ProductFormState(
            open = true,
            id = product.id,
            editingId = product.id,
            name = product.name,
            barcode = product.barcode.orEmpty(),
            sku = product.sku.orEmpty(),
            brand = product.brand.orEmpty(),
            description = product.description.orEmpty(),
            unit = product.unit,
            categoryId = product.categoryId,
            purchasePrice = if (product.purchasePrice > 0) product.purchasePrice.toString() else "",
            salePrice = if (product.salePrice > 0) product.salePrice.toString() else "",
            wholesalePrice = if (product.wholesalePrice > 0) product.wholesalePrice.toString() else "",
            minStock = product.minStock.toString(),
            initialStock = "0",
            isPublic = product.isPublic,
        )
    }

    fun closeForm() {
        _form.update { it.copy(open = false, error = null) }
    }

    fun onNameChange(value: String) = _form.update { it.copy(name = value, error = null) }
    fun onBarcodeChange(value: String) = _form.update {
        it.copy(barcode = PersianDigits.toLatin(value).filter { ch -> ch.isDigit() }, error = null)
    }
    fun onSkuChange(value: String) = _form.update { it.copy(sku = value, error = null) }
    fun onBrandChange(value: String) = _form.update { it.copy(brand = value) }
    fun onDescriptionChange(value: String) = _form.update { it.copy(description = value) }
    fun onUnitChange(value: String) = _form.update { it.copy(unit = value) }
    fun onCategoryChange(id: String?) = _form.update { it.copy(categoryId = id) }
    fun onPurchaseChange(value: String) = _form.update { it.copy(purchasePrice = digits(value)) }
    fun onSaleChange(value: String) = _form.update { it.copy(salePrice = digits(value)) }
    fun onWholesaleChange(value: String) = _form.update { it.copy(wholesalePrice = digits(value)) }
    fun onMinStockChange(value: String) = _form.update { it.copy(minStock = digits(value)) }
    fun onInitialStockChange(value: String) = _form.update { it.copy(initialStock = digits(value)) }
    fun onPublicChange(value: Boolean) = _form.update { it.copy(isPublic = value) }

    fun save() {
        val state = _form.value
        if (state.saving) return
        if (!canManage.value) {
            _form.update { it.copy(error = "شما مجوز مدیریت محصولات ندارید.") }
            return
        }

        val draft = ProductDraft(
            id = state.id,
            name = state.name,
            barcode = state.barcode.ifBlank { null },
            sku = state.sku.ifBlank { null },
            categoryId = state.categoryId,
            brand = state.brand.ifBlank { null },
            description = state.description.ifBlank { null },
            unit = state.unit,
            purchasePrice = state.purchasePrice.toLongOrNull() ?: 0L,
            salePrice = state.salePrice.toLongOrNull() ?: 0L,
            wholesalePrice = state.wholesalePrice.toLongOrNull() ?: 0L,
            minStock = state.minStock.toIntOrNull() ?: 0,
            initialStock = state.initialStock.toIntOrNull() ?: 0,
            isPublic = state.isPublic,
        )

        _form.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.saveProduct(draft)) {
                is AppResult.Success -> {
                    _form.value = ProductFormState()
                    _message.value = if (state.isNew) "محصول «${result.data.name}» ثبت شد." else "تغییرات ذخیره شد."
                }
                is AppResult.Failure -> _form.update {
                    it.copy(saving = false, error = result.error.message)
                }
            }
        }
    }

    fun archive(product: ProductEntity) {
        if (!canManage.value) {
            _message.value = "شما مجوز مدیریت محصولات ندارید."
            return
        }
        viewModelScope.launch {
            when (val result = repository.archiveProduct(product.id)) {
                is AppResult.Success -> _message.value = "محصول «${product.name}» غیرفعال شد."
                is AppResult.Failure -> _message.value = result.error.message
            }
        }
    }

    fun consumeMessage() { _message.value = null }

    private fun digits(value: String): String =
        PersianDigits.toLatin(value).filter { it.isDigit() }
}

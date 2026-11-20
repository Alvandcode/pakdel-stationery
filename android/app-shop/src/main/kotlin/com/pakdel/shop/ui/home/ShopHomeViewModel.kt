package com.pakdel.shop.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.data.local.entity.CategoryEntity
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShopHomeViewModel @Inject constructor(
    private val repository: ProductRepository,
) : ViewModel() {

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val products: StateFlow<List<ProductEntity>> = combine(
        repository.observeProducts(),
        _selectedCategory,
    ) { all, categoryId ->
        if (categoryId == null) all else all.filter { it.categoryId == categoryId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun selectCategory(id: String?) { _selectedCategory.value = id }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val result = repository.refreshFromServer()
            _error.value = if (result is com.pakdel.core.common.result.AppResult.Failure) {
                result.error.message
            } else {
                null
            }
            _refreshing.value = false
        }
    }
}

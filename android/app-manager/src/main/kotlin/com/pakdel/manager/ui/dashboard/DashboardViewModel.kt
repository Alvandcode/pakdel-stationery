package com.pakdel.manager.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.repository.ProductRepository
import com.pakdel.core.data.repository.SalesRepository
import com.pakdel.core.data.session.SessionManager
import com.pakdel.core.data.sync.SyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class DashboardUiState(
    val storeName: String = "",
    val userName: String = "",
    val todaySales: Long = 0L,
    val invoiceCount: Int = 0,
    val grossProfit: Long = 0L,
    val stockValue: Long = 0L,
    val productCount: Int = 0,
    val lowStock: List<ProductEntity> = emptyList(),
    val pendingSync: Int = 0,
    val conflictCount: Int = 0,
    val lastSyncAt: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    sessionManager: SessionManager,
    salesRepository: SalesRepository,
    productRepository: ProductRepository,
    syncEngine: SyncEngine,
) : ViewModel() {

    val refreshing = MutableStateFlow(false)

    /**
     * بازه «امروز» هر دقیقه دوباره محاسبه می‌شود تا پس از نیمه‌شب
     * بدون راه‌اندازی مجدد برنامه درست بماند.
     */
    private val todayWindow: Flow<LongRange> = flow {
        while (true) {
            val start = startOfDay()
            emit(start..(start + DAY_MILLIS))
            delay(60_000)
        }
    }

    private val account = combine(
        sessionManager.session,
        sessionManager.storeName,
    ) { session, storeName -> session to storeName }

    private val todayTotals = todayWindow.flatMapLatest { window ->
        combine(
            salesRepository.observeSalesTotal(window.first, window.last),
            salesRepository.observeInvoiceCount(window.first, window.last),
            salesRepository.observeCogs(window.first, window.last),
        ) { total, count, cogs -> Triple(total, count, cogs) }
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        account,
        todayTotals,
        productRepository.observeProducts(),
        productRepository.observeLowStock(),
        syncEngine.observeStatus(),
    ) { account, totals, products, lowStock, sync ->
        DashboardUiState(
            storeName = account.second,
            userName = account.first?.displayName.orEmpty(),
            todaySales = totals.first,
            invoiceCount = totals.second,
            grossProfit = totals.first - totals.third,
            stockValue = products.sumOf { it.stockCached.toLong() * it.purchasePrice },
            productCount = products.size,
            lowStock = lowStock,
            pendingSync = sync.pendingCount,
            conflictCount = sync.conflictCount,
            lastSyncAt = sync.lastSuccessAt,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            syncEngine.syncNow()
            refreshing.value = false
        }
    }

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}

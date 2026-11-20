package com.pakdel.manager.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pakdel.core.common.date.JalaliDate
import com.pakdel.core.common.money.Toman
import com.pakdel.core.designsystem.component.StatCard
import com.pakdel.core.designsystem.component.SyncState
import com.pakdel.core.designsystem.component.SyncStatusChip
import com.pakdel.core.designsystem.theme.DangerRed
import com.pakdel.core.designsystem.theme.InfoCyan
import com.pakdel.core.designsystem.theme.PakdelAmber
import com.pakdel.core.designsystem.theme.SuccessGreen

@Composable
fun DashboardScreen(viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()

    val syncState = when {
        state.conflictCount > 0 -> SyncState.Conflict
        state.pendingSync > 0 -> SyncState.Pending
        else -> SyncState.Synced
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "سلام ${state.userName.ifBlank { "مدیر" }}",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        JalaliDate.today().formatLong(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SyncStatusChip(syncState, state.pendingSync)
                    IconButton(onClick = { viewModel.refresh() }, enabled = !refreshing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "همگام‌سازی")
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    title = "فروش امروز",
                    value = Toman(state.todaySales).format(),
                    icon = Icons.Outlined.Payments,
                    tint = SuccessGreen,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "فاکتورهای امروز",
                    value = state.invoiceCount.toString(),
                    icon = Icons.Outlined.ReceiptLong,
                    tint = InfoCyan,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    title = "سود ناخالص امروز",
                    value = Toman(state.grossProfit).format(),
                    icon = Icons.Outlined.TrendingUp,
                    tint = PakdelAmber.takeIf { state.grossProfit >= 0 } ?: DangerRed,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "ارزش انبار",
                    value = Toman(state.stockValue).format(),
                    icon = Icons.Outlined.Inventory2,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    title = "تعداد کالا",
                    value = state.productCount.toString(),
                    icon = Icons.Outlined.Category,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = "کالاهای رو به اتمام",
                    value = state.lowStock.size.toString(),
                    icon = Icons.Outlined.WarningAmber,
                    tint = if (state.lowStock.isEmpty()) SuccessGreen else DangerRed,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.conflictCount > 0) {
            item {
                com.pakdel.core.designsystem.component.SectionCard(
                    title = "نیازمند بررسی",
                ) {
                    Text(
                        "${state.conflictCount} عملیات به دلیل مغایرت موجودی یا خطای تجاری همگام نشده است." +
                            " به بخش تنظیمات مراجعه کنید.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (state.lowStock.isNotEmpty()) {
            item {
                Text(
                    "کالاهای رو به اتمام",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.lowStock.take(8), key = { it.id }) { product ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(product.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "موجودی: ${product.stockCached}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (product.stockCached <= 0) DangerRed else PakdelAmber,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

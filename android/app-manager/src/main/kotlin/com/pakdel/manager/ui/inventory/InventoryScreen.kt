package com.pakdel.manager.ui.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pakdel.core.common.date.JalaliDate
import com.pakdel.core.data.repository.StockMovementType
import com.pakdel.core.designsystem.component.EmptyState
import com.pakdel.core.designsystem.component.PakdelButton
import com.pakdel.core.designsystem.component.PakdelTextField
import com.pakdel.core.designsystem.component.SectionCard
import com.pakdel.core.designsystem.theme.DangerRed
import com.pakdel.core.designsystem.theme.SuccessGreen

@Composable
fun InventoryScreen(viewModel: InventoryViewModel = hiltViewModel()) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val movements by viewModel.movements.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val canAdjust by viewModel.canAdjust.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("کالاها") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("گردش انبار") })
            }

            if (tab == 0) {
                Column(Modifier.fillMaxSize()) {
                    if (!canAdjust) {
                        Text(
                            "شما فقط می‌توانید موجودی را ببینید؛ برای تغییر، مجوز انبار لازم است.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    PakdelTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        label = "جست‌وجوی کالا",
                        leadingIcon = Icons.Outlined.Search,
                        modifier = Modifier.padding(16.dp),
                    )

                    if (products.isEmpty()) {
                        EmptyState(
                            title = "کالایی برای مدیریت موجودی نیست",
                            description = "ابتدا از بخش محصولات کالا ثبت کنید.",
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(products.size) { index ->
                                val product = products[index]
                                SectionCard(
                                    modifier = Modifier.clickable(enabled = canAdjust) {
                                        viewModel.open(product, StockMovementType.Adjustment)
                                    },
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(product.name, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "موجودی: ${product.stockCached} ${product.unit}" +
                                                    if (product.isLowStock) "  •  رو به اتمام" else "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (product.isLowStock) DangerRed
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.open(product, StockMovementType.In) },
                                            enabled = canAdjust,
                                        ) {
                                            Icon(
                                                Icons.Outlined.Add,
                                                contentDescription = "ورود کالا",
                                                tint = SuccessGreen,
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.open(product, StockMovementType.Damage) },
                                            enabled = canAdjust,
                                        ) {
                                            Icon(
                                                Icons.Outlined.Remove,
                                                contentDescription = "ضایعات",
                                                tint = DangerRed,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (movements.isEmpty()) {
                    EmptyState(
                        title = "گردشی ثبت نشده",
                        description = "هر ورود، خروج یا اصلاح موجودی در این فهرست ثبت می‌شود.",
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(movements.size) { index ->
                            val movement = movements[index]
                            val label = StockMovementType.entries
                                .firstOrNull { it.code == movement.type }?.label
                                ?: when (movement.type) {
                                    "sale" -> "فروش"
                                    "return_sale" -> "مرجوعی فروش"
                                    "stocktake" -> "انبارگردانی"
                                    "transfer" -> "انتقال بین شعب"
                                    "purchase" -> "خرید"
                                    else -> movement.type
                                }
                            SectionCard {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            movement.productName,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            "$label • ${JalaliDate.fromEpochMillis(movement.createdAt).format()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = if (movement.qtyDelta > 0) "+${movement.qtyDelta}"
                                        else movement.qtyDelta.toString(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (movement.qtyDelta > 0) SuccessGreen else DangerRed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (dialog.isOpen) {
        StockDialog(
            state = dialog,
            onTypeChange = viewModel::onTypeChange,
            onQtyChange = viewModel::onQtyChange,
            onReasonChange = viewModel::onReasonChange,
            onSubmit = viewModel::submit,
            onClose = viewModel::close,
        )
    }
}

@Composable
private fun StockDialog(
    state: StockDialogState,
    onTypeChange: (StockMovementType) -> Unit,
    onQtyChange: (String) -> Unit,
    onReasonChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClose: () -> Unit,
) {
    val product = state.product ?: return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(product.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "موجودی فعلی: ${product.stockCached} ${product.unit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StockMovementType.entries.forEach { type ->
                        FilterChip(
                            selected = state.type == type,
                            onClick = { onTypeChange(type) },
                            label = { Text(type.label) },
                        )
                    }
                }

                PakdelTextField(
                    value = state.qty,
                    onValueChange = onQtyChange,
                    label = if (state.type == StockMovementType.Adjustment) {
                        "موجودی واقعی شمارش‌شده"
                    } else {
                        "تعداد"
                    },
                    keyboardType = KeyboardType.Number,
                )

                if (state.qty.isNotBlank()) {
                    Text(
                        "موجودی پس از ثبت: ${state.previewStock} " +
                            "(تغییر: ${if (state.delta > 0) "+" else ""}${state.delta})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                PakdelTextField(
                    value = state.reason,
                    onValueChange = onReasonChange,
                    label = "دلیل *",
                    placeholder = if (state.type == StockMovementType.Damage) {
                        "مثال: شکستگی هنگام جابه‌جایی"
                    } else {
                        "مثال: فاکتور خرید ۱۲۳"
                    },
                )

                if (state.error != null) {
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit) {
                Text(if (state.saving) "در حال ثبت..." else "ثبت")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("انصراف") }
        },
    )
}

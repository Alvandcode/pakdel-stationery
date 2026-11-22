package com.pakdel.manager.ui.invoices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pakdel.core.common.date.JalaliDate
import com.pakdel.core.common.money.PersianDigits
import com.pakdel.core.common.money.Toman
import com.pakdel.core.data.local.entity.SaleEntity
import com.pakdel.core.data.local.entity.SaleItemEntity
import com.pakdel.core.designsystem.component.EmptyState
import com.pakdel.core.designsystem.component.MoneyText
import com.pakdel.core.designsystem.component.PakdelTextField
import com.pakdel.core.designsystem.theme.DangerRed
import com.pakdel.core.designsystem.theme.SuccessGreen

private val statusLabels = mapOf(
    "completed" to "تکمیل‌شده",
    "canceled" to "لغوشده",
    "rejected" to "ردشده",
)

private val syncLabels = mapOf(
    "pending" to "در صف ارسال",
    "synced" to "همگام‌شده",
    "conflict" to "تعارض",
    "discarded" to "کنارگذاشته",
)

@Composable
fun InvoicesScreen(
    onBack: () -> Unit,
    viewModel: InvoicesViewModel = hiltViewModel(),
) {
    val sales by viewModel.sales.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val canCancel by viewModel.canCancel.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "بازگشت")
            }
            Text("فاکتورها", style = MaterialTheme.typography.titleLarge)
        }

        if (message != null || error != null) {
            Surface(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = error ?: message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (sales.isEmpty()) {
            EmptyState(
                title = "هنوز فاکتوری ثبت نشده است",
                description = "فاکتورهایی که در صندوق فروش ثبت می‌کنید اینجا با جزئیات نمایش داده می‌شوند.",
                icon = Icons.Outlined.ReceiptLong,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sales, key = { it.id }) { sale ->
                    InvoiceRow(sale = sale, onClick = { viewModel.open(sale.id) })
                }
            }
        }
    }

    val current = detail
    if (current != null) {
        InvoiceDetailDialog(
            detail = current,
            canCancel = canCancel && current.sale.status == "completed",
            busy = busy,
            onDismiss = {
                viewModel.close()
                viewModel.consumeMessage()
                viewModel.consumeError()
            },
            onCancel = { reason -> viewModel.cancel(current.sale.id, reason) },
        )
    }
}

@Composable
private fun InvoiceRow(sale: SaleEntity, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    PersianDigits.toPersian(sale.invoiceNumber),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    statusLabels[sale.status] ?: sale.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sale.status == "completed") SuccessGreen else DangerRed,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                JalaliDate.fromEpochMillis(sale.soldAt).format() + " • " +
                    (syncLabels[sale.syncState] ?: sale.syncState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    sale.customerName ?: "مشتری متفرقه",
                    style = MaterialTheme.typography.bodySmall,
                )
                MoneyText(Toman(sale.grandTotal), style = MaterialTheme.typography.titleSmall)
            }
            if (sale.dueTotal > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "نسیه: ${Toman(sale.dueTotal).formatWithUnit()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DangerRed,
                )
            }
        }
    }
}

@Composable
private fun InvoiceDetailDialog(
    detail: InvoiceDetail,
    canCancel: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCancel: (String) -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    var reason by rememberSaveable { mutableStateOf("") }
    val sale = detail.sale

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "فاکتور ${PersianDigits.toPersian(sale.invoiceNumber)}",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    JalaliDate.fromEpochMillis(sale.soldAt).formatLong(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "وضعیت: ${statusLabels[sale.status] ?: sale.status} • " +
                        (syncLabels[sale.syncState] ?: sale.syncState),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                detail.items.forEach { item -> InvoiceLineRow(item) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TotalRow("جمع", sale.subtotal)
                if (sale.discountTotal > 0) TotalRow("تخفیف", -sale.discountTotal)
                TotalRow("قابل پرداخت", sale.grandTotal, emphasise = true)
                TotalRow("پرداخت‌شده", sale.paidTotal)
                if (sale.dueTotal > 0) TotalRow("مانده (نسیه)", sale.dueTotal, danger = true)

                if (confirming) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "دلیل لغو (اختیاری)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    PakdelTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = "مثلاً اشتباه در ثبت",
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "با لغو فاکتور، کالاها به انبار برمی‌گردند و بدهی مشتری اصلاح می‌شود. " +
                            "فاکتور برای حسابرسی باقی می‌ماند.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (canCancel) {
                if (confirming) {
                    TextButton(
                        onClick = { onCancel(reason) },
                        enabled = !busy,
                    ) {
                        Text(
                            if (busy) "در حال لغو..." else "لغو فاکتور",
                            color = DangerRed,
                        )
                    }
                } else {
                    TextButton(onClick = { confirming = true }) {
                        Text("لغو فاکتور", color = DangerRed)
                    }
                }
            }
        },
        dismissButton = {
            if (confirming) {
                TextButton(onClick = { confirming = false }, enabled = !busy) { Text("بازگشت") }
            } else {
                TextButton(onClick = onDismiss) { Text("بستن") }
            }
        },
    )
}

@Composable
private fun InvoiceLineRow(item: SaleItemEntity) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "${item.nameSnapshot} × ${PersianDigits.toPersian(item.qty.toString())}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            Toman(item.lineTotal).format(),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun TotalRow(
    label: String,
    amount: Long,
    emphasise: Boolean = false,
    danger: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = if (emphasise) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodySmall,
        )
        Text(
            Toman(amount).formatWithUnit(),
            style = if (emphasise) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodySmall,
            color = if (danger) DangerRed else MaterialTheme.colorScheme.onSurface,
        )
    }
}

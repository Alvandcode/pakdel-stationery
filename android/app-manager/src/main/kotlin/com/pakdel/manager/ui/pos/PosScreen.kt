package com.pakdel.manager.ui.pos

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pakdel.core.common.money.Toman
import com.pakdel.core.data.local.entity.CustomerEntity
import com.pakdel.core.data.session.Session
import com.pakdel.core.designsystem.component.PakdelButton
import com.pakdel.core.designsystem.component.PakdelOutlinedButton
import com.pakdel.core.designsystem.component.PakdelTextField
import com.pakdel.core.designsystem.component.SectionCard
import com.pakdel.core.designsystem.theme.SuccessGreen

@Composable
fun PosScreen(
    session: Session,
    viewModel: PosViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val cart by viewModel.cart.collectAsStateWithLifecycle()
    val paidInput by viewModel.paidInput.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val selectedCustomer by viewModel.selectedCustomer.collectAsStateWithLifecycle()
    val customerForm by viewModel.customerForm.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("صندوق فروش", style = MaterialTheme.typography.titleLarge)
                Text(
                    session.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            PakdelTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = "افزودن کالا",
                placeholder = "نام یا بارکد را وارد کنید",
                leadingIcon = Icons.Outlined.Search,
            )

            if (results.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    results.forEach { product ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    viewModel.addProduct(product)
                                    viewModel.onQueryChange("")
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(product.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    Toman(product.salePrice).formatWithUnit(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SuccessGreen,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (cart.isEmpty) {
                    item {
                        Text(
                            "سبد خرید خالی است. کالا را جست‌وجو و اضافه کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(cart.lines, key = { it.productId }) { line ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(line.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${Toman(line.unitPrice).format()} × ${line.qty}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.decrease(line.productId) }) {
                            Icon(Icons.Outlined.Remove, contentDescription = "کاهش")
                        }
                        Text("${line.qty}", style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { viewModel.increase(line.productId) }) {
                            Icon(Icons.Outlined.Add, contentDescription = "افزایش")
                        }
                        Text(
                            Toman(line.lineTotal).format(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(onClick = { viewModel.remove(line.productId) }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "حذف",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            SectionCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("مشتری", style = MaterialTheme.typography.bodySmall)
                        Text(
                            selectedCustomer?.let {
                                it.fullName + if (it.balanceCached > 0) {
                                    " • بدهی: ${Toman(it.balanceCached).formatWithUnit()}"
                                } else ""
                            } ?: "مشتری انتخاب نشده",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (selectedCustomer != null) {
                        TextButton(onClick = viewModel::clearCustomer) { Text("حذف") }
                    }
                    TextButton(onClick = viewModel::openCustomerPicker) {
                        Text(if (selectedCustomer == null) "انتخاب" else "تغییر")
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("جمع کل", style = MaterialTheme.typography.bodyMedium)
                    Text(Toman(cart.subtotal).formatWithUnit())
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PakdelTextField(
                        value = if (cart.invoiceDiscount == 0L) "" else cart.invoiceDiscount.toString(),
                        onValueChange = viewModel::setInvoiceDiscount,
                        label = "تخفیف فاکتور",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("قابل پرداخت", style = MaterialTheme.typography.bodySmall)
                        Text(
                            Toman(cart.grandTotal).formatWithUnit(),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                PakdelTextField(
                    value = paidInput,
                    onValueChange = viewModel::setPaidInput,
                    label = "مبلغ دریافتی (خالی = نسیه)",
                    keyboardType = KeyboardType.Number,
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                PakdelButton(
                    text = "ثبت فروش",
                    onClick = viewModel::checkout,
                    enabled = !cart.isEmpty,
                    loading = submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                val paid = paidInput.toLongOrNull() ?: 0L
                val due = (cart.grandTotal - paid).coerceAtLeast(0L)
                if (due > 0 && !cart.isEmpty) {
                    Text(
                        if (selectedCustomer == null) {
                            "مانده بدهی: ${Toman(due).formatWithUnit()} — برای ثبت نسیه مشتری را انتخاب کنید"
                        } else {
                            "مانده بدهی: ${Toman(due).formatWithUnit()}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (customerForm.open) {
        CustomerPickerDialog(
            form = customerForm,
            customers = customers,
            onQueryChange = viewModel::onCustomerQueryChange,
            onNameChange = viewModel::onNewCustomerName,
            onPhoneChange = viewModel::onNewCustomerPhone,
            onCreate = viewModel::createCustomer,
            onSelect = viewModel::selectCustomer,
            onDismiss = viewModel::closeCustomerPicker,
        )
    }
}

@Composable
private fun CustomerPickerDialog(
    form: CustomerFormState,
    customers: List<CustomerEntity>,
    onQueryChange: (String) -> Unit,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onCreate: () -> Unit,
    onSelect: (CustomerEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val filtered = if (form.query.isBlank()) {
        customers.take(8)
    } else {
        customers.filter {
            it.fullName.contains(form.query, ignoreCase = true) ||
                (it.phone?.contains(form.query) == true)
        }.take(8)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("انتخاب مشتری") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PakdelTextField(
                    value = form.query,
                    onValueChange = onQueryChange,
                    label = "جست‌وجو",
                    placeholder = "نام یا شماره تلفن",
                    leadingIcon = Icons.Outlined.Search,
                )

                if (filtered.isEmpty()) {
                    Text(
                        "مشتری یافت نشد. می‌توانید مشتری جدید ثبت کنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    filtered.forEach { customer ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(customer) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(customer.fullName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    listOfNotNull(
                                        customer.phone,
                                        if (customer.balanceCached > 0) {
                                            "بدهی: ${Toman(customer.balanceCached).formatWithUnit()}"
                                        } else null,
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("مشتری جدید", style = MaterialTheme.typography.labelLarge)
                PakdelTextField(
                    value = form.name,
                    onValueChange = onNameChange,
                    label = "نام و نام خانوادگی *",
                )
                PakdelTextField(
                    value = form.phone,
                    onValueChange = onPhoneChange,
                    label = "شماره تلفن",
                    keyboardType = KeyboardType.Number,
                )
                if (form.error != null) {
                    Text(
                        form.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PakdelOutlinedButton(
                    text = "ثبت و انتخاب مشتری",
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن") }
        },
    )
}

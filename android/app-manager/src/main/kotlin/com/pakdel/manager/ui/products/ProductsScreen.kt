package com.pakdel.manager.ui.products

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pakdel.core.common.money.Toman
import com.pakdel.core.data.local.entity.CategoryEntity
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.designsystem.component.EmptyState
import com.pakdel.core.designsystem.component.PakdelButton
import com.pakdel.core.designsystem.component.PakdelOutlinedButton
import com.pakdel.core.designsystem.component.PakdelTextField
import com.pakdel.core.designsystem.component.SectionCard
import com.pakdel.core.designsystem.theme.DangerRed
import com.pakdel.core.designsystem.theme.SuccessGreen

@Composable
fun ProductsScreen(viewModel: ProductsViewModel = hiltViewModel()) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val publishing by viewModel.publishing.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val canManage by viewModel.canManage.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<ProductEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (canManage) {
                FloatingActionButton(onClick = viewModel::openNew) {
                    Icon(Icons.Outlined.Add, contentDescription = "افزودن محصول")
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PakdelTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    label = "جست‌وجوی کالا",
                    placeholder = "نام، بارکد یا کد کالا",
                    leadingIcon = Icons.Outlined.Search,
                    modifier = Modifier.weight(1f),
                )
                if (canManage) {
                    IconButton(
                        onClick = viewModel::publishAll,
                        enabled = !publishing,
                    ) {
                        Icon(
                            Icons.Outlined.CloudUpload,
                            contentDescription = "انتشار همه در فروشگاه آنلاین",
                        )
                    }
                }
                IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "دریافت از سرور")
                }
            }

            if (products.isEmpty()) {
                EmptyState(
                    title = if (query.isBlank()) "کالایی ثبت نشده" else "نتیجه‌ای یافت نشد",
                    description = if (query.isBlank()) {
                        "با دکمه + اولین محصول را ثبت کنید یا کاتالوگ را از سرور دریافت کنید."
                    } else {
                        "عبارت دیگری را جست‌وجو کنید."
                    },
                    actionLabel = if (query.isBlank() && canManage) "افزودن محصول" else null,
                    onAction = viewModel::openNew,
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(products.size) { index ->
                        val product = products[index]
                        SectionCard(
                            modifier = Modifier.clickable { viewModel.openEdit(product) },
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(product.name, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(4.dp))
                                    Row {
                                        Text(
                                            Toman(product.salePrice).formatWithUnit(),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (product.barcode != null) {
                                            Text(
                                                "  •  ${product.barcode}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = if (product.isLowStock) {
                                                DangerRed.copy(alpha = 0.12f)
                                            } else {
                                                SuccessGreen.copy(alpha = 0.12f)
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = "${product.stockCached} ${product.unit}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (product.isLowStock) DangerRed else SuccessGreen,
                                    )
                                }
                                IconButton(
                                    onClick = { pendingDelete = product },
                                    enabled = canManage,
                                ) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = "غیرفعال‌سازی",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (form.open) {
        ProductEditorDialog(
            form = form,
            categories = categories,
            onNameChange = viewModel::onNameChange,
            onBarcodeChange = viewModel::onBarcodeChange,
            onSkuChange = viewModel::onSkuChange,
            onBrandChange = viewModel::onBrandChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onUnitChange = viewModel::onUnitChange,
            onCategoryChange = viewModel::onCategoryChange,
            onPurchaseChange = viewModel::onPurchaseChange,
            onSaleChange = viewModel::onSaleChange,
            onWholesaleChange = viewModel::onWholesaleChange,
            onMinStockChange = viewModel::onMinStockChange,
            onInitialStockChange = viewModel::onInitialStockChange,
            onPublicChange = viewModel::onPublicChange,
            onSave = viewModel::save,
            onClose = viewModel::closeForm,
        )
    }

    pendingDelete?.let { product ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("غیرفعال‌سازی محصول") },
            text = {
                Text(
                    "«${product.name}» از فهرست کالاها و اپ مشتریان حذف می‌شود، " +
                        "اما سوابق فروش و گزارش‌های قبلی دست‌نخورده می‌مانند."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.archive(product)
                    pendingDelete = null
                }) { Text("غیرفعال کن") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("انصراف") }
            },
        )
    }
}

@Composable
private fun ProductEditorDialog(
    form: ProductFormState,
    categories: List<CategoryEntity>,
    onNameChange: (String) -> Unit,
    onBarcodeChange: (String) -> Unit,
    onSkuChange: (String) -> Unit,
    onBrandChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onCategoryChange: (String?) -> Unit,
    onPurchaseChange: (String) -> Unit,
    onSaleChange: (String) -> Unit,
    onWholesaleChange: (String) -> Unit,
    onMinStockChange: (String) -> Unit,
    onInitialStockChange: (String) -> Unit,
    onPublicChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (form.isNew) "ثبت محصول جدید" else "ویرایش محصول",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = onClose) { Text("بستن") }
                }
                HorizontalDivider()

                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PakdelTextField(
                        value = form.name,
                        onValueChange = onNameChange,
                        label = "نام محصول *",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PakdelTextField(
                            value = form.barcode,
                            onValueChange = onBarcodeChange,
                            label = "بارکد",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        PakdelTextField(
                            value = form.sku,
                            onValueChange = onSkuChange,
                            label = "کد کالا",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    PakdelTextField(
                        value = form.brand,
                        onValueChange = onBrandChange,
                        label = "برند",
                    )

                    Text("دسته‌بندی", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = form.categoryId == null,
                            onClick = { onCategoryChange(null) },
                            label = { Text("بدون دسته") },
                        )
                        categories.forEach { category ->
                            FilterChip(
                                selected = form.categoryId == category.id,
                                onClick = { onCategoryChange(category.id) },
                                label = { Text(category.name) },
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PakdelTextField(
                            value = form.purchasePrice,
                            onValueChange = onPurchaseChange,
                            label = "قیمت خرید",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        PakdelTextField(
                            value = form.salePrice,
                            onValueChange = onSaleChange,
                            label = "قیمت فروش *",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PakdelTextField(
                            value = form.wholesalePrice,
                            onValueChange = onWholesaleChange,
                            label = "قیمت عمده",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        PakdelTextField(
                            value = form.unit,
                            onValueChange = onUnitChange,
                            label = "واحد",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PakdelTextField(
                            value = form.minStock,
                            onValueChange = onMinStockChange,
                            label = "حداقل موجودی",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        if (form.isNew) {
                            PakdelTextField(
                                value = form.initialStock,
                                onValueChange = onInitialStockChange,
                                label = "موجودی اولیه",
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("نمایش در اپ مشتریان", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "فقط محصولات عمومی در فروشگاه آنلاین دیده می‌شوند.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = form.isPublic, onCheckedChange = onPublicChange)
                    }

                    PakdelTextField(
                        value = form.description,
                        onValueChange = onDescriptionChange,
                        label = "توضیحات",
                        singleLine = false,
                    )

                    if (form.error != null) {
                        Text(
                            form.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PakdelOutlinedButton(
                        text = "انصراف",
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                    )
                    PakdelButton(
                        text = if (form.isNew) "ثبت محصول" else "ذخیره تغییرات",
                        onClick = onSave,
                        loading = form.saving,
                        enabled = form.name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

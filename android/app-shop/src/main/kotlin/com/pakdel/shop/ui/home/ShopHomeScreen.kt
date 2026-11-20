package com.pakdel.shop.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pakdel.core.common.money.Toman
import com.pakdel.core.designsystem.component.EmptyState
import com.pakdel.core.designsystem.theme.ShopPrimary

@Composable
fun ShopHomeScreen(viewModel: ShopHomeViewModel = hiltViewModel()) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val selected by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "پاکدل استیشنری",
                    style = MaterialTheme.typography.headlineSmall,
                    color = ShopPrimary,
                )
                Text(
                    "لوازم‌التحریر و لوازم مدرسه",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = viewModel::refresh, enabled = !refreshing) {
                Icon(Icons.Outlined.Refresh, contentDescription = "به‌روزرسانی")
            }
        }

        if (categories.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    CategoryChip(
                        label = "همه",
                        selected = selected == null,
                        onClick = { viewModel.selectCategory(null) },
                    )
                }
                items(categories.size) { index ->
                    val category = categories[index]
                    CategoryChip(
                        label = category.name,
                        selected = selected == category.id,
                        onClick = { viewModel.selectCategory(category.id) },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            products.isEmpty() && error != null -> EmptyState(
                title = "دریافت محصولات ناموفق بود",
                description = error.orEmpty(),
                icon = Icons.Outlined.ShoppingBag,
                actionLabel = "تلاش مجدد",
                onAction = viewModel::refresh,
            )

            products.isEmpty() -> EmptyState(
                title = "به‌زودی محصولات اینجا نمایش داده می‌شوند",
                description = "کاتالوگ فروشگاه در حال آماده‌سازی است.",
                icon = Icons.Outlined.ShoppingBag,
                actionLabel = "به‌روزرسانی",
                onAction = viewModel::refresh,
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(products, key = { it.id }) { product ->
                    ProductCard(
                        name = product.name,
                        price = product.salePrice,
                        brand = product.brand,
                        inStock = product.stockCached > 0,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) ShopPrimary else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) androidx.compose.ui.graphics.Color.White
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ProductCard(
    name: String,
    price: Long,
    brand: String?,
    inStock: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.ShoppingBag,
                    contentDescription = null,
                    tint = ShopPrimary.copy(alpha = 0.4f),
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!brand.isNullOrBlank()) {
                Text(
                    brand,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                Toman(price).formatWithUnit(),
                style = MaterialTheme.typography.titleSmall,
                color = ShopPrimary,
            )
            Text(
                if (inStock) "موجود" else "ناموجود",
                style = MaterialTheme.typography.labelSmall,
                color = if (inStock) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

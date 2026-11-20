package com.pakdel.core.data.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.remote.CategoryDto
import com.pakdel.core.data.remote.ProductDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionDataStore by preferencesDataStore(name = "pakdel_session")

data class Session(
    val userId: String,
    val email: String,
    val storeId: String,
    val role: String,
    val displayName: String,
) {
    val isOwner: Boolean get() = role == "owner"
    val canSeeProfit: Boolean get() = role in setOf("owner", "manager")

    /** همان نقشه‌ای که در `role_permission_defaults` سرور seed شده است. */
    fun hasPermission(code: String): Boolean = code in permissionsOf(role)

    val canAdjustStock: Boolean get() = hasPermission("inventory.adjust")
    val canManageProducts: Boolean get() = hasPermission("product.manage")
    val canManageCustomers: Boolean get() = hasPermission("customer.manage")
    val canCreateSale: Boolean get() = hasPermission("sale.create")
    val canCancelSale: Boolean get() = hasPermission("sale.cancel")
    val canManageStaff: Boolean get() = hasPermission("staff.manage")

    companion object {
        private val saleCreate = setOf("sale.create")

        private val rolePermissions: Map<String, Set<String>> = mapOf(
            "owner" to setOf(
                "sale.create", "sale.cancel", "sale.price_override",
                "inventory.view", "inventory.adjust", "inventory.stocktake",
                "product.manage", "supplier.manage", "purchase.view", "purchase.manage",
                "customer.manage", "order.manage", "report.view", "report.profit",
                "finance.receive", "finance.pay", "finance.expense", "finance.settings",
                "staff.manage", "campaign.manage", "audit.view", "settings.finance",
            ),
            "manager" to setOf(
                "sale.create", "sale.cancel", "sale.price_override",
                "inventory.view", "inventory.adjust", "inventory.stocktake",
                "product.manage", "supplier.manage", "purchase.view", "purchase.manage",
                "customer.manage", "order.manage", "report.view", "report.profit",
                "finance.receive", "finance.pay", "finance.expense",
                "staff.manage", "campaign.manage", "audit.view",
            ),
            "sales" to setOf(
                "sale.create", "sale.price_override", "inventory.view",
                "customer.manage", "report.view",
            ),
            "cashier" to (saleCreate + "customer.manage"),
            "warehouse" to setOf(
                "inventory.view", "inventory.adjust", "inventory.stocktake",
                "product.manage", "supplier.manage", "purchase.view", "purchase.manage",
            ),
        )

        fun permissionsOf(role: String): Set<String> =
            rolePermissions[role] ?: rolePermissions.getValue("cashier")
    }
}

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val userId = stringPreferencesKey("user_id")
        val email = stringPreferencesKey("email")
        val storeId = stringPreferencesKey("store_id")
        val role = stringPreferencesKey("role")
        val displayName = stringPreferencesKey("display_name")
        val storeName = stringPreferencesKey("store_name")
    }

    val session: Flow<Session?> = context.sessionDataStore.data.map { prefs ->
        val userId = prefs[Keys.userId]
        val storeId = prefs[Keys.storeId]
        if (userId.isNullOrBlank() || storeId.isNullOrBlank()) {
            null
        } else {
            Session(
                userId = userId,
                email = prefs[Keys.email].orEmpty(),
                storeId = storeId,
                role = prefs[Keys.role] ?: "cashier",
                displayName = prefs[Keys.displayName].orEmpty(),
            )
        }
    }

    val storeName: Flow<String> = context.sessionDataStore.data.map { it[Keys.storeName].orEmpty() }

    suspend fun current(): Session? = session.first()

    suspend fun requireSession(): Session =
        current() ?: throw IllegalStateException("UNAUTHORIZED: نشست کاربر یافت نشد.")

    suspend fun save(session: Session, storeName: String = "") {
        context.sessionDataStore.edit { prefs ->
            prefs[Keys.userId] = session.userId
            prefs[Keys.email] = session.email
            prefs[Keys.storeId] = session.storeId
            prefs[Keys.role] = session.role
            prefs[Keys.displayName] = session.displayName
            prefs[Keys.storeName] = storeName
        }
    }

    suspend fun updateDisplayName(name: String) {
        context.sessionDataStore.edit { it[Keys.displayName] = name }
    }

    suspend fun clear() {
        context.sessionDataStore.edit { it.clear() }
    }
}

// ---------- Mapperها ----------

/**
 * تحلیل زمان PostgREST.
 *
 * خروجی Supabase معمولاً `+00:00` دارد (نه `Z`)؛ `Instant.parse` آن را رد می‌کند و
 * نتیجه‌اش این بود که تاریخ فروش‌های دریافت‌شده به «امروز» تبدیل می‌شد.
 */
internal fun parseTimestampOrNull(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val text = value.trim()
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(text.replace(' ', 'T'))
                .atZone(ZoneId.of("Asia/Tehran"))
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
}

fun ProductDto.toProductEntity(): ProductEntity = ProductEntity(
    id = id,
    storeId = storeId.orEmpty(),
    categoryId = categoryId,
    name = name,
    sku = sku,
    barcode = barcode,
    description = description,
    brand = brand,
    unit = unit ?: "عدد",
    purchasePrice = purchasePrice,
    salePrice = salePrice,
    wholesalePrice = wholesalePrice,
    stockCached = stockCached,
    minStock = minStock,
    imagePath = imagePath,
    isPublic = isPublic,
    isActive = isActive,
    deletedAt = parseTimestampOrNull(deletedAt),
    updatedAt = parseTimestampOrNull(updatedAt) ?: System.currentTimeMillis(),
)

fun CategoryDto.toCategoryEntity(): com.pakdel.core.data.local.entity.CategoryEntity =
    com.pakdel.core.data.local.entity.CategoryEntity(
        id = id,
        parentId = parentId,
        name = name,
        slug = slug,
        color = color,
        sortOrder = sortOrder,
        isActive = isActive,
        updatedAt = parseTimestampOrNull(updatedAt) ?: System.currentTimeMillis(),
    )

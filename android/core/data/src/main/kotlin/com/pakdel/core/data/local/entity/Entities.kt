package com.pakdel.core.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * موجودیت‌های محلی (Room).
 *
 * قواعد:
 * - کلید اصلی رشته UUID است که در دستگاه تولید می‌شود (سازگاری کامل با ثبت آفلاین).
 * - تاریخ‌ها به‌صورت epoch millis ذخیره می‌شوند.
 * - مبالغ Long و بر حسب تومان هستند.
 * - حذف فیزیکی نداریم؛ isActive/deletedAt استفاده می‌شود.
 */

@Entity(tableName = "categories", indices = [Index("parentId")])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val parentId: String? = null,
    val name: String,
    val slug: String? = null,
    val color: String? = null,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["barcode"]),
        Index(value = ["sku"]),
        Index(value = ["categoryId"]),
        Index(value = ["name"]),
    ],
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val categoryId: String? = null,
    val name: String,
    val sku: String? = null,
    val barcode: String? = null,
    val description: String? = null,
    val brand: String? = null,
    val unit: String = "عدد",
    val purchasePrice: Long = 0L,
    val salePrice: Long = 0L,
    val wholesalePrice: Long = 0L,
    val stockCached: Int = 0,
    val minStock: Int = 0,
    val imagePath: String? = null,
    val isPublic: Boolean = false,
    val isActive: Boolean = true,
    val deletedAt: Long? = null,
    val updatedAt: Long = 0L,
) {
    val isLowStock: Boolean get() = stockCached <= minStock
    val profitMargin: Long get() = salePrice - purchasePrice
}

@Entity(
    tableName = "customers",
    indices = [Index(value = ["phone"]), Index(value = ["userId"])],
)
data class CustomerEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val userId: String? = null,
    val fullName: String,
    val phone: String? = null,
    val groupCode: String = "regular",
    val discountPercent: Double = 0.0,
    val creditLimit: Long = 0L,
    val balanceCached: Long = 0L,
    val isActive: Boolean = true,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "sales",
    indices = [
        Index(value = ["soldAt"]),
        Index(value = ["customerId"]),
        Index(value = ["status"]),
    ],
)
data class SaleEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val customerId: String? = null,
    val customerName: String? = null,
    val invoiceNumber: String,
    val subtotal: Long,
    val discountTotal: Long,
    val grandTotal: Long,
    val paidTotal: Long,
    val dueTotal: Long,
    val paymentMethod: String,
    val status: String = "completed",
    val note: String? = null,
    val soldAt: Long,
    /** pending | synced | conflict */
    val syncState: String = "pending",
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["id"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("saleId"), Index("productId")],
)
data class SaleItemEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val productId: String,
    val nameSnapshot: String,
    val qty: Int,
    val unitPrice: Long,
    val unitCost: Long,
    val discount: Long,
    val lineTotal: Long,
)

@Entity(
    tableName = "inventory_tx",
    indices = [Index("productId"), Index("createdAt")],
)
data class InventoryTxEntity(
    @PrimaryKey val id: String,
    val storeId: String,
    val productId: String,
    val variantId: String? = null,
    val qtyDelta: Int,
    val type: String,
    val referenceType: String? = null,
    val referenceId: String? = null,
    val reason: String? = null,
    val createdAt: Long,
    val syncState: String = "pending",
)

/**
 * صف عملیات در انتظار ارسال به سرور.
 * payload همان بدنه‌ی JSON توابع RPC است و بدون تغییر ارسال می‌شود.
 */
@Entity(tableName = "outbox", indices = [Index("status"), Index("id", unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0L,
    val id: String,
    /** شناسه رکورد دامنه (فاکتور، محصول، ...) برای تعارض‌یابی و محافظت از داده محلی */
    val entityId: String? = null,
    val rpc: String,
    val payload: String,
    /** pending | conflict | failed */
    val status: String = "pending",
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

package com.pakdel.core.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** تنظیمات اتصال؛ مقادیر از `local.properties` اپ می‌آید و هرگز در کد سخت‌کد نمی‌شود. */
data class SupabaseConfig(
    val url: String,
    val anonKey: String,
) {
    val isConfigured: Boolean
        get() = url.startsWith("http") && anonKey.isNotBlank()
}

val PakdelJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable
data class ProductDto(
    val id: String,
    @SerialName("store_id") val storeId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    val name: String,
    val sku: String? = null,
    val barcode: String? = null,
    val description: String? = null,
    val brand: String? = null,
    val unit: String? = null,
    @SerialName("purchase_price") val purchasePrice: Long = 0L,
    @SerialName("sale_price") val salePrice: Long = 0L,
    @SerialName("wholesale_price") val wholesalePrice: Long = 0L,
    @SerialName("stock_cached") val stockCached: Int = 0,
    @SerialName("min_stock") val minStock: Int = 0,
    @SerialName("image_path") val imagePath: String? = null,
    @SerialName("is_public") val isPublic: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class CategoryDto(
    val id: String,
    @SerialName("store_id") val storeId: String? = null,
    val name: String,
    val slug: String? = null,
    val color: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class CustomerDto(
    val id: String,
    @SerialName("store_id") val storeId: String,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("full_name") val fullName: String,
    val phone: String? = null,
    @SerialName("group_code") val groupCode: String = "regular",
    @SerialName("discount_percent") val discountPercent: Double = 0.0,
    @SerialName("credit_limit") val creditLimit: Long = 0L,
    @SerialName("balance_cached") val balanceCached: Long = 0L,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("deleted_at") val deletedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class SaleItemDto(
    val id: String,
    @SerialName("sale_id") val saleId: String,
    @SerialName("product_id") val productId: String? = null,
    @SerialName("name_snapshot") val nameSnapshot: String,
    val qty: Int = 1,
    @SerialName("unit_price") val unitPrice: Long = 0L,
    @SerialName("unit_cost") val unitCost: Long = 0L,
    val discount: Long = 0L,
    @SerialName("line_total") val lineTotal: Long = 0L,
)

/** فاکتور سرور همراه با اقلام تو‌در‌تو (PostgREST embed) */
@Serializable
data class SaleDto(
    val id: String,
    @SerialName("store_id") val storeId: String,
    @SerialName("customer_id") val customerId: String? = null,
    @SerialName("invoice_number") val invoiceNumber: String,
    val subtotal: Long = 0L,
    @SerialName("discount_total") val discountTotal: Long = 0L,
    @SerialName("grand_total") val grandTotal: Long = 0L,
    @SerialName("paid_total") val paidTotal: Long = 0L,
    @SerialName("due_total") val dueTotal: Long = 0L,
    @SerialName("payment_method") val paymentMethod: String = "cash",
    val status: String = "completed",
    val note: String? = null,
    @SerialName("sold_at") val soldAt: String? = null,
    @SerialName("sale_items") val items: List<SaleItemDto> = emptyList(),
)

/** گردش انبار از سرور */
@Serializable
data class InventoryTxDto(
    val id: String,
    @SerialName("store_id") val storeId: String = "",
    @SerialName("product_id") val productId: String? = null,
    @SerialName("qty_delta") val qtyDelta: Int = 0,
    val type: String = "adjustment",
    @SerialName("reference_type") val referenceType: String? = null,
    @SerialName("reference_id") val referenceId: String? = null,
    val reason: String? = null,
    @SerialName("client_operation_id") val clientOperationId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** پاسخ `rpc_my_context` */
@Serializable
data class MyContextDto(
    @SerialName("user_id") val userId: String,
    @SerialName("store_id") val storeId: String,
    val role: String = "cashier",
    @SerialName("store_name") val storeName: String = "",
    @SerialName("display_name") val displayName: String = "",
    val email: String = "",
)

@Serializable
data class MemberDto(
    val id: String,
    @SerialName("user_id") val userId: String = "",
    @SerialName("full_name") val fullName: String = "",
    val email: String = "",
    val role: String = "cashier",
    @SerialName("role_title") val roleTitle: String = "",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("is_self") val isSelf: Boolean = false,
)

@Serializable
data class UpsertCustomerResponseDto(
    @SerialName("customer_id") val customerId: String,
    val balance: Long = 0L,
    val duplicate: Boolean = false,
)

/** پاسخ توابع RPC */
@Serializable
data class CheckoutResponseDto(
    @SerialName("sale_id") val saleId: String,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("grand_total") val grandTotal: Long = 0L,
    @SerialName("due_total") val dueTotal: Long = 0L,
    val duplicate: Boolean = false,
)

@Serializable
data class DashboardSummaryDto(
    @SerialName("sales_total") val salesTotal: Long = 0L,
    @SerialName("invoice_count") val invoiceCount: Int = 0,
    @SerialName("cogs_total") val cogsTotal: Long = 0L,
    @SerialName("gross_profit") val grossProfit: Long = 0L,
    @SerialName("expenses_total") val expensesTotal: Long = 0L,
    @SerialName("net_profit_estimate") val netProfitEstimate: Long = 0L,
    @SerialName("stock_value") val stockValue: Long = 0L,
    @SerialName("low_stock_count") val lowStockCount: Int = 0,
    @SerialName("customer_debt") val customerDebt: Long = 0L,
    @SerialName("supplier_debt") val supplierDebt: Long = 0L,
    @SerialName("new_orders") val newOrders: Int = 0,
)

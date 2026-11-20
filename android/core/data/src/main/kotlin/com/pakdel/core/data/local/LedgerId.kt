package com.pakdel.core.data.local

/**
 * شناسه یکتای گردش انبار در کش محلی.
 *
 * سرور برای گردش‌های خودکار (فروش، لغو، موجودی اولیه) شناسه تصادفی می‌سازد؛
 * بنابراین برای اینکه همگام‌سازی همان ردیف محلی را «جایگزین» کند و ردیف
 * تکراری نسازد، از یک شناسه قطعی بر اساس مرجع تغییر استفاده می‌کنیم.
 *
 * اولویت: مرجع (نوع+شناسه) ← شناسه عملیات کلاینت ← شناسه سرور
 *
 * محدودیت شناخته‌شده: اگر در یک فاکتور یک کالا در دو ردیف با تعداد یکسان
 * ثبت شود، یک ردیف گردش به‌جای دو ردیف نمایش داده می‌شود (فقط نمایشی؛
 * موجودی و مبالغ از سرور/محصولات می‌آید).
 */
object LedgerId {

    fun of(
        serverId: String? = null,
        referenceType: String? = null,
        referenceId: String? = null,
        productId: String,
        type: String,
        qtyDelta: Int,
        clientOperationId: String? = null,
    ): String = when {
        !referenceType.isNullOrBlank() && !referenceId.isNullOrBlank() ->
            "inv:$referenceType:$referenceId:$productId:$type:$qtyDelta"

        !clientOperationId.isNullOrBlank() -> clientOperationId

        !serverId.isNullOrBlank() -> serverId

        else -> "inv:$productId:$type:$qtyDelta"
    }

    /** گردش ناشی از فروش/لغو/برگشت یک فاکتور. */
    fun forSale(saleId: String, productId: String, type: String, qtyDelta: Int): String =
        of(
            referenceType = "sale",
            referenceId = saleId,
            productId = productId,
            type = type,
            qtyDelta = qtyDelta,
        )

    /** موجودی اولیه یک کالا. */
    fun forProductStock(productId: String, qtyDelta: Int): String =
        of(
            referenceType = "product",
            referenceId = productId,
            productId = productId,
            type = "adjustment",
            qtyDelta = qtyDelta,
        )
}

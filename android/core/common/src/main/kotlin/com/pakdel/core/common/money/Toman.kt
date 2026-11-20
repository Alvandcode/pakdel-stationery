package com.pakdel.core.common.money

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * مبلغ مالی بر حسب تومان.
 *
 * قاعده پروژه: هیچ‌گاه از Double/Float برای پول استفاده نمی‌شود.
 * تمام محاسبات با Long انجام می‌شود تا خطای اعشار رخ ندهد.
 */
@JvmInline
value class Toman(val amount: Long) : Comparable<Toman> {

    operator fun plus(other: Toman) = Toman(amount + other.amount)
    operator fun minus(other: Toman) = Toman(amount - other.amount)
    operator fun times(quantity: Int) = Toman(amount * quantity)
    operator fun unaryMinus() = Toman(-amount)

    fun isZero(): Boolean = amount == 0L
    fun isPositive(): Boolean = amount > 0L

    /** تبدیل به ریال — فقط برای نمایش یا درگاه پرداخت */
    fun toRial(): Long = amount * 10L

    /** قالب‌بندی با جداکننده هزارگان و ارقام فارسی: «۱٬۲۵۰٬۰۰۰ تومان» */
    fun formatWithUnit(): String = "${format()} تومان"

    /** قالب‌بندی عدد با ارقام فارسی */
    fun format(): String = format(usePersianDigits = true)

    fun format(usePersianDigits: Boolean): String {
        val symbols = DecimalFormatSymbols(Locale.US).apply {
            groupingSeparator = '٬'
        }
        val formatter = DecimalFormat("#,##0", symbols)
        val text = formatter.format(amount)
        return if (usePersianDigits) PersianDigits.toPersian(text) else text
    }

    override fun compareTo(other: Toman): Int = amount.compareTo(other.amount)

    companion object {
        val ZERO = Toman(0L)
        fun of(amount: Long): Toman = Toman(amount)

        /** درصد را با گرد کردن نیم‌به‌بالا محاسبه می‌کند (هم‌راستا با round در PostgreSQL) */
        fun percentOf(base: Toman, percent: Int): Toman {
            if (percent <= 0) return ZERO
            val exact = base.amount * percent
            return Toman((exact + 50) / 100)
        }
    }
}

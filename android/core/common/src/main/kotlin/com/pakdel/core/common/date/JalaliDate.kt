package com.pakdel.core.common.date

import com.pakdel.core.common.money.PersianDigits
import java.util.Calendar

/**
 * تاریخ شمسی (جلالی).
 *
 * الگوریتم تبدیل بر پایه‌ی محاسبه‌ی روز مطلق است (هم‌ارز JDF).
 * ساعت و دقیقه از تقویم میلادی گرفته می‌شود؛ فقط نمایش تاریخ شمسی است.
 */
data class JalaliDate(
    val year: Int,
    val month: Int,
    val day: Int,
) : Comparable<JalaliDate> {

    val monthName: String
        get() = MONTH_NAMES.getOrElse(month - 1) { "" }

    /** ۱۴۰۳/۰۵/۲۱ */
    fun format(usePersianDigits: Boolean = true): String {
        val text = "${year.toString().padStart(4, '0')}/" +
            "${month.toString().padStart(2, '0')}/" +
            day.toString().padStart(2, '0')
        return if (usePersianDigits) PersianDigits.toPersian(text) else text
    }

    /** ۲۱ مرداد ۱۴۰۳ */
    fun formatLong(usePersianDigits: Boolean = true): String {
        val text = "$day $monthName $year"
        return if (usePersianDigits) PersianDigits.toPersian(text) else text
    }

    override fun compareTo(other: JalaliDate): Int = when {
        year != other.year -> year.compareTo(other.year)
        month != other.month -> month.compareTo(other.month)
        else -> day.compareTo(other.day)
    }

    companion object {
        private val MONTH_NAMES = arrayOf(
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
        )

        private val GREGORIAN_DAYS_IN_MONTH = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        private val DAYS_BEFORE_GREGORIAN_MONTH = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

        fun today(): JalaliDate {
            val cal = Calendar.getInstance()
            return fromGregorian(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
        }

        fun fromEpochMillis(millis: Long): JalaliDate {
            val cal = Calendar.getInstance().apply { timeInMillis = millis }
            return fromGregorian(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
            )
        }

        @Suppress("MagicNumber")
        fun fromGregorian(gy: Int, gm: Int, gd: Int): JalaliDate {
            require(gm in 1..12 && gd in 1..31) { "تاریخ میلادی نامعتبر: $gy/$gm/$gd" }
            val gy2 = if (gm > 2) gy + 1 else gy
            var days = 355666L + (365L * gy) +
                ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) +
                gd + DAYS_BEFORE_GREGORIAN_MONTH[gm - 1]

            var jy = -1595 + (33 * (days / 12053))
            days %= 12053
            jy += 4 * (days / 1461)
            days %= 1461
            if (days > 365) {
                jy += (days - 1) / 365
                days = (days - 1) % 365
            }
            val jm: Int
            val jd: Int
            if (days < 186) {
                jm = 1 + (days / 31).toInt()
                jd = 1 + (days % 31).toInt()
            } else {
                jm = 7 + ((days - 186) / 30).toInt()
                jd = 1 + ((days - 186) % 30).toInt()
            }
            return JalaliDate(jy.toInt(), jm, jd)
        }

        /** تعداد روزهای ماه شمسی (اسفند در سال کبیسه ۳۰ روز) */
        fun daysInMonth(year: Int, month: Int): Int {
            if (month <= 6) return 31
            if (month <= 11) return 30
            return if (isLeapYear(year)) 30 else 29
        }

        fun isLeapYear(year: Int): Boolean {
            // چرخه ۳۳ ساله: سال‌های کبیسه وقتی باقی‌مانده تقسیم سال بر ۳۳
            // در این مجموعه باشد (مثال: ۱۳۹۹، ۱۴۰۳، ۱۴۰۸).
            val mod = ((year % 33) + 33) % 33
            return mod in intArrayOf(1, 5, 9, 13, 17, 22, 26, 30)
        }
    }
}

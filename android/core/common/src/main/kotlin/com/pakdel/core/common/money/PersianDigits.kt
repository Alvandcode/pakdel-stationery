package com.pakdel.core.common.money

/** تبدیل ارقام لاتین و فارسی/عربی به یکدیگر */
object PersianDigits {

    private const val PERSIAN_ZERO = '\u06F0'
    private const val ARABIC_ZERO = '\u0660'

    fun toPersian(input: String): String = buildString(input.length) {
        for (ch in input) {
            append(
                when (ch) {
                    in '0'..'9' -> (PERSIAN_ZERO + (ch - '0'))
                    else -> ch
                }
            )
        }
    }

    fun toLatin(input: String): String = buildString(input.length) {
        for (ch in input) {
            append(
                when (ch) {
                    in '\u06F0'..'\u06F9' -> ('0' + (ch - PERSIAN_ZERO))
                    in '\u0660'..'\u0669' -> ('0' + (ch - ARABIC_ZERO))
                    else -> ch
                }
            )
        }
    }

    /** نرمال‌سازی ورودی کاربر: ارقام لاتین + یکسان‌سازی ی/ک عربی */
    fun normalizeInput(input: String): String =
        toLatin(input)
            .replace('\u064A', '\u06CC') // ي -> ی
            .replace('\u0643', '\u06A9') // ك -> ک
            .trim()
}

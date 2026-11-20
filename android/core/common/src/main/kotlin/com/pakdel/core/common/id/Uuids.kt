package com.pakdel.core.common.id

import java.util.UUID

/**
 * شناسه‌های سمت دستگاه.
 * چون فروش آفلاین است، شناسه رکوردها باید قبل از اتصال ساخته شود؛
 * به همین دلیل سرور از UUID تولیدشده در کلاینت به‌عنوان کلید اصلی استفاده می‌کند.
 */
object Uuids {
    fun newId(): String = UUID.randomUUID().toString()

    fun newIdOrNull(value: String?): String? =
        value?.takeIf { it.isNotBlank() } ?: null

    fun isValid(value: String?): Boolean =
        value != null && runCatching { UUID.fromString(value) }.isSuccess
}

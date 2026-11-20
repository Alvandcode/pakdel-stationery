package com.pakdel.core.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * فهرست مهاجرت‌های پایگاه‌داده.
 *
 * هر بار نسخه `PakdelDatabase.version` افزایش یافت، مهاجرت متناظر را اینجا اضافه کنید.
 * استفاده از `fallbackToDestructiveMigration` ممنوع است: داده فروشگاه نباید از دست برود.
 */
object DatabaseMigrations {

    /** v2: افزودن شناسه موجودیت به Outbox برای تعارض‌یابی و محافظت از ویرایش‌های آفلاین */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE outbox ADD COLUMN entityId TEXT")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}

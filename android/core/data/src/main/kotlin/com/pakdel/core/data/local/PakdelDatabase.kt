package com.pakdel.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.pakdel.core.data.local.dao.CategoryDao
import com.pakdel.core.data.local.dao.CustomerDao
import com.pakdel.core.data.local.dao.InventoryDao
import com.pakdel.core.data.local.dao.OutboxDao
import com.pakdel.core.data.local.dao.ProductDao
import com.pakdel.core.data.local.dao.SaleDao
import com.pakdel.core.data.local.dao.SyncFlagDao
import com.pakdel.core.data.local.dao.SyncMetaDao
import com.pakdel.core.data.local.entity.CategoryEntity
import com.pakdel.core.data.local.entity.CustomerEntity
import com.pakdel.core.data.local.entity.InventoryTxEntity
import com.pakdel.core.data.local.entity.OutboxEntity
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.local.entity.SaleEntity
import com.pakdel.core.data.local.entity.SaleItemEntity
import com.pakdel.core.data.local.entity.SyncMetaEntity

/**
 * پایگاه‌داده محلی.
 *
 * سیاست مهاجرت:
 * - هیچ‌گاه `fallbackToDestructiveMigration` استفاده نمی‌شود؛ داده فروشگاه نباید از دست برود.
 * - برای هر افزایش نسخه، یک Migration در `DatabaseMigrations.kt` اضافه کنید.
 */
@Database(
    entities = [
        ProductEntity::class,
        CategoryEntity::class,
        CustomerEntity::class,
        SaleEntity::class,
        SaleItemEntity::class,
        InventoryTxEntity::class,
        OutboxEntity::class,
        SyncMetaEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class PakdelDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun categoryDao(): CategoryDao
    abstract fun customerDao(): CustomerDao
    abstract fun saleDao(): SaleDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncMetaDao(): SyncMetaDao
    abstract fun syncFlagDao(): SyncFlagDao

    companion object {
        const val NAME = "pakdel.db"
    }
}

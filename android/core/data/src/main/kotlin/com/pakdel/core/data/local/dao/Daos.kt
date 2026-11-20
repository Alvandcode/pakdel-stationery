package com.pakdel.core.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.pakdel.core.data.local.entity.CategoryEntity
import com.pakdel.core.data.local.entity.CustomerEntity
import com.pakdel.core.data.local.entity.InventoryTxEntity
import com.pakdel.core.data.local.entity.OutboxEntity
import com.pakdel.core.data.local.entity.ProductEntity
import com.pakdel.core.data.local.entity.SaleEntity
import com.pakdel.core.data.local.entity.SaleItemEntity
import com.pakdel.core.data.local.entity.SyncMetaEntity
import kotlinx.coroutines.flow.Flow

// نکته: Room از نام خود پراپرتی‌ها به‌عنوان نام ستون استفاده می‌کند
// (camelCase). تمام کوئری‌ها با همان نام نوشته شده‌اند.

@Dao
interface ProductDao {

    @Query("SELECT * FROM products WHERE deletedAt IS NULL AND isActive = 1 ORDER BY name ASC")
    fun observeActive(): Flow<List<ProductEntity>>

    @Query(
        """
        SELECT * FROM products
        WHERE isActive = 1
          AND (name LIKE '%' || :query || '%' OR barcode = :query OR sku = :query)
        ORDER BY name ASC
        LIMIT 60
        """
    )
    fun search(query: String): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun byId(id: String): ProductEntity?

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun byBarcode(barcode: String): ProductEntity?

    /** برای جلوگیری از تعارض با کالاهای حذف‌شده؛ سرور اجازه استفاده مجدد از بارکد را می‌دهد. */
    @Query("SELECT * FROM products WHERE barcode = :barcode AND deletedAt IS NULL LIMIT 1")
    suspend fun byActiveBarcode(barcode: String): ProductEntity?

    @Query("DELETE FROM products WHERE storeId <> :storeId")
    suspend fun deleteOutsideStore(storeId: String)

    @Query("UPDATE products SET isActive = 0, deletedAt = :now WHERE id = :id")
    suspend fun deactivateById(id: String, now: Long)

    @Query("SELECT * FROM products WHERE stockCached <= minStock AND isActive = 1 ORDER BY stockCached ASC")
    fun observeLowStock(): Flow<List<ProductEntity>>

    @Upsert
    suspend fun upsert(product: ProductEntity)

    @Upsert
    suspend fun upsertAll(products: List<ProductEntity>)

    @Query("UPDATE products SET stockCached = stockCached - :qty, updatedAt = :now WHERE id = :id AND stockCached >= :qty")
    suspend fun decreaseStock(id: String, qty: Int, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE products SET stockCached = stockCached + :qty, updatedAt = :now WHERE id = :id")
    suspend fun increaseStock(id: String, qty: Int, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(stockCached * purchasePrice), 0) FROM products WHERE isActive = 1")
    suspend fun stockValue(): Long
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY sortOrder ASC")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Upsert
    suspend fun upsertAll(categories: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id NOT IN (:keepIds)")
    suspend fun deleteNotIn(keepIds: List<String>)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface CustomerDao {

    @Query("SELECT * FROM customers WHERE isActive = 1 ORDER BY fullName ASC")
    fun observeAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE id = :id")
    suspend fun byId(id: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE storeId = :storeId AND phone = :phone AND isActive = 1 LIMIT 1")
    suspend fun byPhone(storeId: String, phone: String): CustomerEntity?

    @Query(
        """
        SELECT * FROM customers
        WHERE isActive = 1 AND (fullName LIKE '%' || :query || '%' OR phone LIKE '%' || :query || '%')
        ORDER BY fullName ASC LIMIT 40
        """
    )
    fun search(query: String): Flow<List<CustomerEntity>>

    @Upsert
    suspend fun upsert(customer: CustomerEntity)

    @Upsert
    suspend fun upsertAll(customers: List<CustomerEntity>)

    @Query("SELECT COUNT(*) FROM customers")
    suspend fun count(): Int
}

@Dao
interface SaleDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSale(sale: SaleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<SaleItemEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInventory(txs: List<InventoryTxEntity>)

    @Query("SELECT * FROM sales ORDER BY soldAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun byId(id: String): SaleEntity?

    @Query("SELECT * FROM sale_items WHERE saleId = :saleId")
    suspend fun itemsOf(saleId: String): List<SaleItemEntity>

    @Query("UPDATE sales SET syncState = :state WHERE id = :id")
    suspend fun updateSyncState(id: String, state: String)

    @Query("UPDATE sales SET invoiceNumber = :number, syncState = 'synced' WHERE id = :id")
    suspend fun markSynced(id: String, number: String)

    @Query("UPDATE sales SET status = 'canceled' WHERE id = :id")
    suspend fun markCanceledLocal(id: String)

    @Query("UPDATE sales SET status = 'canceled', syncState = 'synced' WHERE id = :id")
    suspend fun markCanceledSynced(id: String)

    /** فروشی که سرور آن را نپذیرفت: از گزارش‌های مالی خارج می‌شود ولی حذف نمی‌شود. */
    @Query("UPDATE sales SET status = 'rejected', syncState = 'discarded' WHERE id = :id")
    suspend fun markRejected(id: String)

    @Upsert
    suspend fun upsertSales(sales: List<SaleEntity>)

    @Query("DELETE FROM sale_items WHERE saleId = :saleId")
    suspend fun deleteItems(saleId: String)

    @Query("SELECT id FROM sales WHERE syncState IN ('pending','conflict')")
    suspend fun blockedSaleIds(): List<String>

    @Query(
        """
        SELECT COALESCE(SUM(grandTotal), 0) FROM sales
        WHERE status = 'completed' AND soldAt >= :from AND soldAt < :to
        """
    )
    fun observeSalesTotal(from: Long, to: Long): Flow<Long>

    @Query(
        """
        SELECT COUNT(*) FROM sales
        WHERE status = 'completed' AND soldAt >= :from AND soldAt < :to
        """
    )
    fun observeInvoiceCount(from: Long, to: Long): Flow<Int>

    @Query(
        """
        SELECT COALESCE(SUM(si.unitCost * si.qty), 0) FROM sale_items si
        JOIN sales s ON s.id = si.saleId
        WHERE s.status = 'completed' AND s.soldAt >= :from AND s.soldAt < :to
        """
    )
    fun observeCogs(from: Long, to: Long): Flow<Long>
}

@Dao
interface OutboxDao {

    @Insert
    suspend fun enqueue(item: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE status = 'pending' ORDER BY seq ASC LIMIT :limit")
    suspend fun pending(limit: Int = 25): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'pending'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'conflict'")
    fun observeConflictCount(): Flow<Int>

    @Query("SELECT * FROM outbox WHERE status = 'conflict' ORDER BY seq DESC")
    fun observeConflicts(): Flow<List<OutboxEntity>>

    @Query("UPDATE outbox SET status = :status, attempts = attempts + 1, lastError = :error WHERE seq = :seq")
    suspend fun mark(seq: Long, status: String, error: String?)

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun remove(seq: Long)

    @Query("SELECT * FROM outbox WHERE seq = :seq")
    suspend fun bySeq(seq: Long): OutboxEntity?

    /** شناسه رکوردهایی که یا در صف‌اند یا تعارض دارند؛ نسخه سرور نباید آن‌ها را بازنویسی کند. */
    @Query("SELECT entityId FROM outbox WHERE status IN ('pending','conflict') AND entityId IS NOT NULL")
    suspend fun blockedEntityIds(): List<String>

    @Query("SELECT * FROM outbox WHERE status IN ('pending','conflict') ORDER BY seq ASC")
    suspend fun blocked(): List<OutboxEntity>

    @Query("UPDATE outbox SET status = 'pending', attempts = 0, lastError = NULL WHERE seq = :seq")
    suspend fun retry(seq: Long)

    @Query("SELECT COUNT(*) FROM outbox WHERE status IN ('pending','conflict')")
    suspend fun unresolvedCount(): Int
}

@Dao
interface SyncMetaDao {

    @Query("SELECT value FROM sync_meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(meta: SyncMetaEntity)

    @Query("SELECT value FROM sync_meta WHERE key = :key")
    fun observe(key: String): Flow<String?>
}

@Dao
interface SyncFlagDao {
    @Update
    suspend fun updateSale(sale: SaleEntity)
}

data class InventoryMovementRow(
    val id: String,
    val productName: String,
    val qtyDelta: Int,
    val type: String,
    val reason: String?,
    val createdAt: Long,
)

@Dao
interface InventoryDao {

    @Insert
    suspend fun insert(tx: InventoryTxEntity)

    @Upsert
    suspend fun upsertAll(txs: List<InventoryTxEntity>)

    @Query("DELETE FROM inventory_tx WHERE createdAt < :before")
    suspend fun pruneOlderThan(before: Long)

    @Query(
        """
        SELECT t.id AS id, p.name AS productName, t.qtyDelta AS qtyDelta,
               t.type AS type, t.reason AS reason, t.createdAt AS createdAt
        FROM inventory_tx t
        JOIN products p ON p.id = t.productId
        ORDER BY t.createdAt DESC
        LIMIT 200
        """
    )
    fun observeMovements(): Flow<List<InventoryMovementRow>>

    @Query("SELECT COALESCE(SUM(qtyDelta), 0) FROM inventory_tx WHERE productId = :productId")
    suspend fun netMovement(productId: String): Int
}

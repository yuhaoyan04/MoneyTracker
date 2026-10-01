package com.mudasir.smartledger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: TransactionRecord): Long

    @Update
    suspend fun update(record: TransactionRecord)

    @Delete
    suspend fun delete(record: TransactionRecord)

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TransactionRecord?

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ---- 已确认的活动记录（仪表盘 / 列表） ----
    @Query(
        """
        SELECT * FROM transactions
        WHERE isDeleted = 0 AND status = 'CONFIRMED'
        ORDER BY timestamp DESC
        """
    )
    fun observeActive(): Flow<List<TransactionRecord>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE isDeleted = 0 AND status = 'CONFIRMED'
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<TransactionRecord>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE isDeleted = 0 AND status = 'CONFIRMED'
        ORDER BY timestamp DESC
        """
    )
    suspend fun getActiveRaw(): List<TransactionRecord>

    // ---- 待确认收件箱（自动抓取） ----
    @Query(
        """
        SELECT * FROM transactions
        WHERE isDeleted = 0 AND status = 'PENDING'
        ORDER BY timestamp DESC
        """
    )
    fun observePending(): Flow<List<TransactionRecord>>

    @Query("SELECT COUNT(*) FROM transactions WHERE isDeleted = 0 AND status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE isDeleted = 0 AND status = 'PENDING' AND timestamp < :cutoff ORDER BY timestamp ASC")
    suspend fun getPendingOlderThan(cutoff: Long): List<TransactionRecord>

    // ---- 月度范围查询（统计） ----
    @Query(
        """
        SELECT * FROM transactions
        WHERE isDeleted = 0 AND status = 'CONFIRMED'
          AND timestamp >= :start AND timestamp < :end
        ORDER BY timestamp DESC
        """
    )
    suspend fun getInRange(start: Long, end: Long): List<TransactionRecord>

    // ---- 软删除 / 回收站 ----
    @Query(
        """
        SELECT * FROM transactions
        WHERE isDeleted = 1
        ORDER BY deletedAt DESC
        """
    )
    fun observeTrashed(): Flow<List<TransactionRecord>>

    @Query("UPDATE transactions SET isDeleted = 1, deletedAt = :now WHERE id = :id")
    suspend fun moveToTrash(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    @Query("DELETE FROM transactions WHERE isDeleted = 1 AND deletedAt < :before")
    suspend fun deleteExpiredTrash(before: Long)

    @Query("UPDATE transactions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    // ---- 汇总 ----
    @Query(
        """
        SELECT COALESCE(SUM(amount), 0.0) FROM transactions
        WHERE isDeleted = 0 AND status = 'CONFIRMED' AND type = :type
          AND timestamp >= :start AND timestamp < :end
        """
    )
    suspend fun sumByType(type: String, start: Long, end: Long): Double

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM transactions WHERE isDeleted = 0 AND status = 'CONFIRMED' AND type = :type")
    suspend fun sumAllByType(type: String): Double

    // ---- 备份 / 恢复 / 清理 ----
    /** 全量归档（含已软删除），保证备份文件永不因「清理」而丢失历史。 */
    @Query("SELECT * FROM transactions ORDER BY timestamp ASC")
    suspend fun getAllForBackup(): List<TransactionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<TransactionRecord>): List<Long>

    /** 清理某类型已确认记录（软删除，仅影响展示，不触发备份重写）。 */
    @Query("UPDATE transactions SET isDeleted = 1, deletedAt = :now WHERE isDeleted = 0 AND status = 'CONFIRMED' AND type = :type")
    suspend fun clearByType(type: String, now: Long = System.currentTimeMillis())
}

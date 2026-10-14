package com.mudasir.smartledger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WishlistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WishlistItem): Long

    @Query("SELECT * FROM wishlist_items ORDER BY CASE status WHEN 'WAITING' THEN 0 ELSE 1 END, createdAt DESC")
    fun observeAll(): Flow<List<WishlistItem>>

    @Query("SELECT * FROM wishlist_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WishlistItem?

    @Query("UPDATE wishlist_items SET status = :status, resolvedAt = :now WHERE id = :id AND status = 'WAITING'")
    suspend fun resolve(id: Long, status: String, now: Long = System.currentTimeMillis()): Int

    @Delete
    suspend fun delete(item: WishlistItem)

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM wishlist_items WHERE status = 'DROPPED'")
    fun observeSavedTotal(): Flow<Double>
}

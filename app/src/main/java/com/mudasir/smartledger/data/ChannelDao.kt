package com.mudasir.smartledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: PaymentChannel): Long

    @Query("SELECT * FROM payment_channels ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<PaymentChannel>>

    @Query("SELECT * FROM payment_channels ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<PaymentChannel>

    @Query("DELETE FROM payment_channels WHERE id = :id")
    suspend fun delete(id: Long)
}

package com.mudasir.smartledger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(category: Category): Long

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    fun observeByType(type: String): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    suspend fun getByType(type: String): List<Category>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<Category>

    @Query("SELECT * FROM categories WHERE type = :type AND level = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun getRoots(type: String): List<Category>

    @Query("SELECT * FROM categories WHERE parentName = :parentName AND type = :type ORDER BY sortOrder ASC, id ASC")
    suspend fun getChildren(parentName: String, type: String): List<Category>

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY level ASC, parentName ASC, sortOrder ASC, id ASC")
    suspend fun getStructured(type: String): List<Category>

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}

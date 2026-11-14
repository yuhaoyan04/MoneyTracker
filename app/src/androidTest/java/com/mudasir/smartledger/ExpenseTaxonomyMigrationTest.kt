package com.mudasir.smartledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpenseTaxonomyMigrationTest {
    @Test fun upgradingPreservesTransactionsIdsAndCustomCategories() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "taxonomy-migration-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_15_16).build()
        try {
            val original = open()
            val categories = original.categoryDao()
            val oldId = categories.insert(Category(name = "美容美发", parentName = "医疗", level = 2, isDefault = true))
            val customId = categories.insert(Category(name = "美妆护肤", parentName = "网购", level = 2, isDefault = false))
            val transactionId = original.transactionDao().insert(TransactionRecord(categoryId = oldId, categoryName = "美容美发", amount = 12.0))
            original.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 15 }

            val migrated = open()
            try {
                val all = migrated.categoryDao().getByType(TransactionRecord.TYPE_EXPENSE)
                val moved = all.single { it.name == "美容美发" }
                val custom = all.single { it.name == "美妆护肤" }
                assertEquals(oldId, moved.id)
                assertEquals("个人护理", moved.parentName)
                assertEquals(customId, custom.id)
                assertEquals("网购", custom.parentName)
                assertFalse(custom.isDefault)
                val cursor = migrated.openHelper.readableDatabase.query("SELECT categoryId, categoryName FROM transactions WHERE id=$transactionId")
                cursor.use { assertTrue(it.moveToFirst()); assertEquals(oldId, it.getLong(0)); assertEquals("美容美发", it.getString(1)) }
                assertTrue(all.any { it.name == "未分类支出" && it.parentName == "其他" })
                assertEquals(-1L, migrated.categoryDao().insert(Category(name = "美容美发", isDefault = true)))
                assertEquals(oldId, migrated.categoryDao().getByType(TransactionRecord.TYPE_EXPENSE).single { it.name == "美容美发" }.id)
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}

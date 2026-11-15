package com.mudasir.smartledger

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mudasir.smartledger.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyTaxonomyMigrationTest {
    private fun inMemory(block: (SupportSQLiteDatabase) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        try { block(helper.writableDatabase) } finally { helper.close() }
    }

    private fun count(db: SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { it.moveToFirst(); it.getInt(0) }

    @Test fun versionSevenSeedsOnlyAfterHierarchyColumnsExist() = inMemory { db ->
        AppDatabase.MIGRATION_7_8.migrate(db)
        assertEquals(0, count(db, "SELECT count(*) FROM categories"))
        AppDatabase.MIGRATION_8_9.migrate(db)
        assertEquals(1, count(db, "SELECT count(*) FROM categories WHERE name='美妆护肤' AND parentName='网购'"))
        assertEquals(1, count(db, "SELECT count(*) FROM payment_channels WHERE name='微信支付'"))
    }

    @Test fun conflictingLegacyNamesPreserveBothCategoriesAndRecords() = inMemory { db ->
        AppDatabase.MIGRATION_7_8.migrate(db)
        AppDatabase.MIGRATION_8_9.migrate(db)
        db.execSQL("INSERT INTO categories(name,type,color,sortOrder,isDefault,parentName,level,createdAt) VALUES('购物','EXPENSE','#123456',0,0,NULL,1,1)")
        db.execSQL("INSERT INTO transactions(type,amount,categoryName,channelName,timestamp,source,status,isDeleted,createdAt) VALUES('EXPENSE',1,'购物','现金',1,'MANUAL','CONFIRMED',0,1)")
        AppDatabase.MIGRATION_9_10.migrate(db)
        assertEquals(1, count(db, "SELECT count(*) FROM categories WHERE name='购物' AND isDefault=0"))
        assertEquals(1, count(db, "SELECT count(*) FROM categories WHERE name='网购'"))
        assertEquals(1, count(db, "SELECT count(*) FROM transactions WHERE categoryName='购物'"))
    }

    @Test fun unambiguousDefaultRenameKeepsChildAndTransactionLinks() = inMemory { db ->
        AppDatabase.MIGRATION_7_8.migrate(db)
        AppDatabase.MIGRATION_8_9.migrate(db)
        db.execSQL("DELETE FROM categories WHERE name='网购' AND type='EXPENSE'")
        db.execSQL("INSERT INTO categories(name,type,color,sortOrder,isDefault,parentName,level,createdAt) VALUES('购物','EXPENSE','#123456',0,1,NULL,1,1)")
        db.execSQL("UPDATE categories SET parentName='购物' WHERE name='美妆护肤' AND type='EXPENSE'")
        db.execSQL("INSERT INTO transactions(type,amount,categoryName,channelName,timestamp,source,status,isDeleted,createdAt) VALUES('EXPENSE',1,'购物','现金',1,'MANUAL','CONFIRMED',0,1)")
        AppDatabase.MIGRATION_9_10.migrate(db)
        assertEquals(0, count(db, "SELECT count(*) FROM categories WHERE name='购物' AND type='EXPENSE'"))
        assertEquals(1, count(db, "SELECT count(*) FROM categories WHERE name='美妆护肤' AND parentName='网购'"))
        assertEquals(1, count(db, "SELECT count(*) FROM transactions WHERE categoryName='网购'"))
    }
}

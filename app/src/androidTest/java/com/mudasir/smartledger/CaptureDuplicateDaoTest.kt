package com.mudasir.smartledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureDuplicateDaoTest {
    @Test fun crossSourceMatchIsFlaggedWithoutDiscardingEitherRecord() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.transactionDao()
            val first = TransactionRecord(amount = 20.0, source = TransactionRecord.SOURCE_CAPTURE_SMS,
                timestamp = 1_000_000L)
            val second = TransactionRecord(amount = 20.0, source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
                packageName = "com.tencent.mm", timestamp = 1_001_000L)
            val id = dao.insert(first)
            dao.insert(second)
            assertEquals(1, dao.countPossibleCrossSourceDuplicates(id, first.type, first.amount,
                700_000L, 1_300_000L, first.source, first.packageName))
            assertEquals(2, dao.countPending())
        } finally { db.close() }
    }
}

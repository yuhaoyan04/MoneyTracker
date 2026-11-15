package com.mudasir.smartledger

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureDuplicateDaoTest {
    @Test fun concurrentExactRedeliveryInsertsOnceButDifferentTimesRemain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val dao = db.transactionDao()
            val source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
            val raw = "支付成功 3.00元"
            val writes = (1..20).map {
                async(Dispatchers.IO) {
                    dao.insertExactCaptureOnce(TransactionRecord(amount = 3.0, rawText = raw,
                        source = source, packageName = "com.tencent.mm", timestamp = 1_000_000L))
                }
            }.awaitAll()
            assertEquals(1, writes.count { it > 0 })
            assertEquals(19, writes.count { it == -1L })
            dao.insertExactCaptureOnce(TransactionRecord(amount = 3.0, rawText = raw,
                source = source, packageName = "com.tencent.mm", timestamp = 1_000_001L))
            assertEquals(2, dao.countPending())
        } finally { db.close() }
    }
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

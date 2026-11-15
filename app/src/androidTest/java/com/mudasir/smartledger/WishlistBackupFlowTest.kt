package com.mudasir.smartledger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.WishlistItem
import com.mudasir.smartledger.util.AutoBackupManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only synthetic data in the isolated application. */
@RunWith(AndroidJUnit4::class)
class WishlistBackupFlowTest {
    @Test fun simultaneousBackupsProduceReadableLatestSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE).edit()
            .putLong("last_public_snapshot", System.currentTimeMillis()).commit()
        val dao = AppDatabase.getDatabase(context).wishlistDao()
        val wish = WishlistItem(name = "并发备份测试", amount = 5.0)
        val id = dao.insert(wish)
        try {
            val results = (1..6).map { async(Dispatchers.IO) { AutoBackupManager.backup(context) } }.awaitAll()
            assertTrue(results.all { it })
            val latest = context.filesDir.resolve("ledger_backups/smartledger-latest.json").readText()
            assertTrue(AutoBackupManager.decodeEnvelope(latest).payload.wishlist.orEmpty().any { it.stableId == wish.stableId })
        } finally { dao.getById(id)?.let { dao.delete(it) } }
    }

    @Test fun backupRestoresOnceAndDoesNotOverwriteResolvedLocalWish() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = AppDatabase.getDatabase(context).wishlistDao()
        val wish = WishlistItem(name = "隔离测试愿望", amount = 123.45)
        val originalId = dao.insert(wish)
        var restoredId = 0L
        try {
            val json = AutoBackupManager.envelopeJsonForSync(context)!!
            dao.delete(wish.copy(id = originalId))
            val first = AutoBackupManager.restoreFromEnvelopeJson(context, json)!!
            val restored = dao.getByStableId(wish.stableId)!!
            restoredId = restored.id
            assertEquals(1, first.wishlistInserted)
            assertEquals(wish.name, restored.name)
            assertEquals(WishlistItem.STATUS_WAITING, restored.status)
            withTimeout(10_000L) {
                while (WorkManager.getInstance(context).getWorkInfosForUniqueWork("wishlist_revisit_${restored.id}").get().isEmpty()) delay(100)
            }
            assertEquals(1, dao.resolve(restored.id, WishlistItem.STATUS_DROPPED))
            val second = AutoBackupManager.restoreFromEnvelopeJson(context, json)!!
            assertEquals(0, second.wishlistInserted)
            assertEquals(WishlistItem.STATUS_DROPPED, dao.getByStableId(wish.stableId)?.status)
        } finally {
            dao.getByStableId(wish.stableId)?.let { dao.delete(it) }
            if (restoredId > 0) WorkManager.getInstance(context).cancelUniqueWork("wishlist_revisit_$restoredId")
        }
    }
}

package com.mudasir.smartledger

import android.content.Context
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Expense
import com.mudasir.smartledger.data.MilkRecord
import com.mudasir.smartledger.data.CustomLedger
import com.mudasir.smartledger.data.CustomEntry
import com.mudasir.smartledger.data.CustomField
import com.mudasir.smartledger.data.FieldType
import com.mudasir.smartledger.util.BackupManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class LegacyBackupFlowTest {
    @Test fun directLocalBackupIsPublishedAndReadable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.junit.Assume.assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var uri: android.net.Uri? = null
        try {
            val path = BackupManager.createLocalZipBackup(context, db)
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            context.contentResolver.query(collection, arrayOf("_id", "is_pending", "_size"),
                "_display_name = ?", arrayOf(path.substringAfterLast('/')), null)!!.use {
                assertTrue(it.moveToFirst())
                uri = android.content.ContentUris.withAppendedId(collection, it.getLong(0))
                assertEquals(0, it.getInt(1))
                assertTrue(it.getLong(2) > 0)
            }
            java.util.zip.ZipInputStream(context.contentResolver.openInputStream(uri!!)!!).use {
                assertEquals("ledger_data.json", it.nextEntry.name)
            }
        } finally { uri?.let { context.contentResolver.delete(it, null, null) }; db.close() }
    }
    @Test fun olderZipWithoutDailyRecordsCanBeRestored() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val zip = File(context.cacheDir, "old-schema-test.zip")
        try {
            ZipOutputStream(zip.outputStream()).use {
                it.putNextEntry(ZipEntry("ledger_data.json"))
                it.write("""{"timestamp":1,"expenses":[],"electricity":[],"milkRecords":[],"customLedgers":[],"customEntries":[]}""".toByteArray())
                it.closeEntry()
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
            val result = BackupManager.restoreFromZip(context, uri, db) { false }
            assertEquals(0, result.expenseAdded)
        } finally { db.close(); zip.delete() }
    }
    @Test fun conflictingCustomLedgerSchemaDoesNotReceiveEntries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val zip = File(context.cacheDir, "ledger-conflict-test.zip")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        try {
            source.customLedgerDao().insertLedger(CustomLedger(id = 1, name = "test", iconName = "x", fields = listOf(CustomField("a", FieldType.TEXT))))
            source.customLedgerDao().insertEntry(CustomEntry(ledgerId = 1, date = 1000, amount = 1.0, dataJson = "{}"))
            target.customLedgerDao().insertLedger(CustomLedger(id = 1, name = "TEST", iconName = "x", fields = listOf(CustomField("b", FieldType.NUMBER))))
            BackupManager.createZipBackup(context, uri, source) { false }
            val report = BackupManager.restoreFromZip(context, uri, target) { false }
            assertEquals(1, report.customSkipped)
            assertTrue(target.customLedgerDao().getAllRawEntries().isEmpty())
        } finally { source.close(); target.close(); zip.delete() }
    }
    @Test fun restoreKeepsLocalConflictsAndIsRepeatable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val zip = File(context.cacheDir, "restore-conflict-test.zip")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        try {
            source.expenseDao().insertExpense(Expense(title = "one", description = "", amount = 3.0, date = 1000))
            source.expenseDao().insertExpense(Expense(title = "two", description = "", amount = 3.0, date = 1000))
            source.milkDao().insert(MilkRecord(monthName = "January", monthIndex = 0, year = 2025, pricePerLiter = 1.0))
            target.milkDao().insert(MilkRecord(monthName = "January", monthIndex = 0, year = 2025, pricePerLiter = 9.0))
            target.expenseDao().insertExpense(Expense(title = "trash", description = "", amount = 1.0, date = 100, isDeleted = true, deletedAt = 100))
            BackupManager.createZipBackup(context, uri, source) { false }
            val first = BackupManager.restoreFromZip(context, uri, target) { false }
            assertEquals(2, first.expenseAdded)
            assertEquals(1, first.milkSkipped)
            assertEquals(9.0, target.milkDao().getAllRaw().single().pricePerLiter, 0.0)
            val second = BackupManager.restoreFromZip(context, uri, target) { false }
            assertEquals(0, second.expenseAdded)
            assertEquals(2, second.expenseSkipped)
            assertEquals(setOf("one", "two", "trash"), target.expenseDao().getAllRaw().map { it.title }.toSet())
            assertTrue(target.expenseDao().getAllRaw().single { it.title == "trash" }.isDeleted)
        } finally { source.close(); target.close(); zip.delete() }
    }
    @Test fun sameNamedAndSharedImagesSurviveExportAndRestore() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val root = File(context.cacheDir, "legacy-backup-test").apply { mkdirs() }
        val one = File(root, "one/receipt.jpg").apply { parentFile!!.mkdirs(); writeText("first") }
        val two = File(root, "two/receipt.jpg").apply { parentFile!!.mkdirs(); writeText("second") }
        val zip = File(root, "backup.zip")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        try {
            source.expenseDao().insertExpense(Expense(title = "first", description = "", amount = 1.0, date = 1000, imagePaths = listOf(one.path, one.path)))
            source.expenseDao().insertExpense(Expense(title = "second", description = "", amount = 2.0, date = 2000, imagePaths = listOf(two.path)))
            BackupManager.createZipBackup(context, uri, source) { false }
            ZipFile(zip).use { archive ->
                assertEquals(2, archive.entries().asSequence().count { it.name.startsWith("images/") })
            }
            BackupManager.restoreFromZip(context, uri, target) { false }
            val records = target.expenseDao().getAllRaw().associateBy { it.title }
            assertEquals(2, records.size)
            assertEquals("first", File(records.getValue("first").imagePaths[0]).readText())
            assertEquals(records.getValue("first").imagePaths[0], records.getValue("first").imagePaths[1])
            assertEquals("second", File(records.getValue("second").imagePaths[0]).readText())
        } finally { source.close(); target.close(); root.deleteRecursively() }
    }

    @Test fun missingImageCausesBackupFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val zip = File(context.cacheDir, "missing-image-test.zip")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        try {
            db.expenseDao().insertExpense(Expense(title = "missing", description = "", amount = 1.0, date = 1000, imagePaths = listOf(File(context.cacheDir, "no-such-file.jpg").path)))
            assertTrue(runCatching { BackupManager.createZipBackup(context, uri, db) { false } }.isFailure)
        } finally { db.close(); zip.delete() }
    }
}

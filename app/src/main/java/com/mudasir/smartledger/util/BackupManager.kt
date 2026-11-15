package com.mudasir.smartledger.util

import android.content.Context
import android.net.Uri
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.BackupData
import com.mudasir.smartledger.data.DateMode
import com.mudasir.smartledger.data.LedgerType
import com.mudasir.smartledger.data.RestoreResult
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {

    suspend fun createLocalZipBackup(context: Context, db: AppDatabase): String = withContext(Dispatchers.IO) {
        val name = "SmartLedger_legacy_${System.currentTimeMillis()}.zip"
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/SmartLedger")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建备份文件")
            try {
                createZipBackup(context, uri, db) { false }
                val published = resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                check(published == 1) { "无法完成备份文件" }
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            "Download/SmartLedger/$name"
        } else {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: error("本机存储不可用")
            val file = File(directory, name)
            try { createZipBackup(context, Uri.fromFile(file), db) { false } }
            catch (e: Exception) { file.delete(); throw e }
            file.absolutePath
        }
    }

    private const val JSON_FILENAME = "ledger_data.json"
    private const val IMAGES_DIR = "images"

    suspend fun createZipBackup(
        context: Context,
        uri: Uri,
        db: AppDatabase,
        checkCancel: () -> Boolean
    ): RestoreResult = withContext(Dispatchers.IO) {
        val result = RestoreResult()
        val cr = context.contentResolver

        val output = cr.openOutputStream(uri) ?: error("无法打开备份文件")
        output.use { outputStream ->
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zos ->
                // Different source folders can contain the same filename. Write each distinct
                // image once under a unique portable name, and fail rather than omit an image.
                val archivedImages = mutableMapOf<String, String>()
                fun archiveImage(path: String): String = archivedImages.getOrPut(path) {
                    val file = File(path)
                    require(file.isFile && file.canRead()) { "无法读取账本图片" }
                    val portableName = "${UUID.randomUUID()}_${file.name}"
                    addToZip(zos, file, "$IMAGES_DIR/$portableName")
                    portableName
                }

                val expenses = db.expenseDao().getAllRaw()
                val electricity = db.electricityDao().getAllRaw()
                val milk = db.milkDao().getAllRaw()

                // 1. Process Expenses
                val processedExpenses = expenses.map { expense ->
                    if (checkCancel()) throw Exception("Backup Stopped")
                    val newPaths = expense.imagePaths.map { path ->
                        archiveImage(path)
                    }
                    if (!expense.isDeleted) {
                        result.expenseAdded++
                    }
                    val safeDeletedAt = if (expense.isDeleted) {
                        expense.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                    } else null
                    expense.copy(imagePaths = newPaths, deletedAt = safeDeletedAt)
                }

                // 2. Process Electricity
                val processedElectricity = electricity.map { elec ->
                    if (checkCancel()) throw Exception("Backup Stopped")
                    val newPaths = elec.imagePaths.map { path ->
                        archiveImage(path)
                    }
                    if (!elec.isDeleted) {
                        result.elecAdded++
                    }
                    val safeDeletedAt = if (elec.isDeleted) {
                        elec.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                    } else null
                    elec.copy(imagePaths = newPaths, deletedAt = safeDeletedAt)
                }

                // 3. Process Milk
                result.milkAdded = milk.count { !it.isDeleted }
                val processedMilk = milk.map { m ->
                    val safeDeletedAt = if (m.isDeleted) {
                        m.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                    } else null
                    m.copy(deletedAt = safeDeletedAt)
                }

                val customEntries = db.customLedgerDao().getAllRawEntries()
                val customLedgers = db.customLedgerDao().getAllLedgersList()

                // 4. Process Custom Entries
                val processedCustomEntries = customEntries.map { entry ->
                    if (checkCancel()) throw Exception("Backup Stopped")

                    val newPaths = entry.imagePaths.map { path ->
                        archiveImage(path)
                    }

                    val parentLedger = customLedgers.find { it.id == entry.ledgerId }

                    if (!entry.isDeleted && parentLedger != null && !parentLedger.isDeleted) {
                        val ledgerName = parentLedger.name
                        result.customCounts[ledgerName] = (result.customCounts[ledgerName] ?: 0) + 1
                    }

                    val safeDeletedAt = if (entry.isDeleted) {
                        entry.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                    } else null
                    entry.copy(imagePaths = newPaths, deletedAt = safeDeletedAt)
                }

                val customDailyRecords = db.customLedgerDao().getAllRawDailyRecords()

                customDailyRecords.forEach { record ->
                    val parentLedger = customLedgers.find { it.id == record.ledgerId }
                    if (!record.isDeleted && parentLedger != null && !parentLedger.isDeleted) {
                        val ledgerName = parentLedger.name
                        result.customCounts[ledgerName] = (result.customCounts[ledgerName] ?: 0) + 1
                    }
                }

                val processedCustomLedgers = customLedgers.map { l ->
                    val safeDeletedAt = if (l.isDeleted) {
                        l.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                    } else null
                    l.copy(deletedAt = safeDeletedAt)
                }

                val processedDailyRecords = customDailyRecords.map { r ->
                    val safeDeletedAt = if (r.isDeleted) {
                        r.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                    } else null
                    r.copy(deletedAt = safeDeletedAt)
                }

                val backupData = BackupData(
                    timestamp = System.currentTimeMillis(),
                    expenses = processedExpenses,
                    electricity = processedElectricity,
                    milkRecords = processedMilk,
                    customLedgers = processedCustomLedgers,
                    customEntries = processedCustomEntries,
                    customDailyRecords = processedDailyRecords
                )

                zos.putNextEntry(ZipEntry(JSON_FILENAME))
                zos.write(Gson().toJson(backupData).toByteArray())
                zos.closeEntry()
            }
        }
        return@withContext result
    }

    class RestoreCancelledException : Exception("Restore halted by user")

    suspend fun restoreFromZip(
        context: Context,
        uri: Uri,
        db: AppDatabase,
        checkCancel: () -> Boolean
    ): RestoreResult = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val internalImgDir = File(context.filesDir, "ledger_images").apply { mkdirs() }
        var backupData: BackupData? = null
        val importedImages = mutableMapOf<String, String>()
        val usedImages = mutableSetOf<String>()
        var restoreSucceeded = false
        val result = RestoreResult()

        try {
        // 1. EXTRACT & PARSE
        cr.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (checkCancel()) throw RestoreCancelledException()
                    when {
                        entry.name == JSON_FILENAME -> {
                            val bytes = zis.readBytes()
                            val jsonString = String(bytes)
                            backupData = Gson().fromJson(jsonString, BackupData::class.java)
                        }
                        entry.name.startsWith("$IMAGES_DIR/") && !entry.isDirectory -> {
                            val fileName = entry.name.substringAfterLast("/")
                            require(fileName.isNotBlank() && fileName !in importedImages) { "备份图片名称无效或重复" }
                            val destFile = File(internalImgDir, "${UUID.randomUUID()}_$fileName")
                            importedImages[fileName] = destFile.absolutePath
                            destFile.outputStream().use { fos -> zis.copyTo(fos) }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        if (backupData == null) throw Exception("Invalid Backup: JSON data not found inside ZIP.")

        fun restoredPath(path: String): String {
            val archived = importedImages[File(path).name]
            if (archived != null) {
                usedImages.add(archived)
                return archived
            }
            require(File(path).isFile) { "备份缺少账本图片" }
            return path
        }

        // 2. DATABASE TRANSACTION
        db.runInTransaction {
            runBlocking {
                val existingExpenses = db.expenseDao().getAllRaw().toMutableList()
                val existingElec = db.electricityDao().getAllRaw().toMutableList()
                val existingMilk = db.milkDao().getAllRaw()

                backupData?.let { data ->
                    // --- EXPENSES ---
                    data.expenses.forEach { incoming ->
                        if (checkCancel()) throw RestoreCancelledException()

                        val existing = existingExpenses.find {
                            it.date == incoming.date && it.amount == incoming.amount &&
                                it.title == incoming.title && it.description == incoming.description
                        }
                        if (existing != null) existingExpenses.remove(existing)

                        val safeDeletedAt = if (incoming.isDeleted) {
                            incoming.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                        } else null

                        if (existing == null) {
                            val paths = incoming.imagePaths.map(::restoredPath)
                            db.expenseDao().insertExpense(incoming.copy(id = 0, imagePaths = paths, deletedAt = safeDeletedAt))

                            if (!incoming.isDeleted) result.expenseAdded++
                        } else {
                            if (!incoming.isDeleted) result.expenseSkipped++
                        }
                    }

                    // --- ELECTRICITY  ---
                    data.electricity.forEach { incoming ->
                        if (checkCancel()) throw RestoreCancelledException()

                        val existing = existingElec.find {
                            it.startDate == incoming.startDate && it.endDate == incoming.endDate &&
                                it.startUnits == incoming.startUnits && it.endUnits == incoming.endUnits &&
                                it.totalUnits == incoming.totalUnits && it.amount == incoming.amount &&
                                it.description == incoming.description
                        }
                        if (existing != null) existingElec.remove(existing)

                        val safeDeletedAt = if (incoming.isDeleted) {
                            incoming.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                        } else null

                        if (existing == null) {
                            val paths = incoming.imagePaths.map(::restoredPath)
                            db.electricityDao().insert(incoming.copy(id = 0, imagePaths = paths, deletedAt = safeDeletedAt))

                            if (!incoming.isDeleted) result.elecAdded++
                        } else {
                            if (!incoming.isDeleted) result.elecSkipped++
                        }
                    }

                    // --- MILK ---
                    data.milkRecords.forEach { incoming ->
                        if (checkCancel()) throw RestoreCancelledException()

                        val existing = existingMilk
                            .sortedBy { it.deletedAt ?: 0L }
                            .find { it.monthIndex == incoming.monthIndex && it.year == incoming.year }

                        val safeDeletedAt = if (incoming.isDeleted) {
                            incoming.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                        } else null

                        if (existing == null) {
                            db.milkDao().insert(incoming.copy(id = 0, deletedAt = safeDeletedAt))
                            if (!incoming.isDeleted) {
                                result.milkAdded++
                            }
                        } else {
                            if (!incoming.isDeleted) result.milkSkipped++
                        }
                    }

                    // --- CUSTOM LEDGER RESTORE ---
                    data.customLedgers.forEach { incomingLedger ->
                        if (checkCancel()) throw RestoreCancelledException()
                        val existingLedger = db.customLedgerDao().getAllLedgersList().find {
                            it.name.equals(incomingLedger.name, ignoreCase = true)
                        }
                        val safeDeletedAt = if (incomingLedger.isDeleted) {
                            incomingLedger.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                        } else null

                        if (existingLedger == null) {
                            val safeLedger = incomingLedger.copy(
                                id = 0,
                                isDeleted = incomingLedger.isDeleted,
                                deletedAt = safeDeletedAt,
                                dateMode = incomingLedger.dateMode,
                                ledgerType = incomingLedger.ledgerType
                            )
                            db.customLedgerDao().insertLedger(safeLedger)
                        }
                    }

                    // 2. RESTORE CUSTOM ENTRIES
                    val localLedgers = db.customLedgerDao().getAllLedgersList()

                    data.customEntries.forEach { incomingEntry ->
                        if (checkCancel()) throw RestoreCancelledException()

                        val incomingLedger = data.customLedgers.find { it.id == incomingEntry.ledgerId }
                        val targetLedger = localLedgers.find { it.name.equals(incomingLedger?.name, ignoreCase = true) }

                        if (targetLedger != null && incomingLedger != null &&
                            targetLedger.fields == incomingLedger.fields && targetLedger.ledgerType == incomingLedger.ledgerType &&
                            targetLedger.dateMode == incomingLedger.dateMode) {
                            val isDuplicate = db.customLedgerDao().checkEntryExists(
                                targetLedger.id,
                                incomingEntry.date,
                                incomingEntry.amount ?: 0.0,
                                incomingEntry.dataJson
                            )

                            if (!isDuplicate) {
                                val paths = incomingEntry.imagePaths.map(::restoredPath)
                                val safeDeletedAt = if (incomingEntry.isDeleted) {
                                    incomingEntry.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                                } else null
                                db.customLedgerDao().insertEntry(incomingEntry.copy(
                                    id = 0,
                                    ledgerId = targetLedger.id,
                                    imagePaths = paths,
                                    isDeleted = incomingEntry.isDeleted,
                                    deletedAt = safeDeletedAt
                                ))

                                if (!incomingEntry.isDeleted && !targetLedger.isDeleted) {
                                    val name = targetLedger.name
                                    result.customCounts[name] = (result.customCounts[name] ?: 0) + 1
                                }
                            } else {
                                result.customSkipped++
                            }
                        } else { result.customSkipped++ }
                    }

                    // --- RESTORE CUSTOM DAILY RECORDS ---
                    // Older ZIP backups predate daily records; Gson leaves the missing field null.
                    val dailyRecordsToRestore = data.customDailyRecords.orEmpty()
                    dailyRecordsToRestore.forEach { incomingRecord ->
                        if (checkCancel()) throw RestoreCancelledException()

                        val incomingLedger = data.customLedgers.find { it.id == incomingRecord.ledgerId }
                        val targetLedger = localLedgers.find { it.name.equals(incomingLedger?.name, ignoreCase = true) }

                        if (targetLedger != null && incomingLedger != null &&
                            targetLedger.fields == incomingLedger.fields && targetLedger.ledgerType == incomingLedger.ledgerType &&
                            targetLedger.dateMode == incomingLedger.dateMode) {
                            val existing = db.customLedgerDao().getDailyRecordByMonthYear(
                                targetLedger.id,
                                incomingRecord.monthIndex,
                                incomingRecord.year
                            )

                            val safeDeletedAt = if (incomingRecord.isDeleted) {
                                incomingRecord.deletedAt?.takeIf { it > 0L } ?: System.currentTimeMillis()
                            } else null

                            if (existing == null) {
                                db.customLedgerDao().insertDailyRecord(
                                    incomingRecord.copy(id = 0, ledgerId = targetLedger.id, deletedAt = safeDeletedAt)
                                )
                                if (!incomingRecord.isDeleted && !targetLedger.isDeleted) {
                                    val name = targetLedger.name
                                    result.customCounts[name] = (result.customCounts[name] ?: 0) + 1
                                }
                            } else {
                                result.customSkipped++
                            }
                        } else { result.customSkipped++ }
                    }
                }
            }
        }

        restoreSucceeded = true
        return@withContext result
        } finally {
            importedImages.values.filter { !restoreSucceeded || it !in usedImages }
                .forEach { File(it).delete() }
        }
    }

    private fun addToZip(zos: ZipOutputStream, file: File, zipPath: String) {
        FileInputStream(file).use { fis ->
            zos.putNextEntry(ZipEntry(zipPath))
            fis.copyTo(zos)
            zos.closeEntry()
        }
    }
}

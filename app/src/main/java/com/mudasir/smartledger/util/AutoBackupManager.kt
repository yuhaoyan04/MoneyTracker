package com.mudasir.smartledger.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.PaymentChannel
import com.mudasir.smartledger.data.TransactionRecord

/**
 * 自动本地备份与恢复（区别于用户手动 zip 导入导出的 BackupManager）。
 *
 * 策略：
 *  - 备份内容：全部交易（含已软删除，即「完整归档」）+ 分类 + 渠道，序列化为 JSON。
 *  - 写入位置：公共 Download/MoneyTracker/moneytracker_backup.json（经 MediaStore，Q+；
 *    <Q 直写公共目录）。该文件随系统而非随 App，**卸载重装后仍在**。
 *  - 同时写一份到应用私有外部目录作为快速缓存。
 *  - 「清理支出/收入」为软删除，仅影响展示；清理不触发备份重写，备份文件保留历史。
 *  - 恢复时把归档原样 REPLACE 回库（保留 isDeleted 状态），从而卸载重装后数据与清理状态都还原。
 */
object AutoBackupManager {

    private const val PUBLIC_NAME = "moneytracker_backup.json"
    private const val PUBLIC_DIR = "MoneyTracker"

    data class Snapshot(
        val exportedAt: Long,
        val transactions: List<TransactionRecord>,
        val categories: List<Category>,
        val channels: List<PaymentChannel>
    )

    data class RestoreOutcome(
        val imported: Int,
        val skipped: Int,
        val source: String
    )

    private data class SnapshotPayload(
        val exportedAt: Long = 0,
        val transactions: List<TransactionRecord>? = emptyList(),
        val categories: List<Category>? = emptyList(),
        val channels: List<PaymentChannel>? = emptyList()
    )

    fun backup(context: Context): Boolean {
        val db = AppDatabase.getDatabase(context)
        val snap = Snapshot(
            exportedAt = System.currentTimeMillis(),
            transactions = kotlinx.coroutines.runBlocking { db.transactionDao().getAllForBackup() },
            categories = kotlinx.coroutines.runBlocking { db.categoryDao().getAll() },
            channels = kotlinx.coroutines.runBlocking { db.channelDao().getAll() }
        )
        val json = Gson().toJson(snap)
        writeToPrivate(context, json)
        return writeToPublic(context, json)
    }

    fun restore(context: Context): Int {
        val json = readFromPublic(context) ?: readFromPrivate(context) ?: return -1
        return runCatching { restoreJson(context, json, "自动备份").imported }.getOrDefault(-1)
    }

    /** 通过系统文件选择器取得 URI 后恢复，解决重装后无法直接查询旧 Downloads JSON。 */
    fun restoreFromUri(context: Context, uri: Uri): RestoreOutcome {
        val json = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalArgumentException("无法读取所选文件")
        val outcome = restoreJson(context, json, "所选文件")
        // 保存一份到当前安装实例的私有外部目录，后续可直接自动恢复。
        writeToPrivate(context, json)
        return outcome
    }

    private fun restoreJson(context: Context, json: String, source: String): RestoreOutcome {
        val root = JsonParser.parseString(json).asJsonObject
        val db = AppDatabase.getDatabase(context)
        if (root.has("transactions")) {
            val snap = Gson().fromJson(root, SnapshotPayload::class.java)
            val incoming = snap.transactions.orEmpty()
            var imported = 0
            var skipped = 0
            kotlinx.coroutines.runBlocking {
                db.runInTransaction {
                    kotlinx.coroutines.runBlocking {
                        val existingKeys = db.transactionDao().getAllForBackup()
                            .mapTo(mutableSetOf()) { transactionKey(it) }
                        incoming.forEach { record ->
                            if (existingKeys.add(transactionKey(record))) {
                                // 不复用旧主键，防止覆盖重装后已经新记的账。
                                db.transactionDao().insert(record.copy(id = 0))
                                imported++
                            } else skipped++
                        }

                        val existingCategories = db.categoryDao().getAll()
                            .mapTo(mutableSetOf()) { "${it.type}|${it.name}" }
                        snap.categories.orEmpty().forEach { category ->
                            if (existingCategories.add("${category.type}|${category.name}")) {
                                db.categoryDao().insert(category.copy(id = 0))
                            }
                        }
                        val existingChannels = db.channelDao().getAll()
                            .mapTo(mutableSetOf()) { it.name }
                        snap.channels.orEmpty().forEach { channel ->
                            if (existingChannels.add(channel.name)) {
                                db.channelDao().insert(channel.copy(id = 0))
                            }
                        }
                    }
                }
            }
            return RestoreOutcome(imported, skipped, source)
        }

        // 兼容用户从旧 ZIP 中单独解出的 ledger_data.json。
        if (root.has("expenses")) {
            val expenseType = object : TypeToken<List<com.mudasir.smartledger.data.Expense>>() {}.type
            val expenses: List<com.mudasir.smartledger.data.Expense> =
                Gson().fromJson(root.get("expenses"), expenseType) ?: emptyList()
            val result = kotlinx.coroutines.runBlocking {
                LegacyTransactionImporter.importExpenses(db, expenses)
            }
            return RestoreOutcome(result.imported, result.skipped, "旧版账本 JSON")
        }
        throw IllegalArgumentException("不是可识别的 SmartLedger 备份")
    }

    private fun transactionKey(record: TransactionRecord): String =
        "${record.type}|${java.lang.Double.doubleToLongBits(record.amount)}|${record.timestamp}|" +
            "${record.merchant.orEmpty()}|${record.note.orEmpty()}|${record.source}"

    fun lastBackupInfo(context: Context): String {
        val f = privateFile(context)
        return if (f.exists()) {
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))
            val size = if (f.length() > 1024) "${f.length() / 1024} KB" else "${f.length()} B"
            "最近备份：$time · $size"
        } else "尚未备份"
    }

    private fun privateFile(context: Context): java.io.File =
        java.io.File(context.applicationContext.getExternalFilesDir(null), "backup/$PUBLIC_NAME")

    private fun writeToPrivate(context: Context, json: String) {
        try {
            val f = privateFile(context)
            f.parentFile?.mkdirs()
            f.writeText(json)
        } catch (_: Exception) { }
    }

    private fun readFromPrivate(context: Context): String? = try {
        val f = privateFile(context)
        if (f.exists()) f.readText() else null
    } catch (_: Exception) { null }

    private fun writeToPublic(context: Context, json: String): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToPublicMediaStore(context, json)
        } else {
            writeToPublicLegacy(json)
        }
    }

    private fun writeToPublicMediaStore(context: Context, json: String): Boolean {
        var ok = false
        try {
            val resolver = context.applicationContext.contentResolver
            try {
                findPublicUri(context)?.let { resolver.delete(it, null, null) }
            } catch (_: Exception) { }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, PUBLIC_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$PUBLIC_DIR")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            resolver.openOutputStream(uri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
                ok = true
            }
        } catch (_: Exception) { ok = false }
        return ok
    }

    @Suppress("DEPRECATION")
    private fun writeToPublicLegacy(json: String): Boolean {
        return try {
            val dir = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_DIR)
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, PUBLIC_NAME).writeText(json)
            true
        } catch (_: Exception) { false }
    }

    private fun findPublicUri(context: Context): Uri? {
        val resolver = context.applicationContext.contentResolver
        val cols = arrayOf(MediaStore.Downloads._ID)
        val sel = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?"
        val args = arrayOf(PUBLIC_NAME, "Download/$PUBLIC_DIR/")
        resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols, sel, args, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            }
        }
        return null
    }

    private fun readFromPublic(context: Context): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            readFromPublicMediaStore(context)
        } else {
            readFromPublicLegacy()
        }
    }

    private fun readFromPublicMediaStore(context: Context): String? = try {
        val uri = findPublicUri(context) ?: return null
        context.applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun readFromPublicLegacy(): String? = try {
        val f = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$PUBLIC_DIR/$PUBLIC_NAME")
        if (f.exists()) f.readText() else null
    } catch (_: Exception) { null }
}

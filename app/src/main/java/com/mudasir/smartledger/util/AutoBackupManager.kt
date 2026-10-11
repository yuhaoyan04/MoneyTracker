package com.mudasir.smartledger.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.google.gson.Gson
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
        return try {
            val type = object : TypeToken<Snapshot>() {}.type
            val snap = Gson().fromJson(json, type) as? Snapshot ?: return -1
            val db = AppDatabase.getDatabase(context)
            kotlinx.coroutines.runBlocking {
                if (snap.transactions.isNotEmpty()) db.transactionDao().insertAll(snap.transactions)
                if (snap.categories.isNotEmpty()) snap.categories.forEach { db.categoryDao().insert(it) }
                if (snap.channels.isNotEmpty()) snap.channels.forEach { db.channelDao().insert(it) }
            }
            snap.transactions.size
        } catch (e: Exception) { -1 }
    }

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

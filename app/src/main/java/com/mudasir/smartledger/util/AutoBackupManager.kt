package com.mudasir.smartledger.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mudasir.smartledger.BuildConfig
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.CategoryBackupDto
import com.mudasir.smartledger.data.ChannelBackupDto
import com.mudasir.smartledger.data.LedgerBackupEnvelope
import com.mudasir.smartledger.data.LedgerBackupPayload
import com.mudasir.smartledger.data.LedgerJournalEntry
import com.mudasir.smartledger.data.LedgerRestoreReport
import com.mudasir.smartledger.data.PaymentChannel
import com.mudasir.smartledger.data.TransactionBackupDto
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Versioned, append-only local ledger backup.
 *
 * Each save creates an immutable public snapshot instead of deleting the previous backup. The
 * portable JSON contains a current checkpoint and an incremental journal of changes since the
 * previous checkpoint. A private atomic `latest` copy makes routine recovery fast; the system
 * document picker is the reliable import route after uninstall/reinstall because scoped storage
 * can hide MediaStore files created by the previous installation.
 */
object AutoBackupManager {

    private const val PUBLIC_DIR = "SmartLedger"
    private const val LATEST_NAME = "smartledger-latest.json"
    private const val JOURNAL_NAME = "smartledger-journal.json"
    private const val MAX_JOURNAL_ENTRIES = 10_000
    private const val MAX_PRIVATE_GENERATIONS = 5
    private const val PUBLIC_SNAPSHOT_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private val gson = Gson()

    /** v2.23.x and earlier auto-backup shape; accepted only as a read migration. */
    private data class LegacySnapshot(
        val exportedAt: Long,
        val transactions: List<TransactionRecord>? = emptyList(),
        val categories: List<Category>? = emptyList(),
        val channels: List<PaymentChannel>? = emptyList()
    )

    fun backup(context: Context): Boolean = runCatching {
        val json = createBackupJson(context)
        writePrivateGeneration(context, json)
        val prefs = context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastPublic = prefs.getLong("last_public_snapshot", 0L)
        if (now - lastPublic >= PUBLIC_SNAPSHOT_INTERVAL_MS) {
            if (!writeImmutablePublicSnapshot(context, json)) return@runCatching false
            prefs.edit().putLong("last_public_snapshot", now).apply()
        }
        true
    }.getOrDefault(false)

    /** Explicit user action: always emits a new immutable shared-storage snapshot. */
    fun backupNow(context: Context): Boolean = runCatching {
        val json = createBackupJson(context)
        writePrivateGeneration(context, json)
        val ok = writeImmutablePublicSnapshot(context, json)
        if (ok) context.getSharedPreferences("ledger_backup", Context.MODE_PRIVATE)
            .edit().putLong("last_public_snapshot", System.currentTimeMillis()).apply()
        ok
    }.getOrDefault(false)

    /** Writes a portable backup selected by the user through ACTION_CREATE_DOCUMENT. */
    fun exportToUri(context: Context, uri: Uri): Boolean = runCatching {
        val json = createBackupJson(context)
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(json.toByteArray(Charsets.UTF_8))
        } ?: error("无法打开目标文件")
        writePrivateGeneration(context, json)
        true
    }.getOrDefault(false)

    /** Restore the newest backup visible to this installation. */
    fun restore(context: Context): LedgerRestoreReport? {
        val json = readLatestPrivate(context) ?: readNewestOwnedPublic(context) ?: return null
        return runCatching { restoreJson(context, json) }.getOrNull()
    }

    /** 供 WebDAV 云同步：生成当前账本的可迁移信封 JSON。 */
    fun envelopeJsonForSync(context: Context): String? = runCatching { createBackupJson(context) }.getOrNull()

    /** 供 WebDAV 云同步：解析并恢复云端下载的信封 JSON（走同一校验+合并管线）。 */
    fun restoreFromEnvelopeJson(context: Context, json: String): LedgerRestoreReport? =
        runCatching { restoreJson(context, json) }.getOrNull()

    /** Restore a user-selected backup; this remains reliable across reinstall and OEM variants. */
    fun restoreFromUri(context: Context, uri: Uri): LedgerRestoreReport =
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            restoreJson(context, it.readText())
        } ?: error("无法读取所选备份")

    fun suggestedExportName(now: Long = System.currentTimeMillis()): String =
        "SmartLedger_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))}.json"

    fun lastBackupInfo(context: Context): String {
        val file = latestFile(context)
        return if (file.exists()) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
            val size = if (file.length() > 1024) "${file.length() / 1024} KB" else "${file.length()} B"
            "最近备份：$time · $size · 含增量历史"
        } else "尚未备份"
    }

    internal fun encodeEnvelope(
        payload: LedgerBackupPayload,
        journal: List<LedgerJournalEntry>,
        exportedAt: Long,
        appVersion: String
    ): String {
        val checksum = sha256(gson.toJson(payload))
        return gson.toJson(
            LedgerBackupEnvelope(
                exportedAt = exportedAt,
                appVersion = appVersion,
                payload = payload,
                journal = journal.takeLast(MAX_JOURNAL_ENTRIES),
                checksumSha256 = checksum
            )
        )
    }

    internal fun decodeEnvelope(json: String): LedgerBackupEnvelope {
        val root = JsonParser.parseString(json).asJsonObject
        val format = root.get("format")?.asString
        if (format == null && root.has("transactions")) {
            val legacy = gson.fromJson(root, LegacySnapshot::class.java)
            val payload = LedgerBackupPayload(
                transactions = legacy.transactions.orEmpty().map {
                    val dto = it.toDto()
                    dto.copy(stableId = "legacy-${entityKey(dto)}")
                },
                categories = legacy.categories.orEmpty().map { it.toDto() },
                channels = legacy.channels.orEmpty().map { it.toDto() }
            )
            return LedgerBackupEnvelope(
                exportedAt = legacy.exportedAt,
                appVersion = "legacy-v2.23",
                payload = payload,
                checksumSha256 = sha256(gson.toJson(payload))
            )
        }
        require(format == LedgerBackupEnvelope.FORMAT) { "不是 SmartLedger 备份文件" }
        val schema = root.get("schemaVersion")?.asInt ?: error("备份缺少版本号")
        require(schema in 1..LedgerBackupEnvelope.CURRENT_SCHEMA) { "备份版本过新：$schema" }
        val envelope = gson.fromJson(root, LedgerBackupEnvelope::class.java)
        require(envelope.payload.transactions.size <= 1_000_000) { "备份记录数量异常" }
        require(envelope.checksumSha256 == sha256(gson.toJson(envelope.payload))) { "备份校验失败，文件可能已损坏" }
        return envelope
    }

    private fun createBackupJson(context: Context): String {
        val db = AppDatabase.getDatabase(context)
        val payload = runBlocking {
            LedgerBackupPayload(
                transactions = db.transactionDao().getAllForBackup().map { it.toDto() },
                categories = db.categoryDao().getAll().map { it.toDto() },
                channels = db.channelDao().getAll().map { it.toDto() }
            )
        }
        val previous = readLatestPrivate(context)?.let { runCatching { decodeEnvelope(it) }.getOrNull() }
        val now = System.currentTimeMillis()
        val journal = buildJournal(previous, payload, now)
        return encodeEnvelope(payload, journal, now, BuildConfig.VERSION_NAME)
    }

    private fun buildJournal(
        previous: LedgerBackupEnvelope?,
        current: LedgerBackupPayload,
        now: Long
    ): List<LedgerJournalEntry> {
        val oldEntries = previous?.payload?.transactions.orEmpty().associateBy { entityKey(it) }
        val newEntries = current.transactions.associateBy { entityKey(it) }
        val history = previous?.journal.orEmpty().toMutableList()
        var sequence = (history.maxOfOrNull { it.sequence } ?: 0L) + 1L

        newEntries.forEach { (key, value) ->
            if (oldEntries[key] != value) {
                history += LedgerJournalEntry(sequence++, now, LedgerJournalEntry.UPSERT, key, value)
            }
        }
        (oldEntries.keys - newEntries.keys).forEach { key ->
            history += LedgerJournalEntry(sequence++, now, LedgerJournalEntry.DELETE, key)
        }
        return history.takeLast(MAX_JOURNAL_ENTRIES)
    }

    private fun restoreJson(context: Context, json: String): LedgerRestoreReport {
        val envelope = decodeEnvelope(json)
        val db = AppDatabase.getDatabase(context)
        var inserted = 0
        var updated = 0
        var skipped = 0
        runBlocking {
            db.withTransaction {
                envelope.payload.categories.forEach { db.categoryDao().insert(it.toEntity()) }
                envelope.payload.channels.forEach { db.channelDao().insert(it.toEntity()) }

                val localRecords = db.transactionDao().getAllForBackup()
                val existing = localRecords.associateBy { recordKey(it) }.toMutableMap()
                val legacyExisting = localRecords.associateBy {
                    entityKey(it.toDto().copy(stableId = null))
                }.toMutableMap()
                envelope.payload.transactions.forEach { dto ->
                    require(dto.amount.isFinite() && dto.amount >= 0.0) { "备份包含非法金额" }
                    require(dto.timestamp >= 0L) { "备份包含非法时间" }
                    val key = entityKey(dto)
                    val fallbackKey = entityKey(dto.copy(stableId = null))
                    val incomingStableId = dto.stableId.orEmpty()
                    val allowLegacyFallback = incomingStableId.isBlank() || incomingStableId.startsWith("legacy-")
                    val local = existing[key] ?: if (allowLegacyFallback) legacyExisting[fallbackKey] else null
                    when {
                        local == null -> {
                            val entity = dto.toEntity(id = 0)
                            val id = db.transactionDao().insert(entity)
                            val saved = entity.copy(id = id)
                            existing[key] = saved
                            legacyExisting[fallbackKey] = saved
                            inserted++
                        }
                        local.toDto().copy(sourceId = dto.sourceId, stableId = dto.stableId) == dto -> skipped++
                        else -> {
                            // A migrated legacy file may match an already-upgraded row by content.
                            // Preserve that row's UUID instead of replacing it with the legacy ID.
                            val entity = dto.toEntity(id = local.id).copy(stableId = local.stableId)
                            db.transactionDao().update(entity)
                            existing[key] = entity
                            legacyExisting[fallbackKey] = entity
                            updated++
                        }
                    }
                }
            }
        }
        // Normalize legacy imports immediately so every local generation has a checksum and version.
        val normalized = encodeEnvelope(
            envelope.payload,
            envelope.journal,
            System.currentTimeMillis(),
            BuildConfig.VERSION_NAME
        )
        writePrivateGeneration(context, normalized)
        return LedgerRestoreReport(
            inserted = inserted,
            updated = updated,
            skipped = skipped,
            categories = envelope.payload.categories.size,
            channels = envelope.payload.channels.size,
            sourceSchema = envelope.schemaVersion
        )
    }

    private fun entityKey(dto: TransactionBackupDto): String = sha256(
        if (dto.stableId.orEmpty().isNotBlank()) "stable:${dto.stableId}" else
        listOf(
            dto.type,
            "%.2f".format(Locale.US, dto.amount),
            dto.timestamp.toString(),
            dto.source,
            dto.packageName.orEmpty(),
            dto.rawText.orEmpty(),
            dto.createdAt.toString()
        ).joinToString("\u001f")
    )

    private fun recordKey(record: TransactionRecord): String = entityKey(record.toDto())

    private fun privateDir(context: Context): File = File(context.filesDir, "ledger_backups").apply { mkdirs() }
    private fun latestFile(context: Context): File = File(privateDir(context), LATEST_NAME)
    private fun journalFile(context: Context): File = File(privateDir(context), JOURNAL_NAME)

    private fun writePrivateGeneration(context: Context, json: String) {
        val dir = privateDir(context)
        val latest = latestFile(context)
        val temp = File(dir, "$LATEST_NAME.tmp")
        temp.writeText(json, Charsets.UTF_8)
        if (!temp.renameTo(latest)) {
            temp.copyTo(latest, overwrite = true)
            temp.delete()
        }

        val envelope = decodeEnvelope(json)
        journalFile(context).writeText(gson.toJson(envelope.journal), Charsets.UTF_8)
        File(dir, "generation-${envelope.exportedAt}.json").writeText(json, Charsets.UTF_8)
        dir.listFiles { file -> file.name.startsWith("generation-") && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_PRIVATE_GENERATIONS)
            ?.forEach { it.delete() }
    }

    private fun readLatestPrivate(context: Context): String? = runCatching {
        latestFile(context).takeIf { it.exists() }?.readText(Charsets.UTF_8)
    }.getOrNull()

    private fun writeImmutablePublicSnapshot(context: Context, json: String): Boolean {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "smartledger-$stamp.json"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$PUBLIC_DIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            runCatching {
                resolver.openOutputStream(uri, "w")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error("无法写入公共备份")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_DIR)
            runCatching { dir.mkdirs(); File(dir, name).writeText(json); true }.getOrDefault(false)
        }
    }

    /** Only finds files owned by the current installation; cross-install import must use SAF. */
    private fun readNewestOwnedPublic(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PUBLIC_DIR)
            return dir.listFiles { file -> file.name.startsWith("smartledger-") && file.extension == "json" }
                ?.maxByOrNull { it.lastModified() }?.readText()
        }
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DATE_ADDED)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("Download/$PUBLIC_DIR/", "smartledger-%.json")
        return runCatching {
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString())
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
        }.getOrNull()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun TransactionRecord.toDto() = TransactionBackupDto(
        sourceId = id, stableId = stableId.orEmpty(), type = type, amount = amount, categoryName = categoryName,
        channelName = channelName, paymentMethod = paymentMethod, merchant = merchant, note = note,
        timestamp = timestamp, source = source, rawText = rawText, packageName = packageName,
        status = status, isDeleted = isDeleted, deletedAt = deletedAt, latitude = latitude,
        longitude = longitude, locationName = locationName, aiConfidence = aiConfidence, createdAt = createdAt
    )

    private fun TransactionBackupDto.toEntity(id: Long) = TransactionRecord(
        id = id, stableId = stableId.orEmpty().ifBlank { UUID.randomUUID().toString() },
        type = type, amount = amount, categoryName = categoryName, channelName = channelName,
        paymentMethod = paymentMethod, merchant = merchant, note = note, timestamp = timestamp,
        source = source, rawText = rawText, packageName = packageName, status = status,
        isDeleted = isDeleted, deletedAt = deletedAt, latitude = latitude, longitude = longitude,
        locationName = locationName, aiConfidence = aiConfidence, createdAt = createdAt
    )

    private fun Category.toDto() = CategoryBackupDto(
        name, type, color, iconName, sortOrder, isDefault, parentName, level, createdAt
    )

    private fun CategoryBackupDto.toEntity() = Category(
        id = 0, name = name, type = type, color = color, iconName = iconName, sortOrder = sortOrder,
        isDefault = isDefault, parentName = parentName, level = level, createdAt = createdAt
    )

    private fun PaymentChannel.toDto() = ChannelBackupDto(name, iconName, isDefault, sortOrder, createdAt)

    private fun ChannelBackupDto.toEntity() = PaymentChannel(
        id = 0, name = name, iconName = iconName, isDefault = isDefault, sortOrder = sortOrder, createdAt = createdAt
    )
}

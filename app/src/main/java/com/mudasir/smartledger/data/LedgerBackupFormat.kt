package com.mudasir.smartledger.data

/**
 * SmartLedger's durable interchange format.
 *
 * These DTOs intentionally do not reuse Room entities. Database columns may be renamed or
 * reorganised in future versions while this wire format remains backwards compatible.
 */
data class LedgerBackupEnvelope(
    val format: String = FORMAT,
    val schemaVersion: Int = CURRENT_SCHEMA,
    val exportedAt: Long,
    val appVersion: String,
    val payload: LedgerBackupPayload,
    val journal: List<LedgerJournalEntry> = emptyList(),
    val checksumSha256: String
) {
    companion object {
        const val FORMAT = "smartledger-ledger"
        const val CURRENT_SCHEMA = 1
    }
}

data class LedgerBackupPayload(
    val transactions: List<TransactionBackupDto> = emptyList(),
    val categories: List<CategoryBackupDto> = emptyList(),
    val channels: List<ChannelBackupDto> = emptyList()
)

data class LedgerJournalEntry(
    val sequence: Long,
    val recordedAt: Long,
    val operation: String,
    val entityKey: String,
    val transaction: TransactionBackupDto? = null
) {
    companion object {
        const val UPSERT = "UPSERT"
        const val DELETE = "DELETE"
    }
}

data class TransactionBackupDto(
    val sourceId: Long = 0,
    val stableId: String? = null,
    val type: String = TransactionRecord.TYPE_EXPENSE,
    val amount: Double = 0.0,
    val categoryName: String = "",
    val channelName: String = "",
    val paymentMethod: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    val timestamp: Long = 0,
    val source: String = TransactionRecord.SOURCE_MANUAL,
    val rawText: String? = null,
    val packageName: String? = null,
    val status: String = TransactionRecord.STATUS_PENDING,
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val aiConfidence: Float? = null,
    val createdAt: Long = 0
)

data class CategoryBackupDto(
    val name: String,
    val type: String,
    val color: String,
    val iconName: String? = null,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    val parentName: String? = null,
    val level: Int = 1,
    val createdAt: Long = 0
)

data class ChannelBackupDto(
    val name: String,
    val iconName: String? = null,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = 0
)

data class LedgerRestoreReport(
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    val categories: Int,
    val channels: Int,
    val sourceSchema: Int
) {
    val total: Int get() = inserted + updated + skipped
}

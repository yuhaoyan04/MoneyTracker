package com.mudasir.smartledger.util

import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Expense
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 把 v8 以前的 expenses 历史合并进新版首页使用的 transactions 表。 */
object LegacyTransactionImporter {
    data class Result(val imported: Int, val skipped: Int)
    private val importMutex = Mutex()

    suspend fun importExisting(db: AppDatabase): Result =
        importExpenses(db, db.expenseDao().getAllRaw())

    suspend fun importExpenses(db: AppDatabase, expenses: List<Expense>): Result = importMutex.withLock {
        if (expenses.isEmpty()) return@withLock Result(0, 0)
        val existingKeys = db.transactionDao().getAllForBackup()
            .mapTo(mutableSetOf()) { key(it.type, it.amount, it.timestamp, it.merchant, it.note) }
        val records = mutableListOf<TransactionRecord>()
        var skipped = 0
        expenses.sortedBy { it.date }.forEach { old ->
            val signature = key(
                TransactionRecord.TYPE_EXPENSE, old.amount, old.date,
                old.title.takeIf { it.isNotBlank() }, old.description.takeIf { it.isNotBlank() }
            )
            if (!existingKeys.add(signature)) {
                skipped++
            } else {
                records += toTransaction(old)
            }
        }
        if (records.isNotEmpty()) db.transactionDao().insertAll(records)
        return Result(records.size, skipped)
    }

    private fun key(type: String, amount: Double, timestamp: Long, merchant: String?, note: String?): String =
        "$type|${java.lang.Double.doubleToLongBits(amount)}|$timestamp|${merchant.orEmpty()}|${note.orEmpty()}"

    fun toTransaction(old: Expense): TransactionRecord = TransactionRecord(
        type = TransactionRecord.TYPE_EXPENSE,
        amount = old.amount,
        categoryName = "其他",
        channelName = "其他",
        merchant = old.title.takeIf { it.isNotBlank() },
        note = old.description.takeIf { it.isNotBlank() },
        timestamp = old.date,
        source = TransactionRecord.SOURCE_MANUAL,
        rawText = "legacy_expense:${old.id}:${old.date}:${old.amount}",
        status = TransactionRecord.STATUS_CONFIRMED,
        isDeleted = old.isDeleted,
        deletedAt = old.deletedAt,
        createdAt = old.date
    )
}

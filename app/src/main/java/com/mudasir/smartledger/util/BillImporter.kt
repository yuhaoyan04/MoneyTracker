package com.mudasir.smartledger.util

import android.content.Context
import android.util.Log
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.ml.PersonalTagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 账单导入：把微信/支付宝官方账单合并进本地账本。
 *
 * 两段式：
 * 1) [plan] 干跑预检：统计与账本的关系（精确重复 / 可合并待确认 / 全新），
 *    并给出重复样例 —— 让用户在导入前看清「跳过」到底是跳过了什么
 * 2) [execute] 执行导入：
 *    - 去重模式：指纹精确命中跳过；±2 分钟同额同类合并待确认记录；其余新增
 *    - 全量模式：全部作为新记录插入（用户明确要求时）
 */
object BillImporter {

    /** 对账窗口：自动抓取时间与账单交易时间的合理偏差。 */
    private const val RECONCILE_WINDOW_MS = 120_000L

    data class Plan(
        val total: Int,
        val exactDups: Int,
        val pendingMatches: Int,
        val fresh: Int,
        val periodStart: Long,
        val periodEnd: Long,
        /** 重复样例（最多 3 条），形如「已有: 01-15 12:30 肯德基 ¥39.90」。 */
        val dupSamples: List<String>
    )

    data class Report(
        val inserted: Int,
        val mergedPending: Int,
        val skippedExisting: Int,
        val sourceChannel: String
    )

    private data class ConfirmedIndex(
        val keyToRecord: Map<String, TransactionRecord>
    )

    suspend fun plan(
        context: Context,
        bills: List<BillImportParser.ParsedBill>,
        sourceChannel: String
    ): Plan = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).transactionDao()
        val minTs = bills.minOf { it.timestamp } - RECONCILE_WINDOW_MS
        val maxTs = bills.maxOf { it.timestamp } + RECONCILE_WINDOW_MS + 1
        val confirmed = dao.getInRange(minTs, maxTs)
        val pending = dao.getPendingInRange(minTs, maxTs)

        val keyToRecord = confirmed.associateBy { dedupKeyOf(it.type, it.amount, it.timestamp) }
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        var exact = 0; var pend = 0; var fresh = 0
        val samples = mutableListOf<String>()

        for (bill in bills) {
            val existing = keyToRecord[dedupKeyOf(bill.type, bill.amount, bill.timestamp)]
            when {
                existing != null -> {
                    exact++
                    if (samples.size < 3) {
                        samples.add(
                            "已有: ${sdf.format(Date(existing.timestamp))} " +
                                "${existing.merchant ?: existing.categoryName} ¥${"%.2f".format(existing.amount)}"
                        )
                    }
                }
                pending.any {
                    it.type == bill.type && abs(it.amount - bill.amount) < 0.01 &&
                        abs(it.timestamp - bill.timestamp) <= RECONCILE_WINDOW_MS
                } -> pend++
                else -> fresh++
            }
        }
        Plan(
            total = bills.size,
            exactDups = exact,
            pendingMatches = pend,
            fresh = fresh,
            periodStart = bills.minOf { it.timestamp },
            periodEnd = bills.maxOf { it.timestamp },
            dupSamples = samples
        )
    }

    suspend fun execute(
        context: Context,
        bills: List<BillImportParser.ParsedBill>,
        sourceChannel: String,
        skipDuplicates: Boolean
    ): Report = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.transactionDao()
        var inserted = 0
        var mergedPending = 0
        var skippedExisting = 0

        val minTs = bills.minOf { it.timestamp } - RECONCILE_WINDOW_MS
        val maxTs = bills.maxOf { it.timestamp } + RECONCILE_WINDOW_MS + 1

        val confirmedKeys = dao.getInRange(minTs, maxTs)
            .mapTo(mutableSetOf()) { dedupKeyOf(it.type, it.amount, it.timestamp) }
        val pendingRecords = dao.getPendingInRange(minTs, maxTs).toMutableList()

        for (bill in bills) {
            try {
                if (skipDuplicates) {
                    // 1) 指纹精确命中 → 账本已有
                    if (bill.dedupKey in confirmedKeys) { skippedExisting++; continue }

                    // 2) 与待确认记录合并（同类型 + 同金额 + 时间在窗口内）
                    val matchIdx = pendingRecords.indexOfFirst { p ->
                        p.type == bill.type &&
                            abs(p.amount - bill.amount) < 0.01 &&
                            abs(p.timestamp - bill.timestamp) <= RECONCILE_WINDOW_MS
                    }
                    if (matchIdx >= 0) {
                        val pending = pendingRecords.removeAt(matchIdx)
                        val suggestion = PersonalTagger.recommendWithConfidence(
                            context, bill.type, bill.timestamp, bill.amount,
                            sourceChannel, bill.merchant, bill.note, null
                        )
                        dao.update(
                            pending.copy(
                                status = TransactionRecord.STATUS_CONFIRMED,
                                categoryName = pending.categoryName.ifBlank { suggestion.category },
                                aiConfidence = pending.aiConfidence ?: suggestion.confidence,
                                merchant = pending.merchant ?: bill.merchant,
                                paymentMethod = pending.paymentMethod ?: bill.paymentMethod
                            )
                        )
                        PersonalTagger.learn(
                            context, bill.type, bill.timestamp, bill.amount,
                            sourceChannel, pending.categoryName.ifBlank { suggestion.category }, bill.merchant
                        )
                        mergedPending++
                        continue
                    }
                }

                // 3) 新增（全量模式下重复项也走这里）
                val suggestion = PersonalTagger.recommendWithConfidence(
                    context, bill.type, bill.timestamp, bill.amount,
                    sourceChannel, bill.merchant, bill.note, null
                )
                dao.insert(
                    TransactionRecord(
                        type = bill.type,
                        amount = bill.amount,
                        categoryName = suggestion.category,
                        channelName = sourceChannel,
                        paymentMethod = bill.paymentMethod,
                        merchant = bill.merchant,
                        note = bill.note,
                        timestamp = bill.timestamp,
                        source = TransactionRecord.SOURCE_IMPORT,
                        status = TransactionRecord.STATUS_CONFIRMED,
                        aiConfidence = suggestion.confidence
                    )
                )
                PersonalTagger.learn(
                    context, bill.type, bill.timestamp, bill.amount,
                    sourceChannel, suggestion.category, bill.merchant
                )
                confirmedKeys.add(bill.dedupKey)
                inserted++
            } catch (e: Exception) {
                Log.w("BillImporter", "import row failed", e)
                skippedExisting++
            }
        }
        Report(inserted, mergedPending, skippedExisting, sourceChannel)
    }

    private fun dedupKeyOf(type: String, amount: Double, timestamp: Long): String =
        "$type|${"%.2f".format(amount)}|${timestamp / 1000}"
}

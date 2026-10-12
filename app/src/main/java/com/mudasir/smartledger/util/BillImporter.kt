package com.mudasir.smartledger.util

import android.content.Context
import android.util.Log
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.ml.PersonalTagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 账单导入：把微信/支付宝官方账单合并进本地账本。
 *
 * 三级对账策略：
 * 1) 指纹精确命中（类型+金额+秒级时间）→ 账本已有，跳过
 * 2) 与「待确认」记录对账合并：同类型+同金额+±2 分钟（通知抓取时间与账单
 *    交易时间常差数秒）→ 确认该待确认记录，不重复插入
 * 3) 都不匹配 → 作为新记录插入（AI 预分类 + 打标学习）
 *
 * 历史账单会交给打标器学习——这是冷启动最好的养料。
 */
object BillImporter {

    /** 对账窗口：自动抓取时间与账单交易时间的合理偏差。 */
    private const val RECONCILE_WINDOW_MS = 120_000L

    data class Report(
        val inserted: Int,
        val mergedPending: Int,
        val skippedExisting: Int,
        val sourceChannel: String
    )

    suspend fun import(
        context: Context,
        bills: List<BillImportParser.ParsedBill>,
        sourceChannel: String
    ): Report = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.transactionDao()
        var inserted = 0
        var mergedPending = 0
        var skippedExisting = 0

        val minTs = bills.minOf { it.timestamp } - RECONCILE_WINDOW_MS
        val maxTs = bills.maxOf { it.timestamp } + RECONCILE_WINDOW_MS + 1

        // 已确认记录：指纹精确去重
        val confirmedKeys = dao.getInRange(minTs, maxTs)
            .mapTo(mutableSetOf()) { dedupKeyOf(it.type, it.amount, it.timestamp) }
        // 待确认记录：对账合并候选
        val pendingRecords = dao.getPendingInRange(minTs, maxTs).toMutableList()

        for (bill in bills) {
            try {
                // 1) 精确命中 → 已存在
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

                // 3) 全新记录
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

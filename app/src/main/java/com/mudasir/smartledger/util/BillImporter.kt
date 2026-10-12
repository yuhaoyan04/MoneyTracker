package com.mudasir.smartledger.util

import android.content.Context
import android.util.Log
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.ml.PersonalTagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 账单导入：把微信/支付宝官方账单合并进本地账本。
 *
 * - 指纹去重：类型 + 金额 + 秒级时间戳，与账单时间范围内已有记录比对
 * - 导入即确认（官方数据可信），并交给打标器学习——历史账单是冷启动最好的养料
 * - 单事务批量插入，报告新增/跳过数量
 */
object BillImporter {

    data class Report(val inserted: Int, val skipped: Int, val sourceChannel: String)

    suspend fun import(
        context: Context,
        bills: List<BillImportParser.ParsedBill>,
        sourceChannel: String
    ): Report = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.transactionDao()
        var inserted = 0
        var skipped = 0

        val minTs = bills.minOf { it.timestamp }
        val maxTs = bills.maxOf { it.timestamp }
        val existingKeys = dao.getInRange(minTs, maxTs + 1)
            .mapTo(mutableSetOf()) { "${it.type}|${"%.2f".format(it.amount)}|${it.timestamp / 1000}" }

        for (bill in bills) {
            if (bill.dedupKey in existingKeys) { skipped++; continue }
            try {
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
                existingKeys.add(bill.dedupKey)
                inserted++
            } catch (e: Exception) {
                Log.w("BillImporter", "import row failed", e)
                skipped++
            }
        }
        Report(inserted, skipped, sourceChannel)
    }
}

package com.mudasir.smartledger.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 银行短信接收器：抓取银行下发的「消费/入账」短信，解析后写入待确认收件箱。
 *
 * 仅处理疑似银行交易短信（含金额 + 「尾号/银行/消费/到账」等关键词），
 * 避免把普通短信当作交易。
 *
 * 需 RECV_SMS 权限，并设为默认短信应用才能在部分机型拦截；此处仅被动接收。
 */
class BankSmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bankSignalKeywords = listOf("尾号", "银行", "信用社", "消费", "支出", "入账", "到账", "代发", "扣款", "退款")

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        if (body.isBlank()) return

        // 快速过滤非交易短信
        val hasMoney = body.contains("¥") || body.contains("￥") || body.contains("元")
        val hasBankSignal = bankSignalKeywords.any { body.contains(it) }
        if (!hasMoney || !hasBankSignal) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress
        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        val parsed = TransactionParser.parse(
            text = body,
            source = TransactionRecord.SOURCE_CAPTURE_SMS,
            channelHint = "银行卡",
            timestamp = timestamp
        ) ?: return

        scope.launch {
            AppDatabase.getDatabase(context.applicationContext).transactionDao().insert(
                TransactionRecord(
                    type = parsed.type,
                    amount = parsed.amount,
                    channelName = parsed.channelName,
                    paymentMethod = parsed.paymentMethod,
                    merchant = parsed.merchant,
                    note = sender?.let { "来自 $it" },
                    rawText = body,
                    source = TransactionRecord.SOURCE_CAPTURE_SMS,
                    status = TransactionRecord.STATUS_PENDING,
                    timestamp = timestamp
                )
            )
        }
    }
}

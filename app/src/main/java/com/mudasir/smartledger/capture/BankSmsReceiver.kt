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

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        if (body.isBlank()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress
        if (!BankSmsGate.shouldCapture(sender, body)) return
        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        val parsed = TransactionParser.parse(
            text = body,
            source = TransactionRecord.SOURCE_CAPTURE_SMS,
            channelHint = "银行卡",
            timestamp = timestamp
        ) ?: return

        val pendingResult = goAsync()
        scope.launch {
            try {
            // 后台协程绝不抛异常 —— 任何失败只记日志，保住前台体验
            runCatching {
                val dao = AppDatabase.getDatabase(context.applicationContext).transactionDao()
                // 短信到达即记录支付时刻位置（主动新鲜定位，等待最多 8s）
                val place = com.mudasir.smartledger.util.LocationHelper.freshPlace(context.applicationContext)
                if (dao.countExactCapture(
                        TransactionRecord.SOURCE_CAPTURE_SMS,
                        null,
                        body,
                        timestamp,
                        timestamp
                    ) > 0
                ) return@launch
                dao.insert(
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
                        timestamp = timestamp,
                        latitude = place?.latitude,
                        longitude = place?.longitude,
                        locationName = place?.name,
                        // 短信无 AI 建议入口，此处同步给打标建议 + 置信度
                        categoryName = com.mudasir.smartledger.ml.PersonalTagger.recommend(
                            context.applicationContext, parsed.type, timestamp, parsed.amount,
                            parsed.channelName, parsed.merchant, body, place?.name
                        )
                    )
                )
                runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(context.applicationContext) }
                com.mudasir.smartledger.util.PendingNotifier.update(context.applicationContext)
                // 想好再买：超过门槛的大额消费 → 立即冷静提醒
                if (parsed.type == TransactionRecord.TYPE_EXPENSE && parsed.amount > 0) {
                    val threshold = GuardThreshold.read(context.applicationContext
                        .getSharedPreferences("guard_prefs", Context.MODE_PRIVATE))
                    if (CheckoutPageParser.exceedsThreshold(parsed.amount, threshold)) {
                        com.mudasir.smartledger.util.PurchaseGuardNotifier.notifyBigSpend(
                            context.applicationContext, parsed.amount, parsed.merchant ?: "银行卡消费"
                        )
                    }
                }
            }.onFailure { android.util.Log.w("BankSmsReceiver", "sms capture failed", it) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

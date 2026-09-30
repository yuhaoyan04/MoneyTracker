package com.mudasir.smartledger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 通知监听服务：自动抓取微信 / 支付宝 / 淘宝 / 京东 / 银行 App 的支付通知。
 *
 * 抓取的交易以 status = PENDING 写入「待确认收件箱」，由用户核对后确认，
 * 既保证不遗漏，又允许用户修正分类/渠道/金额。
 *
 * 使用前需用户在系统「通知使用权」中授权本应用。
 */
class LedgerNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentHashes = LinkedHashMap<Long, String>()

    // 监听这些包名的通知。null 表示来源未知，但仍尝试解析。
    private val watchedPackages = setOf(
        "com.tencent.mm",           // 微信
        "com.eg.android.AlipayGphone", // 支付宝
        "com.taobao.taobao",        // 淘宝
        "com.jingdong.app.mall",    // 京东
        "com.jd.jrapp",             // 京东金融
        "com.eg.android.AlipayGphone.rc"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        val combined = listOf(title, sub, text, big).filter { it.isNotBlank() }.joinToString(" | ")
        if (combined.isBlank()) return

        // 快速过滤：非监听包且无金额关键字则跳过，减少噪声
        val watched = pkg in watchedPackages
        val hasMoneySignal = combined.contains("¥") || combined.contains("￥") || combined.contains("元") ||
            combined.contains("支付") || combined.contains("收款") || combined.contains("消费") ||
            combined.contains("付款") || combined.contains("扣款") || combined.contains("到账")
        if (!watched && !hasMoneySignal) return

        val parsed = TransactionParser.parse(
            text = combined,
            source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
            packageName = pkg,
            timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        )

        scope.launch {
            val dao = AppDatabase.getDatabase(this@LedgerNotificationListener).transactionDao()
            if (parsed != null) {
                if (isDuplicate(parsed)) return@launch
                val rec = parsed.toRecord()
                // 个性化打标冷启动：抓取时即给一个分类建议，减少用户手动分类负担
                val suggested = com.mudasir.smartledger.ml.PersonalTagger.recommend(
                    applicationContext,
                    rec.type, rec.timestamp, rec.amount, rec.channelName, rec.merchant, rec.rawText
                )
                dao.insert(rec.copy(categoryName = rec.categoryName.ifBlank { suggested }))
            } else if (watched && hasMoneySignal) {
                // 兜底：监听包内疑似支付但解析失败 —— 仍以原文入库，确保不遗漏
                dao.insert(
                    TransactionRecord(
                        type = TransactionRecord.TYPE_EXPENSE,
                        amount = 0.0,
                        channelName = TransactionParser.channelForPackage(pkg) ?: "其他",
                        merchant = title.takeIf { it.isNotBlank() },
                        rawText = combined,
                        source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
                        packageName = pkg,
                        status = TransactionRecord.STATUS_PENDING,
                        timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun isDuplicate(parsed: ParsedTransaction): Boolean {
        val now = System.currentTimeMillis()
        val hash = "${parsed.type}|${parsed.amount}|${parsed.channelName}|${parsed.timestamp / 60000}"
        // 清理 2 分钟以上的旧记录
        val cutoff = now - 120_000
        val it = recentHashes.entries.iterator()
        while (it.hasNext()) {
            if (it.next().key < cutoff) it.remove() else break
        }
        return if (recentHashes.values.contains(hash)) true
        else { recentHashes[now] = hash; false }
    }

    private fun ParsedTransaction.toRecord() = TransactionRecord(
        type = type,
        amount = amount,
        channelName = channelName,
        paymentMethod = paymentMethod,
        merchant = merchant,
        rawText = rawText,
        source = source,
        packageName = packageName,
        status = TransactionRecord.STATUS_PENDING,
        timestamp = timestamp
    )
}

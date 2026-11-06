package com.mudasir.smartledger.capture

import android.app.Notification
import android.content.Context
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

    // 渠道名集合 —— 如果 merchant 字段只是渠道名（如"微信支付"），则视为缺失商户
    private val channelNames = setOf(
        "微信支付", "微信", "支付宝", "京东", "淘宝", "天猫", "银行卡", "银行",
        "信用卡", "花呗", "借呗", "云闪付", "数字人民币", "现金", "其他", "android"
    )

    // 严格白名单（v2.37.0 收紧，用户指令）：只采集 微信 / 支付宝 / 云闪付（银行卡
    // 支付工具）的通知 —— 淘宝/京东/美团/拼多多等购物 App 推送是广告金额噪音的
    // 主要来源，全部从源头切断。银行卡支付走短信通道（BankSmsReceiver）。
    // 白名单与渠道归类见 CaptureWhitelist（纯逻辑、可单元测试）。
    private val watchedPackages = CaptureWhitelist.packages

    // 完成表述词、营销黑名单、双信号门已提取至 NotificationGate（纯逻辑、可单元测试）：
    // v2.36.0 起 Gate3 改为「必备完成词」，广告金额误判的根治见 NotificationGate 文档。


    /** 判断 merchant 字段是否为空或只是渠道名（需要跨 App 补全） */
    private fun needsMerchant(merchant: String?): Boolean {
        if (merchant.isNullOrBlank()) return true
        val lower = merchant.lowercase()
        return channelNames.any { lower.contains(it.lowercase()) }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 服务重连（开机/重新授权）→ 自愈常驻待确认通知
        scope.launch {
            runCatching { com.mudasir.smartledger.util.PendingNotifier.update(applicationContext) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (!CaptureWhitelist.isWatched(pkg)) return
        CaptureDiagnostics.record(this, if (pkg == "com.tencent.mm") CaptureDiagnostics.Stage.WECHAT_RECEIVED else CaptureDiagnostics.Stage.OTHER_RECEIVED)
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        // MessagingStyle 通知（微信服务号消息常用）：文本藏在 EXTRA_MESSAGES 里
        val msgText = runCatching {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ?.let { Notification.MessagingStyle.Message.getMessagesFromBundleArray(it) }
                ?.mapNotNull { it.text?.toString() }
                ?.joinToString(" ")
                .orEmpty()
        }.getOrDefault("")

        val combined = listOf(title, sub, text, big, msgText).filter { it.isNotBlank() }.joinToString(" | ")
        if (combined.isBlank()) { CaptureDiagnostics.record(this, CaptureDiagnostics.Stage.CONTENT_REJECTED); return }

        // === Gate 1: 严格白名单 —— 只处理支付/购物 App 的通知 ===
        // 微博、新闻、银行 App 非通知源（银行走短信）等全部忽略
        if (!NotificationGate.isTrustedSender(pkg, title)) { CaptureDiagnostics.record(this, CaptureDiagnostics.Stage.SENDER_REJECTED); return }

        // === Gates 2+3: 双信号门 + 完成词主门（逻辑提取至 NotificationGate，可单元测试） ===
        // v2.36.0：广告金额误判收支的根治 —— 无「支付成功/已扣款/已到账」等完成表述
        // 一律不采集（漏采可补记，广告污染需逐条清理）。
        if (!NotificationGate.shouldCapture(combined)) { CaptureDiagnostics.record(this, CaptureDiagnostics.Stage.CONTENT_REJECTED); return }

        val captureTime = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val parsed = TransactionParser.parse(
            text = combined,
            source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
            packageName = pkg,
            timestamp = captureTime
        )
        CaptureDiagnostics.record(this, if (parsed != null) CaptureDiagnostics.Stage.PARSED else CaptureDiagnostics.Stage.FALLBACK)

        scope.launch {
            // 后台协程绝不抛异常 —— 任何失败只记日志，保住前台体验
            runCatching {
            val dao = AppDatabase.getDatabase(this@LedgerNotificationListener).transactionDao()
            if (parsed != null) {
                // 内存去重会在进程重启后丢失；先用原始通知做持久化重放保护。
                if (dao.countExactCapture(
                        TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
                        pkg,
                        parsed.rawText,
                        parsed.timestamp,
                        parsed.timestamp
                    ) > 0
                ) { CaptureDiagnostics.record(applicationContext, CaptureDiagnostics.Stage.DUPLICATE); return@launch }
                val rec = parsed.toRecord()
                // Equal amounts and nearby timestamps do not prove two notices are the same transaction.
                val enrichedMerchant = rec.merchant
                // 支付时刻定位：主动请求一次新鲜定位（等待最多 8s，超时回退 lastKnown）
                // 需要后台定位权限，见设置 → 自动抓取 → 支付时记录位置
                val place = runCatching {
                    com.mudasir.smartledger.util.LocationHelper.freshPlace(applicationContext)
                }.getOrNull()
                val suggestion = com.mudasir.smartledger.ml.PersonalTagger.recommendWithConfidence(
                    applicationContext,
                    rec.type, rec.timestamp, rec.amount, rec.channelName, enrichedMerchant, rec.rawText,
                    place?.name
                )
                dao.insert(rec.copy(
                    categoryName = rec.categoryName.ifBlank { suggestion.category },
                    merchant = enrichedMerchant,
                    latitude = place?.latitude,
                    longitude = place?.longitude,
                    locationName = place?.name,
                    aiConfidence = suggestion.confidence
                ))
                CaptureDiagnostics.record(applicationContext, CaptureDiagnostics.Stage.SAVED)
                runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(applicationContext) }
                // 有新的待确认交易 → 刷新常驻通知
                com.mudasir.smartledger.util.PendingNotifier.update(applicationContext)
                // 想好再买：超过门槛的大额消费 → 立即冷静提醒
                if (rec.type == TransactionRecord.TYPE_EXPENSE && rec.amount > 0) {
                    val threshold = GuardThreshold.read(getSharedPreferences("guard_prefs", Context.MODE_PRIVATE))
                    if (CheckoutPageParser.exceedsThreshold(rec.amount, threshold)) {
                        com.mudasir.smartledger.util.PurchaseGuardNotifier.notifyBigSpend(
                            applicationContext, rec.amount, enrichedMerchant ?: "支付"
                        )
                    }
                }
            } else {
                // 兜底：白名单内疑似支付但解析失败（门槛已过 NotificationGate）—— 仍以原文入库，确保不遗漏
                val fallbackMerchant = title.takeIf { it.isNotBlank() && !needsMerchant(title) }
                    ?: null
                if (dao.countExactCapture(
                        TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
                        pkg,
                        combined,
                        captureTime,
                        captureTime
                    ) > 0
                ) { CaptureDiagnostics.record(applicationContext, CaptureDiagnostics.Stage.DUPLICATE); return@launch }
                dao.insert(
                    TransactionRecord(
                        type = TransactionRecord.TYPE_EXPENSE,
                        amount = 0.0,
                        channelName = TransactionParser.channelForPackage(pkg) ?: "其他",
                        merchant = fallbackMerchant,
                        rawText = combined,
                        source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
                        packageName = pkg,
                        status = TransactionRecord.STATUS_PENDING,
                        timestamp = captureTime
                    )
                )
                CaptureDiagnostics.record(applicationContext, CaptureDiagnostics.Stage.SAVED)
                runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(applicationContext) }
                com.mudasir.smartledger.util.PendingNotifier.update(applicationContext)
            }
            }.onFailure { CaptureDiagnostics.record(applicationContext, CaptureDiagnostics.Stage.FAILED); android.util.Log.w("LedgerListener", "capture failed", it) }
        }
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

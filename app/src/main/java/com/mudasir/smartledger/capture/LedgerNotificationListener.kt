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
    private val recentHashes = LinkedHashMap<Long, Pair<String, String>>()

    // 商户关联缓冲区：记录最近解析的通知（含商户名），用于跨 App 商户补全
    private data class RecentCapture(
        val amount: Double,
        val merchant: String?,
        val pkg: String,
        val timestamp: Long
    )
    private val recentCaptures = mutableListOf<RecentCapture>()

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

    /** 在 60s 窗口内查找金额匹配、来源不同的通知，补全商户名 */
    private fun correlateMerchant(amount: Double, pkg: String, timestamp: Long): String? {
        val now = System.currentTimeMillis()
        synchronized(recentCaptures) {
            recentCaptures.removeAll { it.timestamp < now - 60_000 }
            return recentCaptures.find {
                kotlin.math.abs(it.amount - amount) < 0.01 &&
                it.pkg != pkg &&
                !needsMerchant(it.merchant)
            }?.merchant
        }
    }

    /** 将本次抓取存入缓冲区，供后续通知关联 */
    private fun storeCapture(amount: Double, merchant: String?, pkg: String, timestamp: Long) {
        synchronized(recentCaptures) {
            recentCaptures.add(RecentCapture(amount, merchant, pkg, timestamp))
        }
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
                ?.mapNotNull { (it as? Notification.MessagingStyle.Message)?.text?.toString() }
                ?.joinToString(" ")
                .orEmpty()
        }.getOrDefault("")

        val combined = listOf(title, sub, text, big, msgText).filter { it.isNotBlank() }.joinToString(" | ")
        if (combined.isBlank()) return

        // === Gate 1: 严格白名单 —— 只处理支付/购物 App 的通知 ===
        // 微博、新闻、银行 App 非通知源（银行走短信）等全部忽略
        if (!CaptureWhitelist.isWatched(pkg)) return
        if (!NotificationGate.isTrustedSender(pkg, title)) return

        // === Gates 2+3: 双信号门 + 完成词主门（逻辑提取至 NotificationGate，可单元测试） ===
        // v2.36.0：广告金额误判收支的根治 —— 无「支付成功/已扣款/已到账」等完成表述
        // 一律不采集（漏采可补记，广告污染需逐条清理）。
        if (!NotificationGate.shouldCapture(combined)) return

        val parsed = TransactionParser.parse(
            text = combined,
            source = TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
            packageName = pkg,
            timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        )

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
                        parsed.timestamp - 5 * 60_000,
                        parsed.timestamp + 5 * 60_000
                    ) > 0
                ) return@launch
                if (isDuplicate(parsed, pkg)) {
                    // 跨 App 去重：同一笔交易被多个 App 通知。
                    // 若本次通知带有商户名，尝试补全已入库记录的空商户。
                    if (!needsMerchant(parsed.merchant)) {
                        val cutoff = parsed.timestamp - 60_000
                        dao.updatePendingMerchant(parsed.merchant!!, parsed.amount, cutoff)
                    }
                    return@launch
                }
                // 跨通道去重（v2.36.0）：银行短信常比钱包通知晚 1-3 分钟，
                // 同一笔卡支付会同时产生「微信支付凭证通知 + 银行扣款短信」。
                // ① 共享注册表（通知↔无障碍↔短信 同进程）3 分钟窗口
                if (RecentCaptureRegistry.isDuplicate(parsed.type, parsed.amount, 180_000L)) {
                    return@launch
                }
                // ② DB 待确认池（跨进程重启兜底）：±3 分钟内已有同额待确认 → 同一笔
                if (dao.countPendingLike(
                        parsed.amount, parsed.amount,
                        parsed.timestamp - 180_000L, parsed.timestamp + 180_000L
                    ) > 0
                ) return@launch
                val rec = parsed.toRecord()
                // 商户关联：若当前通知缺少商户，从 60s 窗口内其他 App 的通知补全
                val enrichedMerchant = if (needsMerchant(rec.merchant)) {
                    correlateMerchant(rec.amount, pkg, rec.timestamp) ?: rec.merchant
                } else {
                    rec.merchant
                }
                // 存入缓冲区供后续通知关联
                storeCapture(rec.amount, enrichedMerchant, pkg, rec.timestamp)
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
                    ?: correlateMerchant(0.0, pkg, sbn.postTime)
                if (dao.countExactCapture(
                        TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
                        pkg,
                        combined,
                        (sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()) - 5 * 60_000,
                        (sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()) + 5 * 60_000
                    ) > 0
                ) return@launch
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
                        timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                )
                runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(applicationContext) }
            }
            }.onFailure { android.util.Log.w("LedgerListener", "capture failed", it) }
        }
    }

    /**
     * 去重策略：
     * - 跨 App（微信+银行等同时通知同一笔）→ 3 分钟窗口内去重
     * - 同 App 短窗口（≤20s，同一通知被系统重发/更新）→ 去重
     * - 同 App 长窗口（同分钟同金额的真实重复消费，如连买两瓶水）→ 保留
     */
    private fun isDuplicate(parsed: ParsedTransaction, pkg: String): Boolean {
        val now = System.currentTimeMillis()
        val hash = "${parsed.type}|${parsed.amount}|${parsed.timestamp / 60000}"
        val cutoff = now - 180_000
        recentHashes.entries.removeAll { it.key < cutoff }
        val dupFromOtherApp = recentHashes.entries.any { it.value.first == hash && it.value.second != pkg }
        val dupSameAppRecent = recentHashes.entries.any {
            it.key >= now - 20_000 && it.value.first == hash && it.value.second == pkg
        }
        return if (dupFromOtherApp || dupSameAppRecent) true
        else { recentHashes[now] = hash to pkg; false }
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

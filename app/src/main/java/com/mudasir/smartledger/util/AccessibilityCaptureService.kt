package com.mudasir.smartledger.util

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mudasir.smartledger.capture.RecentCaptureRegistry
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 增强模式（可选，无障碍）：在支付确认页面出现时（密码输入之前）读取订单
 * 金额，生成「订单候选」进入收件箱——覆盖 UsageStats 通道无法识别的
 * H5/内嵌收银台（如淘宝内直接唤起的微信/支付宝收银台变体）。
 *
 * 设计要点（吸取 v2.22 退役教训）：
 * - 只处理 WINDOW_STATE_CHANGED，单事件去抖 400ms，浅层遍历（≤25 层 / ≤120 文本）
 * - 强关键词门：页面必须同时含支付确认词 + 金额，否则直接放弃
 * - 与通知通道跨源去重（RecentCaptureRegistry），同一笔只记一次
 * - 任何异常只记日志，绝不崩溃（无障碍崩溃 = 系统自动关闭开关）
 * - 数据仅存本机
 */
class AccessibilityCaptureService : AccessibilityService() {

    companion object {
        private const val TAG = "A11yCapture"

        @Volatile
        var isRunning = false
            private set

        private const val POLL_DEBOUNCE_MS = 400L

        // 强确认词：命中说明用户正处在付款确认页
        private val confirmWords = listOf(
            "确认付款", "付款成功页", "立即支付", "确认支付", "收银台",
            "请输入支付密码", "向商家付款", "二维码付款", "付款码"
        )
        // 金额：¥/￥ 优先，兼容 N.NN元
        private val amountRegex = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")
        private val yuanRegex = Regex("""([0-9]{1,6}(?:\.[0-9]{1,2})?)\s*元""")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessAt = 0L
    private var lastCapturedKey = ""
    private var lastCapturedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        // 开机/重授权后自愈：待确认常驻通知刷新
        scope.launch {
            runCatching { PendingNotifier.update(applicationContext) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastProcessAt < POLL_DEBOUNCE_MS) return
        lastProcessAt = now
        handler.postDelayed({ runCatching { inspect() } }, 350)
    }

    private fun inspect() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val texts = mutableListOf<String>()
        collectTexts(root, texts, 0)
        if (texts.size < 2) return
        val combined = texts.joinToString(" ")

        // 强关键词门：页面必须含「确认付款类」表述，且不含「退款」
        val confirm = confirmWords.any { combined.contains(it) }
        if (!confirm || combined.contains("退款成功")) return

        val amount = extractAmount(combined) ?: return
        val key = "$pkg|$amount"
        val now = System.currentTimeMillis()
        // 90 秒内同一笔（同包同额）只记一次
        if (key == lastCapturedKey && now - lastCapturedAt < 90_000) return
        lastCapturedKey = key
        lastCapturedAt = now

        val type = if (combined.contains("收款成功")) TransactionRecord.TYPE_INCOME
        else TransactionRecord.TYPE_EXPENSE

        scope.launch {
            runCatching {
                val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
                // 跨通道去重①：通知/短信通道 90 秒窗口内已有同额同向待确认 → 跳过
                if (dao.countPendingLike(
                        amount, amount, now - 90_000, now + 90_000
                    ) > 0
                ) return@runCatching
                // 跨通道去重②：与通知通道共享的注册表（同额同向 90 秒视为同一笔）
                if (RecentCaptureRegistry.isDuplicate(type, amount)) return@runCatching
                val suggestion = com.mudasir.smartledger.ml.PersonalTagger.recommendWithConfidence(
                    applicationContext, type, now, amount, channelOf(pkg), null, combined.take(300), null
                )
                dao.insert(
                    TransactionRecord(
                        type = type,
                        amount = amount,
                        categoryName = suggestion.category,
                        channelName = channelOf(pkg),
                        merchant = null,
                        rawText = combined.take(400),
                        source = TransactionRecord.SOURCE_CAPTURE_ACCESSIBILITY,
                        packageName = pkg,
                        status = TransactionRecord.STATUS_PENDING,
                        timestamp = now,
                        aiConfidence = suggestion.confidence
                    )
                )
                PendingNotifier.update(applicationContext)
                // 同步触发想好再买浮层/通知（密码输入前的最后干预点）
                com.mudasir.smartledger.util.PurchaseGuardService.onExternalPayPage(applicationContext)
            }.onFailure { android.util.Log.w(TAG, "capture failed", it) }
        }
    }

    private fun channelOf(pkg: String): String = when (pkg) {
        "com.tencent.mm" -> "微信支付"
        "com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone.rc", "com.eg.android.AlipayGphone.lite" -> "支付宝"
        else -> "其他"
    }

    private fun extractAmount(text: String): Double? {
        val m = amountRegex.find(text) ?: yuanRegex.find(text) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 25 || out.size > 120) return
        node.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out, depth + 1) }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        scope.cancel()
        return super.onUnbind(intent)
    }
}

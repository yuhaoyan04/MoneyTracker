package com.mudasir.smartledger.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 无障碍服务：捕获「付款码 / 扫一扫」支付——这类支付发生时微信/支付宝在前台，
 * 系统会抑制其服务号通知的推送，通知监听通道天然收不到。
 *
 * 本服务在「支付成功结果页」出现时读取页面内容：
 * - 结果页特征：支付成功/付款成功/交易成功/转账成功 + 金额 + 「完成/返回商家」按钮
 *   （要求存在完成按钮是为了与聊天/账单页区分——翻看旧支付消息不会误抓）
 * - 同步捕获支付时刻位置 + AI 分类建议
 * - 与通知监听通道通过 RecentCaptureRegistry 跨通道去重
 *
 * 数据仅保存在本机，绝不上传。
 */
class PaymentAccessibilityService : AccessibilityService() {

    companion object {
        private const val WECHAT = "com.tencent.mm"
        private const val ALIPAY = "com.eg.android.AlipayGphone"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 只处理窗口切换（进入支付结果页是 Activity 切换）；内容变化（滚动聊天/账单）不处理
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != WECHAT && pkg != ALIPAY) return

        val now = System.currentTimeMillis()
        if (now - lastEventAt < 600) return  // 事件节流
        lastEventAt = now

        // 延迟 400ms 等页面内容就绪（切换瞬间 root 可能仍是旧窗口）
        handler.postDelayed({
            runCatching { inspect(pkg) }
        }, 400)
    }

    private fun inspect(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        val texts = mutableListOf<String>()
        collectTexts(root, texts, 0)
        if (texts.isEmpty()) return
        val combined = texts.joinToString(" ")

        val isRefund = combined.contains("退款成功") || combined.contains("已退款")
        val isPayment = combined.contains("支付成功") || combined.contains("付款成功") ||
            combined.contains("交易成功") || combined.contains("转账成功")
        if (!isPayment && !isRefund) return

        // 结果页特征：必有「完成/返回商家」按钮；聊天/账单页没有，防止翻旧消息误抓
        if (!combined.contains("完成") && !combined.contains("返回商家")) return

        val amount = extractAmount(combined) ?: return
        val type = if (isRefund) TransactionRecord.TYPE_INCOME else TransactionRecord.TYPE_EXPENSE
        val channel = if (pkg == WECHAT) "微信支付" else "支付宝"

        // 跨通道去重（与通知监听：结果页 + 服务号通知 = 同一笔）
        if (RecentCaptureRegistry.isDuplicate(type, amount)) return

        val ts = System.currentTimeMillis()
        scope.launch {
            runCatching {
                // 支付时刻位置（后台定位需授权，拿不到则留空）
                val place = com.mudasir.smartledger.util.LocationHelper.freshPlace(applicationContext)
                val suggestion = com.mudasir.smartledger.ml.PersonalTagger.recommendWithConfidence(
                    applicationContext, type, ts, amount, channel, null, combined.take(300), place?.name
                )
                AppDatabase.getDatabase(applicationContext).transactionDao().insert(
                    TransactionRecord(
                        type = type,
                        amount = amount,
                        channelName = channel,
                        merchant = null,
                        rawText = combined.take(500),
                        source = TransactionRecord.SOURCE_CAPTURE_ACCESSIBILITY,
                        packageName = pkg,
                        status = TransactionRecord.STATUS_PENDING,
                        timestamp = ts,
                        latitude = place?.latitude,
                        longitude = place?.longitude,
                        locationName = place?.name,
                        categoryName = suggestion.category,
                        aiConfidence = suggestion.confidence
                    )
                )
                com.mudasir.smartledger.util.PendingNotifier.update(applicationContext)
            }.onFailure { android.util.Log.w("PayAccSvc", "capture failed", it) }
        }
    }

    /** 取页面第一个 ¥ 金额（结果页的主金额）。 */
    private fun extractAmount(text: String): Double? {
        val m = Regex("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)").find(text) ?: return null
        return m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 30 || out.size > 200) return
        node.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
        node.contentDescription?.let { if (it.isNotBlank()) out.add(it.toString()) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out, depth + 1) }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        scope.cancel()
        return super.onUnbind(intent)
    }
}

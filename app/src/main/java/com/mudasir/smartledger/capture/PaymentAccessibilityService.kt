package com.mudasir.smartledger.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
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

    private var serviceJob = SupervisorJob()
    private var scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastEventAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!serviceJob.isActive) {
            serviceJob = SupervisorJob()
            scope = CoroutineScope(serviceJob + Dispatchers.IO)
        }
        com.mudasir.smartledger.util.CaptureServiceState
            .setAccessibilityConnected(applicationContext, true)
        Log.i(TAG, "payment capture accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg != WECHAT && pkg != ALIPAY) return

        val now = System.currentTimeMillis()
        if (now - lastEventAt < 350) return  // 内容变化事件很多，统一去抖
        lastEventAt = now
        // AccessibilityEvent 由系统复用，延迟任务不能持有 event 本体。
        val eventTexts = e.text.map(CharSequence::toString)

        // Compose/小程序页面常分阶段渲染；多个时间点轻量重试，任一次抓到后由注册表去重。
        listOf(150L, 600L, 1_300L).forEach { delay ->
            handler.postDelayed({ runCatching { inspect(pkg, eventTexts) } }, delay)
        }
    }

    private fun inspect(pkg: String, eventTexts: List<String>) {
        val texts = mutableListOf<String>()
        texts.addAll(eventTexts)
        val root = rootInActiveWindow
        if (root?.packageName?.toString() == pkg) collectTexts(root, texts, 0)
        if (texts.isEmpty()) return
        val combined = texts.joinToString(" ")
        val parsed = PaymentPageParser.parse(combined) ?: return
        val amount = parsed.amount
        val type = if (parsed.isRefund) TransactionRecord.TYPE_INCOME else TransactionRecord.TYPE_EXPENSE
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

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 30 || out.size > 200) return
        node.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
        node.contentDescription?.let { if (it.isNotBlank()) out.add(it.toString()) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out, depth + 1) }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "payment capture accessibility service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        com.mudasir.smartledger.util.CaptureServiceState
            .setAccessibilityConnected(applicationContext, false)
        // 系统可能因省电/内存暂时解绑后复用同一实例；不要在这里永久取消写库协程。
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        com.mudasir.smartledger.util.CaptureServiceState
            .setAccessibilityConnected(applicationContext, false)
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val WECHAT = "com.tencent.mm"
        private const val ALIPAY = "com.eg.android.AlipayGphone"
        private const val TAG = "PayAccSvc"
    }
}

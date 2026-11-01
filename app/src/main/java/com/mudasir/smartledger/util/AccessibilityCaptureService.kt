package com.mudasir.smartledger.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
 * 金额，生成「订单候选」进入收件箱——覆盖 UsageStats/通知通道无法识别的
 * H5/内嵌收银台（支付宝收银台、淘宝内支付页、银行 App 支付页等）。
 *
 * v2.36.0 修复（实测「支付宝/银行卡完全失败」的根因）：
 * 1) 确认词库与真实页面文案不匹配：支付宝收银台主按钮是「立即付款」而非
 *    「立即支付」；订单页标题是「付款详情」；密码框是「请输入密码」而非
 *    「请输入支付密码」；银行 App 是「交易密码」。词库补齐后才可能命中。
 * 2) 只处理 WINDOW_STATE_CHANGED：H5 收银台常以内容变化渲染（同 Activity），
 *    补充 WINDOW_CONTENT_CHANGED（带独立节流）。
 * 3) 想好再买浮层触发在去重 return 之后：同一页面第二次出现永远不再拦截。
 *    现改为先触发浮层、再做抓取去重。
 * 4) 部分收银台金额渲染为独立节点「200.00」（¥ 缺失）：强确认词已通过时
 *    允许裸小数兜底（分期页除外）。
 *
 * 设计要点（吸取 v2.22 退役教训）：
 * - 浅层遍历（≤25 层 / ≤120 文本），单事件去抖，绝不阻塞
 * - 强关键词门：页面必须同时含支付确认词，否则直接放弃
 * - 与通知/短信通道跨源去重（RecentCaptureRegistry），同一笔只记一次
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
        private const val CONTENT_MIN_INTERVAL_MS = 1200L

        // 强确认词（v2.36.0 对齐真实支付页面文案）：
        // - 支付宝收银台：付款详情 / 立即付款 / 需付款 / 确认付款
        // - 支付宝密码框：请输入密码 / 输入密码（无「支付」二字！）
        // - 微信：向商家付款 / 二维码付款 / 付款码 / 请输入密码
        // - 银行 App：交易密码 / 安全支付 / 验证并支付
        // - 通用收银台：收银台 / 现金收银台 / 立即支付 / 确认支付
        private val confirmWords = listOf(
            "确认付款", "立即付款", "立即支付", "确认支付", "收银台",
            "付款详情", "支付详情", "需付款", "应付款", "待付款",
            "请输入密码", "输入密码", "交易密码", "支付密码",
            "向商家付款", "二维码付款", "付款码",
            "验证并支付", "安全支付", "指纹支付", "刷脸支付", "面容支付",
            "支付金额", "付款金额"
        )
        // 分期特征：出现则不使用裸小数兜底（页面有多个等额小数，取最大会错）
        private val installmentWords = listOf("每月", "分期", "期数", "月供")
        // 金额：¥/￥ 优先，兼容 N.NN元
        private val amountRegex = Regex("""[¥￥]\s*([0-9]+(?:\.[0-9]{1,2})?)""")
        private val yuanRegex = Regex("""([0-9]{1,6}(?:\.[0-9]{1,2})?)\s*元""")
        // 裸小数兜底（仅在强确认词命中后使用）
        private val bareDecimalRegex = Regex("""([0-9]{1,6}\.[0-9]{2})""")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessAt = 0L
    private var lastContentAt = 0L
    private var lastCapturedKey = ""
    private var lastCapturedAt = 0L
    private var lastGuardAt = 0L

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
        val now = android.os.SystemClock.uptimeMillis()
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (now - lastProcessAt < POLL_DEBOUNCE_MS) return
                lastProcessAt = now
                handler.postDelayed({ runCatching { inspect() } }, 350)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // H5 收银台同页渲染：内容变化兜底（独立节流，防高频）
                if (now - lastContentAt < CONTENT_MIN_INTERVAL_MS) return
                lastContentAt = now
                handler.postDelayed({ runCatching { inspect() } }, 350)
            }
        }
    }

    private fun inspect() {
        val root = rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: return
        val texts = mutableListOf<String>()
        collectTexts(root, texts, 0)
        if (texts.size < 2) return
        val combined = texts.joinToString(" ")

        // 强关键词门：页面必须含「付款确认类」表述，且不是支付结果/退款页
        val confirm = confirmWords.any { combined.contains(it) }
        if (!confirm) return
        if (combined.contains("退款成功") || combined.contains("支付成功") ||
            combined.contains("付款成功") || combined.contains("已到账")
        ) return // 结果页：通知/短信通道已覆盖，避免与结果页重复

        // ★ 想好再买浮层：确认页命中即触发（密码输入前的最后干预点），
        //   必须在抓取去重之前——同一页面第二次出现仍要拦截。
        //   90 秒冷却：浮层被关闭后不因页面内容微变而立即再弹（防骚扰循环）。
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastGuardAt > 90_000) {
            lastGuardAt = nowMs
            val handled = runCatching {
                PurchaseGuardService.onExternalPayPage(applicationContext)
            }.getOrDefault(false)
            if (!handled) {
                // 守护服务未运行（用户只开了增强检测）→ 直接发冷静通知兜底，
                // 不让「拦截失败」再无声发生
                scope.launch { runCatching { fallbackThinkNotification() } }
            }
        }

        val amount = extractAmount(combined) ?: return
        val key = "$pkg|$amount"
        // 90 秒内同一笔（同包同额）只记一次
        if (key == lastCapturedKey && nowMs - lastCapturedAt < 90_000) return
        lastCapturedKey = key
        lastCapturedAt = nowMs

        val type = TransactionRecord.TYPE_EXPENSE

        scope.launch {
            runCatching {
                val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
                // 跨通道去重①：通知/短信通道 3 分钟窗口内已有同额待确认 → 跳过
                if (dao.countPendingLike(
                        amount, amount, nowMs - 180_000, nowMs + 60_000
                    ) > 0
                ) return@runCatching
                // 跨通道去重②：与通知/短信通道共享的注册表
                if (RecentCaptureRegistry.isDuplicate(type, amount)) return@runCatching
                val suggestion = com.mudasir.smartledger.ml.PersonalTagger.recommendWithConfidence(
                    applicationContext, type, nowMs, amount, channelOf(pkg), null, combined.take(300), null
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
                        timestamp = nowMs,
                        aiConfidence = suggestion.confidence
                    )
                )
                PendingNotifier.update(applicationContext)
            }.onFailure { android.util.Log.w(TAG, "capture failed", it) }
        }
    }

    private fun channelOf(pkg: String): String = when {
        pkg == "com.tencent.mm" -> "微信支付"
        pkg.startsWith("com.eg.android.AlipayGphone") -> "支付宝"
        pkg == "com.unionpay" -> "云闪付"
        pkg == "com.jingdong.app.mall" || pkg == "com.jd.jrapp" -> "京东"
        else -> "其他"
    }

    /** 守护服务未运行时的兜底：直接发「想好再买」冷静通知（无浮层）。 */
    private suspend fun fallbackThinkNotification() {
        val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val end = cal.clone() as java.util.Calendar
        end.add(java.util.Calendar.MONTH, 1)
        val spent = dao.sumByType(
            TransactionRecord.TYPE_EXPENSE, cal.timeInMillis, end.timeInMillis
        )
        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val budget = prefs.getFloat("monthly_budget", 0f).toDouble()
        val pct = if (budget > 0) ((spent / budget) * 100).toInt().coerceIn(0, 100) else null
        PurchaseGuardNotifier.notifyThink(applicationContext, spent, pct)
    }

    private fun extractAmount(text: String): Double? {
        amountRegex.find(text)?.let { m ->
            return m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }
        }
        yuanRegex.find(text)?.let { m ->
            return m.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 }
        }
        // 裸小数兜底：收银台常把金额渲染为独立节点（¥ 缺失）。
        // 分期页有多个等额小数（¥83.33×3），不适用兜底。
        if (installmentWords.any { text.contains(it) }) return null
        val candidates = bareDecimalRegex.findAll(text).mapNotNull {
            it.groupValues[1].toDoubleOrNull()
        }.filter { it > 0 }
        return candidates.maxOrNull()
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

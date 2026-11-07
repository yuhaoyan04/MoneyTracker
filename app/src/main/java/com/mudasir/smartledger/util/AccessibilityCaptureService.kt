package com.mudasir.smartledger.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mudasir.smartledger.capture.CaptureWhitelist
import com.mudasir.smartledger.capture.PageAmountExtractor
import com.mudasir.smartledger.capture.PaymentPageState
import com.mudasir.smartledger.capture.PaymentScreenClassifier
import com.mudasir.smartledger.capture.CheckoutNode
import com.mudasir.smartledger.capture.CheckoutPageParser
import com.mudasir.smartledger.capture.CheckoutResult
import com.mudasir.smartledger.capture.VisualGuardTargets
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

        @Volatile private var connectedInstance: AccessibilityCaptureService? = null
        fun overlayWindowManager(): android.view.WindowManager? =
            connectedInstance?.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        fun activeRootPackage(): String? = connectedInstance?.rootInActiveWindow?.packageName?.toString()
        fun onShoppingActivityResumed(pkg: String, cls: String) {
            connectedInstance?.let { service -> service.handler.post {
                if (service.rootInActiveWindow?.packageName?.toString() == pkg) {
                    service.visualMonitor?.ensureCheckoutWindow(pkg, cls)
                }
            } }
        }

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
        // 金额提取与渠道归类提取至 PageAmountExtractor / CaptureWhitelist（可单元测试）
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastProcessAt = 0L
    private var lastContentAt = 0L
    private var lastCapturedKey = ""
    private var lastCapturedAt = 0L
    private var lastGuardAt = 0L
    private var lastGuardDiagnostic = ""
    private var lastGuardDiagnosticAt = 0L
    private var lastObservedPackage = ""
    private var lastLoggedContentAt = 0L
    private val visualMonitor by lazy {
        if (android.os.Build.VERSION.SDK_INT >= 30) VisualCheckoutMonitor(this) else null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        connectedInstance = this
        GuardDiagnosticLog.lifecycle("A11Y_CONNECTED")
        // 开机/重授权后自愈：待确认常驻通知刷新
        scope.launch {
            runCatching { PendingNotifier.update(applicationContext) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val now = android.os.SystemClock.uptimeMillis()
        val eventPackage = e.packageName?.toString().orEmpty()
        if (GuardDiagnosticLog.isRecording() && !e.isPassword && CheckoutPageParser.isSupported(eventPackage)) {
            val kind = when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "WINDOWS_CHANGED"
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
                AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
                else -> null
            }
            if (kind != null && (kind != "CONTENT_CHANGED" || now - lastLoggedContentAt >= 1_200L)) {
                if (kind == "CONTENT_CHANGED") lastLoggedContentAt = now
                val cls = e.className?.toString().orEmpty()
                val role = if (kind == "VIEW_CLICKED" && !cls.contains("EditText", ignoreCase = true)) {
                    GuardLogPolicy.clickRole(e.text.take(5).map { it?.toString().orEmpty().take(80) })
                } else "OTHER"
                GuardDiagnosticLog.accessibilityEvent(eventPackage, cls, kind, e.eventTime, role)
            }
        }
        if (eventPackage.isNotBlank() && eventPackage != lastObservedPackage) {
            lastObservedPackage = eventPackage
            lastGuardDiagnostic = ""
            lastCapturedKey = ""
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = e.packageName?.toString().orEmpty()
            val cls = e.className?.toString().orEmpty()
            if (VisualGuardTargets.isJdVerification(pkg, cls)) visualMonitor?.pauseForVerification(pkg)
            else if (VisualGuardTargets.sourceActivity(pkg, cls)) visualMonitor?.checkoutWindow(pkg, cls)
            else if (pkg in VisualGuardTargets.shopping && cls.endsWith("Activity")) visualMonitor?.leaveCheckout(VisualGuardTargets.isSourcePaymentTransition(cls))
            VisualPaymentHandoff.observe(pkg, cls)?.let { PurchaseGuardService.onVisualPaymentHandoff(applicationContext, pkg, it) }
            if (CheckoutPageParser.isSupported(pkg)) {
                PurchaseGuardService.onObservedForeground(pkg)
            }
            if (CheckoutPageParser.isSupported(pkg)) {
                PurchaseGuardService.recordDetection(pkg, cls, "收到无障碍窗口事件")
            }
            val includeCheckout = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
                .getString("guard_scope", "payment") == "checkout"
            if (PaymentScreenClassifier.matches(pkg, cls, includeCheckout)) {
                PurchaseGuardService.recordDetection(pkg, cls, "窗口候选，等待付款页面和金额")
            }
        }
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (now - lastProcessAt < POLL_DEBOUNCE_MS) return
                lastProcessAt = now
                handler.postDelayed({ inspectSafely() }, 350)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // H5 收银台同页渲染：内容变化兜底（独立节流，防高频）
                if (now - lastContentAt < CONTENT_MIN_INTERVAL_MS) return
                lastContentAt = now
                handler.postDelayed({ inspectSafely() }, 350)
            }
        }
    }

    private fun inspectSafely() {
        try {
            inspect()
        } catch (error: Exception) {
            // Exception messages can contain page content; record only the exception type.
            val pkg = lastObservedPackage
            if (CheckoutPageParser.isSupported(pkg)) {
                GuardDiagnosticLog.failure(error)
                PurchaseGuardService.recordDetection(pkg, "", "无障碍检查异常：${error.javaClass.simpleName}")
            }
        }
    }

    private fun logGuardDiagnostic(pkg: String, key: String, message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (key != lastGuardDiagnostic || now - lastGuardDiagnosticAt >= 15_000L) {
            lastGuardDiagnostic = key
            lastGuardDiagnosticAt = now
            PurchaseGuardService.recordDetection(pkg, "", message)
        }
    }

    private fun inspect() {
        val root = rootInActiveWindow ?: run {
            if (CheckoutPageParser.isSupported(lastObservedPackage) &&
                getSharedPreferences("guard_prefs", Context.MODE_PRIVATE).getBoolean("guard_enabled", false)) {
                logGuardDiagnostic(lastObservedPackage, "$lastObservedPackage|NO_ROOT", "当前活动窗口根节点不可读取")
            }
            return
        }
        val pkg = root.packageName?.toString() ?: return
        if (!CheckoutPageParser.isSupported(pkg)) return
        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("guard_enabled", false)) {
            visualMonitor?.inspect(pkg)
            val nodes = mutableListOf<CheckoutNode>()
            collectCheckoutNodes(root, nodes, 0, -1, intArrayOf(0))
            val threshold = com.mudasir.smartledger.capture.GuardThreshold.read(prefs)
            val result = CheckoutPageParser.analyze(pkg, nodes, threshold)
            PurchaseGuardService.onCheckoutEvaluated(pkg,
                if (result.status == CheckoutResult.Status.QUALIFIED) result.amount?.toDouble() else null)
            val evidence = CheckoutPageParser.diagnosticSummary(nodes)
            val diagnostic = "$pkg|${result.status}|$evidence"
            val message = when (result.status) {
                CheckoutResult.Status.NOT_CHECKOUT -> "页面未识别为付款确认页"
                CheckoutResult.Status.NO_ACCESSIBLE_TEXT -> "当前窗口没有可读取的文字节点"
                CheckoutResult.Status.NO_AMOUNT -> "付款页候选：未识别到明确实付金额"
                CheckoutResult.Status.AMBIGUOUS -> "付款页候选：金额冲突，未提醒"
                CheckoutResult.Status.BELOW_THRESHOLD -> "付款金额未超过门槛，未提醒"
                CheckoutResult.Status.QUALIFIED -> "付款金额超过门槛，请求冷静提醒"
            }
            logGuardDiagnostic(pkg, diagnostic, "$message；$evidence")
            val now = System.currentTimeMillis()
            if (result.status == CheckoutResult.Status.QUALIFIED && now - lastGuardAt > 5_000) {
                lastGuardAt = now
                val handled = PurchaseGuardService.onExternalPayPage(applicationContext, pkg, result.amount!!.toDouble())
                if (!handled && prefs.getBoolean("guard_enabled", false) &&
                    CheckoutPageParser.exceedsThreshold(result.amount.toDouble(), com.mudasir.smartledger.capture.GuardThreshold.read(prefs))) {
                    scope.launch {
                        runCatching { fallbackThinkNotification(pkg, result.amount.toDouble()) }.onFailure {
                            GuardDiagnosticLog.failure(it)
                            PurchaseGuardService.recordDetection(pkg, "", "无障碍兜底失败")
                        }
                    }
                }
            }
        }
        // Shopping pages are only used for the guard; transaction capture has its own whitelist.
        if (!CaptureWhitelist.isPageWatched(pkg)) return
        val texts = mutableListOf<String>()
        collectTexts(root, texts, 0)
        if (texts.isEmpty()) return
        val combined = texts.joinToString(" ")

        val completed = PaymentPageState.isSuccessful(combined)
        val nowMs = System.currentTimeMillis()

        // A checkout is an intention, not evidence that a payment happened.
        if (!completed) { lastCapturedKey = ""; return }
        val amount = PageAmountExtractor.extractConservative(combined) ?: return
        val key = "$pkg|$amount"
        // 90 秒内同一笔（同包同额）只记一次
        if (key == lastCapturedKey && nowMs - lastCapturedAt < 90_000) return
        lastCapturedKey = key
        lastCapturedAt = nowMs

        val type = TransactionRecord.TYPE_EXPENSE
        val channel = CaptureWhitelist.channelOf(pkg)

        scope.launch {
            runCatching {
                val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
                val suggestion = com.mudasir.smartledger.ml.PersonalTagger.recommendWithConfidence(
                    applicationContext, type, nowMs, amount, channel, null, combined.take(300), null
                )
                dao.insert(
                    TransactionRecord(
                        type = type,
                        amount = amount,
                        categoryName = suggestion.category,
                        channelName = channel,
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

    /** 守护服务未运行时的兜底：直接发「想好再买」冷静通知（无浮层）。 */
    private suspend fun fallbackThinkNotification(pkg: String, amount: Double) {
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
        val prefs = getSharedPreferences("budget_prefs", Context.MODE_PRIVATE)
        val budget = prefs.getFloat("monthly_budget", 0f).toDouble()
        val pct = if (budget > 0) ((spent / budget) * 100).toInt().coerceIn(0, 100) else null
        withContext(Dispatchers.Main) {
            val guard = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
            val root = rootInActiveWindow ?: run {
                PurchaseGuardService.recordDetection(pkg, "", "无障碍兜底：没有活动窗口")
                return@withContext
            }
            if (!guard.getBoolean("guard_enabled", false) || root.packageName?.toString() != pkg) {
                PurchaseGuardService.recordDetection(pkg, "", "无障碍兜底：开关或应用已变化")
                return@withContext
            }
            val nodes = mutableListOf<CheckoutNode>()
            collectCheckoutNodes(root, nodes, 0, -1, intArrayOf(0))
            val current = CheckoutPageParser.analyze(pkg, nodes, com.mudasir.smartledger.capture.GuardThreshold.read(guard))
            if (current.status == CheckoutResult.Status.QUALIFIED && current.amount != null &&
                kotlin.math.abs(current.amount.toDouble() - amount) < 0.005) {
                PurchaseGuardNotifier.notifyThink(applicationContext, spent, pct)
                PurchaseGuardService.recordDetection(pkg, "", "无障碍兜底：已调用通知，显示未验证")
            } else {
                PurchaseGuardService.recordDetection(pkg, "", "无障碍兜底：页面或金额已变化")
            }
        }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 25 || out.size > 120) return
        if (node.isPassword) return
        node.text?.let { if (it.isNotBlank()) out.add(it.toString()) }
        node.contentDescription?.let { if (it.isNotBlank()) out.add(it.toString()) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out, depth + 1) }
        }
    }

    private fun collectCheckoutNodes(node: AccessibilityNodeInfo, out: MutableList<CheckoutNode>, depth: Int, parent: Int, count: IntArray) {
        if (depth > 25 || out.size >= 160 || count[0] >= 600 || node.isPassword) return
        val id = count[0]++
        if (node.isVisibleToUser) {
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            val text = node.text?.toString().orEmpty().trim()
            val desc = node.contentDescription?.toString().orEmpty().trim()
            listOf(text, desc).filter { it.isNotBlank() }.distinct().forEach {
                if (out.size < 160) out.add(CheckoutNode(it.take(240), parent, bounds.left, bounds.top, bounds.right, bounds.bottom))
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectCheckoutNodes(it, out, depth + 1, id, count) }
        }
    }

    override fun onInterrupt() { GuardDiagnosticLog.lifecycle("A11Y_INTERRUPTED") }

    override fun onUnbind(intent: Intent?): Boolean {
        GuardDiagnosticLog.lifecycle("A11Y_UNBOUND")
        isRunning = false
        connectedInstance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        GuardDiagnosticLog.lifecycle("A11Y_DESTROYED")
        isRunning = false
        connectedInstance = null
        visualMonitor?.close()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }
}

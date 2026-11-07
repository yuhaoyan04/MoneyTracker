package com.mudasir.smartledger.util

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mudasir.smartledger.capture.CaptureWhitelist
import com.mudasir.smartledger.capture.PageAmountExtractor
import com.mudasir.smartledger.capture.PaymentPageState
import com.mudasir.smartledger.capture.PaymentScreenClassifier
import com.mudasir.smartledger.capture.CheckoutNode
import com.mudasir.smartledger.capture.CheckoutPageParser
import com.mudasir.smartledger.capture.CheckoutResult
import com.mudasir.smartledger.capture.OrderListPaymentParser
import com.mudasir.smartledger.capture.OrderPayCandidate
import com.mudasir.smartledger.capture.VisualGuardTargets
import com.mudasir.smartledger.capture.AlipayVisualPolicy
import com.mudasir.smartledger.capture.JdAlipayEntryTracker
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
        fun approveOrderButton(amount: Double) { connectedInstance?.orderShield?.approved(amount) }
        fun cancelOrderButton() { connectedInstance?.orderShield?.cancelPending() }
        fun clearOrderButtonShield() { connectedInstance?.orderShield?.reset() }
        fun recordWindowLayers() {
            val svc = connectedInstance ?: return
            if (!GuardDiagnosticLog.isRecording()) return
            runCatching {
                val windows = svc.windows.take(20).map { window ->
                    val pkg = runCatching { window.root?.packageName?.toString() }.getOrNull()
                    val kind = when {
                        pkg == null -> "unknown"
                        pkg == svc.packageName -> "self"
                        pkg == "com.android.systemui" -> "system"
                        CheckoutPageParser.isSupported(pkg) -> "payment"
                        else -> "other"
                    }
                    "$kind:${window.type}:${window.layer}:${if (window.isActive) 1 else 0}:${if (window.isFocused) 1 else 0}"
                }
                GuardDiagnosticLog.windowLayers(windows)
            }.onFailure { GuardDiagnosticLog.failure(it) }
        }
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
    private val jdAlipayEntry = JdAlipayEntryTracker()
    private var alipayVisualMonitor: AlipayVisualMonitor? = null
    private var lastLoggedContentAt = 0L
    private var orderButtons: List<OrderPayCandidate> = emptyList()
    private var orderButtonsAt = 0L
    private val orderShield by lazy { OrderButtonShield(this, ::onOrderShieldTap) }
    private val orderRefresh = object : Runnable {
        override fun run() {
            if (rootInActiveWindow?.packageName?.toString() == VisualGuardTargets.TAOBAO) inspectSafely()
        }
    }
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
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && eventPackage == VisualGuardTargets.TAOBAO) orderShield.reset()
        else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && eventPackage == VisualGuardTargets.TAOBAO) orderShield.removeViews()
        else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            ((eventPackage == VisualGuardTargets.TAOBAO && e.className?.toString() == "android.app.Dialog") ||
             (eventPackage.isNotBlank() && eventPackage != VisualGuardTargets.TAOBAO &&
                 rootInActiveWindow?.packageName?.toString() == eventPackage))) orderShield.reset()
        if (eventPackage == VisualGuardTargets.TAOBAO && e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
            val bounds = android.graphics.Rect()
            val hasSourceBounds = runCatching {
                val source = e.source ?: return@runCatching false
                source.getBoundsInScreen(bounds)
                true
            }.getOrDefault(false)
            val recent = now - orderButtonsAt in 0..1_200L && e.eventTime - orderButtonsAt in 0..1_200L
            val candidate = if (recent && hasSourceBounds) OrderListPaymentParser.match(orderButtons,
                bounds.left, bounds.top, bounds.right, bounds.bottom) else null
            if (GuardDiagnosticLog.isRecording()) PurchaseGuardService.recordDetection(eventPackage, "", when {
                orderButtons.isEmpty() -> "订单列表：点击时没有可用快照"
                !hasSourceBounds -> "订单列表：点击来源不可读取"
                !recent -> "订单列表：点击快照已过期"
                candidate == null -> "订单列表：点击未匹配唯一按钮"
                !CheckoutPageParser.exceedsThreshold(candidate.amount.toDouble(), com.mudasir.smartledger.capture.GuardThreshold.read(prefs)) -> "订单列表：点击金额未超过门槛"
                else -> "订单列表：点击匹配成功"
            })
            orderButtons = emptyList(); orderButtonsAt = 0L
            if (candidate != null && prefs.getBoolean("guard_enabled", false) &&
                prefs.getBoolean("guard_visual_order_pages_enabled", false) &&
                CheckoutPageParser.exceedsThreshold(candidate.amount.toDouble(), com.mudasir.smartledger.capture.GuardThreshold.read(prefs))) {
                visualMonitor?.rememberOrderButton(eventPackage, candidate.amount.toDouble())
                PurchaseGuardService.recordDetection(eventPackage, "", "订单列表：已关联所点付款按钮与实付金额")
            }
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            (eventPackage == VisualGuardTargets.TAOBAO && (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED))) {
            orderButtons = emptyList(); orderButtonsAt = 0L
        }
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !e.isPassword && eventPackage == AlipayVisualPolicy.JD) {
            val texts = e.text.take(5).map { it?.toString().orEmpty().take(80) }
            val accepted = jdAlipayEntry.onClick(eventPackage, texts, SystemClock.elapsedRealtime())
            if (AlipayVisualPolicy.isPayAction(texts)) PurchaseGuardService.recordDetection(eventPackage, "", if (accepted) "支付宝入口：付款操作已接收" else "支付宝入口：缺少有效京东收银台")
        }
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
            visualMonitor?.observedWindow(pkg, cls, e.eventTime, rootInActiveWindow?.packageName?.toString() == pkg)
            if (pkg == VisualGuardTargets.WECHAT && cls.endsWith("LauncherUI") &&
                rootInActiveWindow?.packageName?.toString() == pkg) PurchaseGuardService.onWechatHome()
            if (pkg != AlipayVisualPolicy.JD && pkg != AlipayVisualPolicy.ALIPAY ||
                (pkg == AlipayVisualPolicy.ALIPAY && cls.endsWith("Activity") && !AlipayVisualPolicy.isAlipayCashier(pkg, cls))) {
                alipayVisualMonitor?.cancel()
            }
            val alipayEntry = jdAlipayEntry.onWindow(pkg, cls, SystemClock.elapsedRealtime())
            if (AlipayVisualPolicy.isAlipayCashier(pkg, cls)) {
                PurchaseGuardService.recordDetection(pkg, "", if (alipayEntry) "支付宝入口：已匹配付款窗口" else "支付宝入口：没有有效付款操作")
            }
            if (alipayEntry && android.os.Build.VERSION.SDK_INT >= 30) {
                if (alipayVisualMonitor == null) alipayVisualMonitor = AlipayVisualMonitor(this)
                alipayVisualMonitor?.start()
            }
            if (VisualGuardTargets.isJdVerification(pkg, cls)) visualMonitor?.pauseForVerification(pkg)
            else if (VisualGuardTargets.isTaobaoSkuDialog(pkg, cls)) visualMonitor?.checkoutWindow(pkg, cls)
            else if (VisualGuardTargets.sourceActivity(pkg, cls)) visualMonitor?.checkoutWindow(pkg, cls)
            else if (pkg in VisualGuardTargets.shopping && cls.endsWith("Activity")) visualMonitor?.leaveCheckout(VisualGuardTargets.isSourcePaymentTransition(cls))
            if (pkg == VisualGuardTargets.TAOBAO && cls == "android.app.Dialog") {
                visualMonitor?.taobaoDialogOpened(pkg)
                // The payment dialog can disappear before the normal 350 ms inspection.
                // Recheck only while Taobao remains the active root; parsing still applies
                // the full checkout, amount and threshold rules.
                listOf(40L, 120L, 240L).forEach { delay ->
                    handler.postDelayed({
                        if (rootInActiveWindow?.packageName?.toString() == pkg) inspectSafely()
                    }, delay)
                }
            }
            VisualPaymentHandoff.observe(pkg, cls)?.let { PurchaseGuardService.onVisualPaymentHandoff(applicationContext, pkg, it) }
            // Also observe leaving a supported payment app: a launcher is not in the
            // payment allowlist, but an existing prompt must not follow the user there.
            if (pkg.isNotBlank() && (CheckoutPageParser.isSupported(pkg) ||
                    rootInActiveWindow?.packageName?.toString() == pkg)) {
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
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
            if (CheckoutPageParser.isTaobaoOrderList(pkg, nodes)) {
                visualMonitor?.ensureTaobaoOrderList()
                val metrics = resources.displayMetrics
                val wasEmpty = orderButtons.isEmpty()
                orderButtons = OrderListPaymentParser.candidates(nodes, metrics.widthPixels, metrics.heightPixels)
                orderButtonsAt = SystemClock.uptimeMillis()
                if (wasEmpty && orderButtons.isNotEmpty()) PurchaseGuardService.recordDetection(pkg, "", "订单列表：付款按钮快照已就绪")
                orderShield.update(orderButtons, thresholdForShield(prefs), shieldEnabled(prefs))
                handler.removeCallbacks(orderRefresh)
                if (orderButtons.isNotEmpty()) handler.postDelayed(orderRefresh, 700L)
            } else if (pkg == VisualGuardTargets.TAOBAO) {
                // A click may arrive just after the current root becomes temporarily empty.
                // Preserve only the short-lived existing snapshot; explicit window/content
                // changes and its 1.2 s expiry still invalidate it.
                if (nodes.size > 1 || SystemClock.uptimeMillis() - orderButtonsAt !in 0..1_200L) {
                    orderButtons = emptyList(); orderButtonsAt = 0L
                }
                orderShield.removeViews()
                handler.removeCallbacks(orderRefresh)
            }
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
            if (result.status == CheckoutResult.Status.QUALIFIED) {
                visualMonitor?.rememberStructuredDialog(pkg, result.amount!!.toDouble())
            }
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

    private fun thresholdForShield(prefs: android.content.SharedPreferences) =
        com.mudasir.smartledger.capture.GuardThreshold.read(prefs)

    private fun shieldEnabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean("guard_enabled", false) && prefs.getBoolean("guard_visual_order_pages_enabled", false) &&
            prefs.getBoolean("guard_order_button_shield_enabled", false) &&
            prefs.getString("guard_mode", "overlay") == "overlay" && PurchaseGuardService.isRunning &&
            android.provider.Settings.canDrawOverlays(this)

    private fun onOrderShieldTap(candidate: OrderPayCandidate) {
        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val root = rootInActiveWindow
        if (!shieldEnabled(prefs) || root?.packageName?.toString() != VisualGuardTargets.TAOBAO) {
            orderShield.reset()
            android.widget.Toast.makeText(this, "页面或提醒状态已变化，请重新点击", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val nodes = mutableListOf<CheckoutNode>()
        collectCheckoutNodes(root, nodes, 0, -1, intArrayOf(0))
        val metrics = resources.displayMetrics
        val current = OrderListPaymentParser.candidates(nodes, metrics.widthPixels, metrics.heightPixels)
        if (candidate !in current || !CheckoutPageParser.exceedsThreshold(candidate.amount.toDouble(), thresholdForShield(prefs))) {
            orderShield.reset()
            android.widget.Toast.makeText(this, "订单页面已变化，请重新点击付款", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        orderShield.suspendFor(candidate)
        PurchaseGuardService.recordDetection(VisualGuardTargets.TAOBAO, "", "订单按钮保护：用户点击高额按钮")
        if (!PurchaseGuardService.onOrderButtonTap(applicationContext, candidate.amount.toDouble())) {
            orderShield.cancelPending()
            android.widget.Toast.makeText(this, "提醒暂不可用，请检查守护服务", android.widget.Toast.LENGTH_LONG).show()
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

    override fun onInterrupt() { orderShield.reset(); GuardDiagnosticLog.lifecycle("A11Y_INTERRUPTED") }

    override fun onUnbind(intent: Intent?): Boolean {
        GuardDiagnosticLog.lifecycle("A11Y_UNBOUND")
        orderShield.reset()
        isRunning = false
        connectedInstance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        GuardDiagnosticLog.lifecycle("A11Y_DESTROYED")
        isRunning = false
        connectedInstance = null
        orderShield.reset()
        visualMonitor?.close()
        alipayVisualMonitor?.close()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }
}

package com.mudasir.smartledger.util

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import androidx.core.app.ServiceCompat
import com.mudasir.smartledger.R
import com.mudasir.smartledger.activity.AddEditTransactionActivity
import com.mudasir.smartledger.activity.CaptureInboxActivity
import com.mudasir.smartledger.activity.SettingsActivity
import com.mudasir.smartledger.activity.WishlistAddActivity
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.capture.PaymentScreenClassifier
import com.mudasir.smartledger.capture.ResumedPage
import com.mudasir.smartledger.capture.UsagePaymentEvents
import com.mudasir.smartledger.capture.GuardPromptPolicy
import com.mudasir.smartledger.capture.VisualGuardTargets
import com.mudasir.smartledger.capture.PaymentAppLaunchTracker
import com.mudasir.smartledger.capture.CheckoutPageParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 「想好再买」守护服务 + 悬浮球：
 *
 * 1. 支付语境提醒：用户进入支付应用付款页面时，弹出冷静浮层/通知
 *    （UsageStatsManager 页面级检测，输入密码之前完成干预）
 * 2. 悬浮球：全局可拖动小球（待确认角标），单击展开快捷面板
 *    （记一笔/收件箱/想好再买/隐藏），双击直达记一笔
 *
 * 平台约束：Android 不允许第三方 App 在付款确认前读取金额/商户，
 * 提醒基于「进入支付页面」语境；金额维度由大额预警在支付后立即补上。
 */
class PurchaseGuardService : Service() {

    companion object {
        const val CHANNEL_ACTIVE = PurchaseGuardNotifier.CHANNEL_ACTIVE
        private const val ACTION_PAY_PAGE = "com.mudasir.smartledger.PAY_PAGE"
        private const val EXTRA_PAY_PACKAGE = "pay_package"
        private const val EXTRA_PAY_AMOUNT = "pay_amount"

        @Volatile
        var isRunning = false
            private set

        /** 检测自检：最近一次识别到的前台页面与判定（设置页实时展示）。 */
        @Volatile
        var debugPkg: String = ""
        @Volatile
        var debugCls: String = ""
        @Volatile
        var debugPay: Boolean = false
        @Volatile
        var debugUpdatedAt: Long = 0
        @Volatile
        var debugHistory: List<String> = emptyList()
        @Volatile
        var debugFailure: String = ""

        fun recordFailure(stage: String, error: Throwable) {
            debugFailure = "阶段：$stage\n${GuardFailureDetails.format(error)}"
            GuardDiagnosticLog.failure(error)
            Log.w("PurchaseGuard", "Failure at $stage", error)
        }

        @Synchronized
        fun recordDetection(pkg: String, cls: String, result: String) {
            GuardDiagnosticLog.detection(pkg, cls, result)
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            debugPkg = pkg
            debugCls = cls
            debugUpdatedAt = System.currentTimeMillis()
            debugHistory = (debugHistory + "$stamp $pkg ${cls.substringAfterLast('.')} | $result").takeLast(40)
        }

        @Volatile
        private var instance: PurchaseGuardService? = null

        /** 悬浮球开关（设置页与面板共用）。 */
        fun setBallEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("ball_enabled", enabled).apply()
            if (enabled && !isRunning) {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, PurchaseGuardService::class.java)
                )
            }
            instance?.apply { handler.post { applyBallVisibility() } }
        }

        fun refreshBallBadge(context: Context) {
            instance?.handler?.post { instance?.updateBallBadge() }
        }

        /** A different foreground app invalidates an uncompleted prompt. */
        fun onObservedForeground(pkg: String) {
            instance?.let { svc -> svc.handler.post { svc.observeForeground(pkg, System.currentTimeMillis()) } }
        }

        /** Settings must remain usable even if the source payment app was covered by an overlay. */
        fun dismissForSettings() {
            instance?.let { svc -> svc.handler.post {
                if (svc.requestPackage != null || svc.overlayView != null) {
                    val origin = svc.overlayPackage ?: svc.requestPackage.orEmpty()
                    svc.requestGeneration++
                    svc.removeOverlay()
                    recordDetection(origin, "", "已进入设置，关闭付款提醒")
                }
            } }
        }

        fun onCheckoutEvaluated(pkg: String, amount: Double?) {
            instance?.let { svc -> svc.handler.post {
                if (!svc.visualHandoffRequest && svc.requestPackage == pkg && (amount == null ||
                        kotlin.math.abs(svc.requestAmount - amount) >= 0.005)) {
                    svc.requestGeneration++
                    svc.requestPackage = null
                    if (svc.overlayView != null) svc.removeOverlay()
                    recordDetection(pkg, "", "付款页面或金额已变化，关闭过期提醒")
                }
            } }
        }

        fun onVisualPaymentHandoff(context: Context, pkg: String, amount: Double) {
            val prefs = context.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("guard_enabled", false) ||
                !(prefs.getBoolean("guard_visual_order_pages_enabled", false) || prefs.getBoolean("guard_visual_multi_app_enabled", false)) ||
                !CheckoutPageParser.exceedsThreshold(amount, com.mudasir.smartledger.capture.GuardThreshold.read(prefs))) return
            instance?.let { svc -> svc.handler.post {
                svc.deliverVisualHandoff(pkg, amount)
            } }
        }

        /**
         * 无障碍通道检测到支付页 → 触发想好再买浮层/通知（遵守 2 分钟授权冷却）。
         * v2.36.0 返回 true=守护服务在线已接手；false=服务未运行，调用方可做兜底提醒。
         */
        fun onExternalPayPage(context: Context, pkg: String, amount: Double): Boolean {
            val prefs = context.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("guard_enabled", false)) {
                recordDetection(pkg, "", "提醒未开启")
                return false
            }
            if (!CheckoutPageParser.exceedsThreshold(amount, com.mudasir.smartledger.capture.GuardThreshold.read(prefs))) {
                recordDetection(pkg, "", "金额未超过提醒门槛")
                return false
            }
            val svc = instance
            if (svc != null) {
                recordDetection(pkg, "", "守护服务已接收提醒请求")
                svc.handler.post {
                    runCatching { svc.nudgeFromDetection(pkg, amount) }.onFailure {
                        recordFailure("处理付款提醒请求", it)
                        recordDetection(pkg, "", "提醒失败：${it.javaClass.simpleName}")
                    }
                }
                return true
            }
            return runCatching {
                recordDetection(pkg, "", "尝试启动守护服务")
                androidx.core.content.ContextCompat.startForegroundService(context,
                    Intent(context, PurchaseGuardService::class.java)
                        .setAction(ACTION_PAY_PAGE).putExtra(EXTRA_PAY_PACKAGE, pkg).putExtra(EXTRA_PAY_AMOUNT, amount))
                true
            }.onFailure { recordDetection(pkg, "", "后台启动服务失败：${it.javaClass.simpleName}") }.getOrDefault(false)
        }

        fun applyBall(context: Context) {
            instance?.handler?.post { instance?.applyBallVisibility() }
        }

        private val watchedPackages = setOf(
            "com.tencent.mm",                  // 微信
            "com.eg.android.AlipayGphone",     // 支付宝
            "com.eg.android.AlipayGphone.rc",
            "com.eg.android.AlipayGphone.lite",
            "com.taobao.taobao",               // 淘宝
            "com.jingdong.app.mall",           // 京东
            "com.sankuai.meituan",             // 美团
            "com.sankuai.meituan.takeoutnew",
            "com.xunmeng.pinduoduo",           // 拼多多
            "com.sdu.didi.psnger",             // 滴滴
            "com.unionpay"                     // 云闪付
        )

        private const val POLL_MS = 1500L
        private const val NOTIFICATION_ID = 2001

        /** 三思授权冷却：确认放行后，同支付 App 在此时间内不重复拦截。 */
        private const val APPROVAL_COOLDOWN_MS = 120_000L
        private const val BALL_SIZE_DP = 44
        private const val PANEL_WIDTH_DP = 168
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastPoll = 0L
    private var currentFg: String? = null
    private var currentCls: String? = null
    private var lastKey = ""
    private var lastNudgeAt = 0L
    private var screenOn = true
    private var pollCount = 0
    private val seenUsageHints = LinkedHashSet<String>()
    private val launchTracker by lazy { PaymentAppLaunchTracker(packageName) }
    private var requestGeneration = 0L
    private var requestPackage: String? = null
    private var requestStartedAt = 0L
    private var requestAmount = Double.NaN
    private var visualHandoffRequest = false
    private var overlayWindowManager: WindowManager? = null
    private var overlayPackage: String? = null
    private var overlayTick: Runnable? = null
    private var lastPollErrorAt = 0L
    private var lastVisualResumeAt = 0L
    private var visualDeliveryGeneration = 0L

    private fun deliverVisualHandoff(pkg: String, amount: Double) {
        val generation = ++visualDeliveryGeneration
        val started = android.os.SystemClock.elapsedRealtime()
        val attempt = object : Runnable {
            override fun run() {
                val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
                val enabled = instance === this@PurchaseGuardService && generation == visualDeliveryGeneration &&
                    prefs.getBoolean("guard_enabled", false) &&
                    (prefs.getBoolean("guard_visual_order_pages_enabled", false) || prefs.getBoolean("guard_visual_multi_app_enabled", false)) &&
                    CheckoutPageParser.exceedsThreshold(amount, com.mudasir.smartledger.capture.GuardThreshold.read(prefs))
                when (com.mudasir.smartledger.capture.HandoffForegroundWait.decide(
                    pkg, AccessibilityCaptureService.activeRootPackage(),
                    android.os.SystemClock.elapsedRealtime() - started, enabled)) {
                    com.mudasir.smartledger.capture.HandoffForegroundWait.Decision.DELIVER -> {
                        recordDetection(pkg, "", "屏幕识别：已交接支付页面")
                        nudgeFromDetection(pkg, amount, visualHandoff = true)
                    }
                    com.mudasir.smartledger.capture.HandoffForegroundWait.Decision.WAIT -> handler.postDelayed(this, 250L)
                    com.mudasir.smartledger.capture.HandoffForegroundWait.Decision.ABANDON ->
                        recordDetection(pkg, "", "屏幕识别：支付窗口未及时成为前台，放弃提醒")
                }
            }
        }
        attempt.run()
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            if (screenOn) handler.postDelayed(pollRunnable, 200L)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (screenOn) runCatching { checkForeground() }.onFailure {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPollErrorAt >= 10_000L) {
                    lastPollErrorAt = now
                    GuardDiagnosticLog.failure(it)
                }
            }
            // 悬浮球角标：每 4 次轮询（约 6 秒）同步一次待确认数
            pollCount++
            if (ballView != null && pollCount % 4 == 0) updateBallBadge()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        GuardDiagnosticLog.lifecycle("GUARD_CREATED")
        PurchaseGuardNotifier.ensureChannels(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, PurchaseGuardNotifier.buildActive(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, PurchaseGuardNotifier.buildActive(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        }
        lastPoll = System.currentTimeMillis()
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON).also {
            it.addAction(Intent.ACTION_SCREEN_OFF)
        })
        applyBallVisibility()
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAY_PAGE) {
            intent.getStringExtra(EXTRA_PAY_PACKAGE)?.let { nudgeFromDetection(it, intent.getDoubleExtra(EXTRA_PAY_AMOUNT, Double.NaN)) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        GuardDiagnosticLog.lifecycle("GUARD_DESTROYED")
        isRunning = false
        instance = null
        removePanel()
        removeBall()
        removeOverlay()
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        PurchaseGuardNotifier.cancelThink(this)
        scope.cancel()
        super.onDestroy()
    }

    // ===== 支付页面检测 =====

    private fun checkForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        // 使用事件会延迟入库：窗口小幅回溯 + 以最大事件时间戳推进，避免漏读
        val begin = (lastPoll - 2000L).coerceAtLeast(now - 60_000L)
        var latestPkg: String? = null
        var latestCls: String? = null
        var maxTs = 0L
        val resumedPages = mutableListOf<ResumedPage>()
        val events = usm.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestPkg = event.packageName
                latestCls = event.className ?: ""
                resumedPages.add(ResumedPage(event.packageName.orEmpty(), latestCls, event.timeStamp))
                maxTs = maxOf(maxTs, event.timeStamp)
            }
        }
        lastPoll = if (maxTs > 0) maxTs else (now - 1500L)
        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("guard_enabled", false)) return
        val includeCheckout = prefs.getString("guard_scope", "payment") == "checkout"
        val latest = resumedPages.lastOrNull()
        for (page in resumedPages) {
            if (VisualGuardTargets.sourceActivity(page.pkg, page.cls) &&
                page.timestamp > lastVisualResumeAt && page.timestamp in (now - 5_000L)..now && page.pkg == latest?.pkg) {
                lastVisualResumeAt = page.timestamp
                AccessibilityCaptureService.onShoppingActivityResumed(page.pkg, page.cls)
            }
            if (page.timestamp in (now - 5_000L)..now && page.pkg == latest?.pkg) {
                VisualPaymentHandoff.observe(page.pkg, page.cls)?.let { onVisualPaymentHandoff(applicationContext, page.pkg, it) }
            }
            if (page.timestamp in (now - 5_000L)..now && launchTracker.observe(page.pkg, page.timestamp) &&
                page.pkg == latest?.pkg) {
                recordDetection(page.pkg, page.cls, "检测到支付应用切换，等待页面金额")
            }
        }
        resumedPages.lastOrNull()?.let { last ->
            if (last.timestamp >= requestStartedAt) handleForegroundChange(last.pkg)
        }
        UsagePaymentEvents.candidate(resumedPages, now, seenUsageHints, includeCheckout)?.let { hint ->
            seenUsageHints.add(hint.key)
            if (seenUsageHints.size > 100) seenUsageHints.remove(seenUsageHints.first())
            recordDetection(hint.pkg, hint.cls, "支付页面候选，等待页面金额")
        }
        val fg = latestPkg ?: currentFg ?: return
        val cls = latestCls ?: currentCls ?: ""
        if (fg == currentFg && cls == currentCls) return

        val scopeMode = prefs.getString("guard_scope", "payment") ?: "payment"
        val watchedHit = fg in watchedPackages
        val payScreen = watchedHit && PaymentScreenClassifier.matches(fg, cls, includeCheckout)
        if (watchedHit) {
            Log.d("PurchaseGuard", "fg=$fg cls=$cls payScreen=$payScreen")
            recordDetection(fg, cls, if (payScreen) "使用情况类名匹配" else "使用情况类名未匹配")
        }
        val newKey = "$fg|$cls|$payScreen"
        if (newKey == lastKey) return
        val prev = currentFg
        currentFg = fg
        currentCls = cls
        lastKey = newKey

        val shouldNudge = when (scopeMode) {
            "app" -> watchedHit
            else -> payScreen
        }
        if (!shouldNudge && prev in watchedPackages && prev != fg) {
            PurchaseGuardNotifier.cancelThink(this)
        }
        debugPkg = fg
        debugCls = cls
        debugPay = payScreen
        debugUpdatedAt = now
    }

    private fun observeForeground(pkg: String, at: Long) {
        handleForegroundChange(pkg)
        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        if (launchTracker.observe(pkg, at) && prefs.getBoolean("guard_enabled", false)) {
            recordDetection(pkg, "", "检测到支付应用切换，等待页面金额")
        }
    }

    /** 无障碍通道触发的提醒入口（与 usage 检测共用冷却与浮层逻辑）。 */
    fun nudgeFromDetection(pkg: String, amount: Double, visualHandoff: Boolean = false) {
        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("guard_enabled", false)) {
            recordDetection(pkg, "", "提醒未开启")
            return
        }
        if (!CheckoutPageParser.exceedsThreshold(amount, com.mudasir.smartledger.capture.GuardThreshold.read(prefs))) {
            recordDetection(pkg, "", "金额未超过提醒门槛")
            return
        }
        val now = System.currentTimeMillis()
        if (GuardPromptPolicy.canUseTaobaoHandoff(pkg, prefs.getBoolean("guard_handoff_pending", false),
                prefs.getLong("guard_handoff_at", 0L), now, APPROVAL_COOLDOWN_MS) &&
            kotlin.math.abs(prefs.getFloat("guard_handoff_amount", -1f).toDouble() - amount) < 0.005) {
            prefs.edit().putBoolean("guard_handoff_pending", false)
                .putString("guard_last_pkg", pkg).putLong("guard_last_at", now)
                .putFloat("guard_last_amount", amount.toFloat()).apply()
            recordDetection(pkg, "", "已沿用刚确认的淘宝付款提醒")
            return
        }
        if (prefs.getString("guard_last_pkg", "") == pkg &&
            kotlin.math.abs(prefs.getFloat("guard_last_amount", -1f).toDouble() - amount) < 0.005 &&
            now - prefs.getLong("guard_last_at", 0L) < APPROVAL_COOLDOWN_MS) {
            recordDetection(pkg, "", "近期已放行，处于冷却期")
            return
        }
        if (now - lastNudgeAt < 5_000L || overlayView != null) {
            recordDetection(pkg, "", "短时去重或浮层已存在")
            return
        }
        currentFg = pkg
        lastNudgeAt = now
        requestPackage = pkg
        requestAmount = amount
        visualHandoffRequest = visualHandoff
        requestStartedAt = now
        requestGeneration++
        nudgeInternal(pkg, requestGeneration, amount)
    }

    private fun handleForegroundChange(pkg: String) {
        val origin = overlayPackage ?: requestPackage ?: return
        if (!GuardPromptPolicy.shouldCloseOnForeground(origin, pkg, packageName)) return
        requestGeneration++
        requestPackage = null
        if (overlayView != null) {
            recordDetection(origin, "", "离开原应用，关闭未完成的冷静页面")
            removeOverlay()
        } else {
            recordDetection(origin, "", "离开原应用，取消待显示提醒")
        }
    }

    private fun nudgeInternal(pkg: String, generation: Long, amount: Double) {
        scope.launch {
            val spent = runCatching {
                val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val end = cal.clone() as Calendar
                end.add(Calendar.MONTH, 1)
                dao.sumByType(
                    com.mudasir.smartledger.data.TransactionRecord.TYPE_EXPENSE,
                    cal.timeInMillis, end.timeInMillis
                )
            }.onFailure { Log.w("PurchaseGuard", "Unable to load spending total", it) }.getOrDefault(0.0)
            runCatching {
                val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
                val budget = getSharedPreferences("budget_prefs", Context.MODE_PRIVATE).getFloat("monthly_budget", 0f).toDouble()
                val pct = if (budget > 0) ((spent / budget) * 100).toInt().coerceIn(0, 100) else null
                val mode = prefs.getString("guard_mode", "overlay") ?: "overlay"
                withContext(Dispatchers.Main) {
                    if (requestGeneration != generation || requestPackage != pkg) {
                        recordDetection(pkg, "", "页面已切换，取消过期提醒")
                        return@withContext
                    }
                    if (!prefs.getBoolean("guard_enabled", false) ||
                        !CheckoutPageParser.exceedsThreshold(amount, com.mudasir.smartledger.capture.GuardThreshold.read(prefs))) {
                        recordDetection(pkg, "", "提醒开关或门槛已变化，取消提醒")
                        return@withContext
                    }
                    if (mode == "overlay" && Settings.canDrawOverlays(this@PurchaseGuardService)) {
                        showCooldownOverlay(spent, pct, pkg, amount)
                    } else {
                        recordDetection(currentFg.orEmpty(), "", if (mode == "overlay") "无悬浮窗权限，尝试通知" else "通知模式")
                        PurchaseGuardNotifier.notifyThink(applicationContext, spent, pct)
                    }
                }
            }.onFailure {
                Log.w("PurchaseGuard", "Unable to show reminder", it)
                recordFailure("创建或显示付款提醒", it)
                val causes = generateSequence(it) { error -> error.cause }
                    .take(5).joinToString(" → ") { error -> error.javaClass.simpleName }
                recordDetection(currentFg.orEmpty(), "", "提醒失败：$causes；尝试通知")
                handler.post {
                    val current = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
                    if (requestGeneration == generation && requestPackage == pkg &&
                        current.getBoolean("guard_enabled", false) && CheckoutPageParser.exceedsThreshold(
                            amount, com.mudasir.smartledger.capture.GuardThreshold.read(current))) {
                        runCatching { PurchaseGuardNotifier.notifyThink(applicationContext, spent, null) }
                    }
                }
            }
        }
    }

    // ===== 强制冷静浮层 =====

    private var overlayView: View? = null
    private var overlayAutoRemove: Runnable? = null

    private fun showCooldownOverlay(spent: Double, budgetPct: Int?, pkg: String, amount: Double) {
        if (overlayView != null) return
        val a11yWm = if (visualHandoffRequest) AccessibilityCaptureService.overlayWindowManager() else null
        val wm = a11yWm ?: getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = CooldownOverlayFactory.create(this)

        val spendText = buildString {
            append("本次待付 ¥${"%.2f".format(amount)} · 本月已支出 ¥${"%.0f".format(spent)}")
            budgetPct?.let { append(" · 预算已用 $it%") }
        }
        view.findViewById<TextView>(R.id.tvCooldownSpend).text = spendText

        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val cooldown = prefs.getInt("guard_cooldown_seconds", 5).coerceIn(3, 30)
        val tvCountdown = view.findViewById<TextView>(R.id.tvCountdown)
        val btnContinue = view.findViewById<android.widget.Button>(R.id.btnContinue)
        val btnWishlist = view.findViewById<android.widget.Button>(R.id.btnWishlist)

        var remain = cooldown
        tvCountdown.text = remain.toString()
        btnContinue.isEnabled = false
        btnContinue.alpha = 0.45f

        val tick = object : Runnable {
            override fun run() {
                remain--
                if (remain > 0) {
                    tvCountdown.text = remain.toString()
                    handler.postDelayed(this, 1000)
                } else {
                    tvCountdown.text = "🧘"
                    btnContinue.isEnabled = true
                    btnContinue.alpha = 1f
                    btnContinue.text = "我已三思，继续操作"
                    recordDetection(pkg, "", "冷静计时结束")
                }
            }
        }
        btnContinue.setOnClickListener {
            recordDetection(pkg, "", "用户点击继续")
            getSharedPreferences("guard_prefs", Context.MODE_PRIVATE).edit()
                .putString("guard_last_pkg", pkg)
                .putLong("guard_last_at", System.currentTimeMillis())
                .putFloat("guard_last_amount", amount.toFloat())
                .putBoolean("guard_handoff_pending", pkg == "com.taobao.taobao")
                .putLong("guard_handoff_at", System.currentTimeMillis())
                .putFloat("guard_handoff_amount", amount.toFloat())
                .apply()
            removeOverlay()
        }
        btnWishlist.setOnClickListener {
            recordDetection(pkg, "", "用户选择心愿")
            getSharedPreferences("guard_prefs", Context.MODE_PRIVATE).edit()
                .putString("guard_last_pkg", pkg)
                .putLong("guard_last_at", System.currentTimeMillis())
                .putFloat("guard_last_amount", amount.toFloat())
                .putBoolean("guard_handoff_pending", false)
                .apply()
            removeOverlay()
            runCatching {
                startActivity(
                    Intent(this, WishlistAddActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Toast.makeText(this, "记录后 24 小时，我们会问你还想不想买", Toast.LENGTH_LONG).show()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (a11yWm != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        runCatching {
            wm.addView(view, params)
            overlayWindowManager = wm
            overlayView = view
            overlayPackage = pkg
            overlayTick = tick
            handler.postDelayed(tick, 1000)
            recordDetection(currentFg.orEmpty(), "", "系统已接受悬浮窗请求；可见性和触控尚未验证")
            view.postDelayed({
                if (overlayView === view) {
                    recordDetection(currentFg.orEmpty(), "", "浮层状态：attached=${view.isAttachedToWindow}, shown=${view.isShown}, windowVisibility=${view.windowVisibility}；不能证明屏幕显示或触控拦截")
                }
            }, 500L)
            val autoRemove = Runnable {
                recordDetection(pkg, "", "浮层超时关闭")
                removeOverlay()
            }
            overlayAutoRemove = autoRemove
            handler.postDelayed(autoRemove, 60_000)
        }.onFailure {
            overlayView = null
            recordFailure("添加悬浮窗", it)
            recordDetection(currentFg.orEmpty(), "", "悬浮窗创建失败：${it.javaClass.simpleName}，尝试通知")
            val current = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
            if (current.getBoolean("guard_enabled", false) && CheckoutPageParser.exceedsThreshold(
                    amount, com.mudasir.smartledger.capture.GuardThreshold.read(current))) {
                PurchaseGuardNotifier.notifyThink(applicationContext, spent, budgetPct)
            }
        }
    }

    private fun removeOverlay() {
        overlayTick?.let { handler.removeCallbacks(it) }
        overlayTick = null
        overlayAutoRemove?.let { handler.removeCallbacks(it) }
        overlayAutoRemove = null
        overlayView?.let { v ->
            runCatching {
                (overlayWindowManager ?: getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        overlayView = null
        overlayWindowManager = null
        visualHandoffRequest = false
        overlayPackage = null
        requestPackage = null
    }

    // ===== 悬浮球 =====

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var ballBadge: TextView? = null
    private var dockedView: View? = null
    private var dockedParams: WindowManager.LayoutParams? = null
    @Volatile
    private var isDocked = false
    private var pendingSingleTap: Runnable? = null
    private var lastTapUpElapsed = 0L
    private var pendingCount = 0

    // 可见显示区域（排除状态栏/导航栏）——悬浮球拖动与吸边的钳制边界
    private var visLeft = 0
    private var visTop = 0
    private var visRight = 0
    private var visBottom = 0

    private fun computeVisibleBounds(view: View) {
        val r = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(r)
        if (r.width() > 0 && r.height() > 0) {
            visLeft = r.left; visTop = r.top
            visRight = r.right; visBottom = r.bottom
        }
    }

    private fun ballEnabled(): Boolean =
        getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
            .getBoolean("ball_enabled", false)

    private fun applyBallVisibility() {
        if (ballEnabled() && Settings.canDrawOverlays(this)) {
            if (isDocked) showDocked(dockRight = false, y = visTop + dp(48)) else showBall()
        } else { removePanel(); removeDocked(); removeBall() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun showBall() {
        if (ballView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        if (!Settings.canDrawOverlays(this)) return

        val container = FrameLayout(this)
        val circle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(BALL_SIZE_DP), dp(BALL_SIZE_DP)).apply {
                gravity = Gravity.CENTER
            }
            // 渐变陶土色 + 半透明白环：精致不突兀
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#F2D98273"), Color.parseColor("#F2C15F3C"))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.parseColor("#59FFFFFF"))
            }
        }
        val badge = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(-2)
                marginEnd = dp(-2)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#D67270"))
            }
            setPadding(dp(4), dp(1), dp(4), dp(1))
            textSize = 10f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        ballBadge = badge
        container.addView(circle)
        container.addView(badge)

        val size = dp(BALL_SIZE_DP)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = dp(12)
            y = resources.displayMetrics.heightPixels / 4
        }
        attachBallTouch(container, params, size)
        runCatching {
            wm.addView(container, params)
            ballView = container
            ballParams = params
            updateBallBadge()
        }.onFailure { ballView = null }
    }

    private fun removeBall() {
        ballView?.let { v ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        ballView = null
        ballParams = null
        ballBadge = null
    }

    private fun hideBall() {
        getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("ball_enabled", false).apply()
        removePanel()
        removeBall()
        Toast.makeText(this, "悬浮球已隐藏，可在设置中重新开启", Toast.LENGTH_SHORT).show()
    }

    private fun attachBallTouch(view: View, params: WindowManager.LayoutParams, sizePx: Int) {
        val conf = android.view.ViewConfiguration.get(this)
        val slop = conf.scaledTouchSlop
        var downRawX = 0f; var downRawY = 0f
        var startX = 0; var startY = 0
        var dragging = false

        // 可见显示区域（排除状态栏/导航栏）：钳制边界，防止球被拖进系统栏底下"消失"
        fun computeBounds() {
            val r = android.graphics.Rect()
            view.getWindowVisibleDisplayFrame(r)
            if (r.width() > 0 && r.height() > 0) {
                params.x = params.x.coerceIn(r.left, r.right - sizePx)
                params.y = params.y.coerceIn(r.top, r.bottom - sizePx)
            }
        }

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    computeVisibleBounds(view)
                    // 立即把越界的球拉回可见区（修复历史"消失"的球）
                    params.x = params.x.coerceIn(visLeft, (visRight - sizePx).coerceAtLeast(visLeft))
                    params.y = params.y.coerceIn(visTop, (visBottom - sizePx).coerceAtLeast(visTop))
                    runCatching {
                        (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        params.x = (startX + dx).toInt()
                            .coerceIn(visLeft, (visRight - sizePx).coerceAtLeast(visLeft))
                        params.y = (startY + dy).toInt()
                            .coerceIn(visTop, (visBottom - sizePx).coerceAtLeast(visTop))
                        runCatching {
                            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                                .updateViewLayout(view, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // 贴边收纳：松手时球中心接近左右边缘 → 收纳为半胶囊
                        val ballCenter = params.x + sizePx / 2
                        val nearLeftEdge = ballCenter < dp(28)
                        val nearRightEdge = ballCenter > resources.displayMetrics.widthPixels - dp(28)
                        if (nearLeftEdge || nearRightEdge) {
                            showDocked(dockRight = nearRightEdge, y = params.y)
                        } else {
                            snapToEdge(params, sizePx)
                            runCatching {
                                (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                                    .updateViewLayout(view, params)
                            }
                        }
                    } else handleTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, sizePx: Int) {
        val screenW = visRight - visLeft
        params.x = if (params.x + sizePx / 2 < screenW / 2) visLeft + dp(8) else visRight - sizePx - dp(8)
        params.y = params.y.coerceIn(visTop + dp(8), (visBottom - sizePx - dp(8)).coerceAtLeast(visTop))
    }

    /** 贴边半胶囊（收纳态）：点击恢复完整悬浮球。 */
    private fun showDocked(dockRight: Boolean, y: Int) {
        removeBall()
        if (dockedView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        if (!Settings.canDrawOverlays(this)) return

        val dock = View(this).apply {
            layoutParams = ViewGroup.LayoutParams(dp(14), dp(48))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                val r = dp(7).toFloat()
                cornerRadii = if (dockRight)
                    floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                else
                    floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setColor(Color.parseColor("#B3C15F3C"))
            }
        }
        dock.setOnClickListener {
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(dock)
            }
            dockedView = null
            isDocked = false
            showBall()
        }
        val params = WindowManager.LayoutParams(
            dp(14), dp(48),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = if (dockRight) resources.displayMetrics.widthPixels - dp(14) else visLeft
            this.y = y.coerceIn(visTop + dp(8), (visBottom - dp(48) - dp(8)).coerceAtLeast(visTop + dp(8)))
        }
        runCatching {
            wm.addView(dock, params)
            dockedView = dock
            dockedParams = params
            isDocked = true
        }.onFailure { dockedView = null }
    }

    private fun removeDocked() {
        dockedView?.let { v ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        dockedView = null
        dockedParams = null
        isDocked = false
    }

    private fun handleTap() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastTapUpElapsed < 300) {
            // 双击 → 直达记一笔
            pendingSingleTap?.let { handler.removeCallbacks(it) }
            pendingSingleTap = null
            lastTapUpElapsed = 0
            openAddEdit()
        } else {
            lastTapUpElapsed = now
            val singleTap = Runnable {
                pendingSingleTap = null
                togglePanel()
            }
            pendingSingleTap = singleTap
            handler.postDelayed(singleTap, 280)
        }
    }

    private fun openAddEdit() {
        removePanel()
        runCatching {
            startActivity(
                Intent(this, AddEditTransactionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun togglePanel() {
        if (panelView != null) { removePanel(); return }
        val ball = ballView ?: return
        val bp = ballParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val panel = buildPanelView()
        val width = dp(PANEL_WIDTH_DP)
        val side = if (bp.x + dp(BALL_SIZE_DP) / 2 < resources.displayMetrics.widthPixels / 2) 1 else -1
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = if (side == 1) bp.x + dp(BALL_SIZE_DP) + dp(8) else bp.x - width - dp(8)
            x = x.coerceAtLeast(dp(8))
            y = bp.y
        }
        runCatching {
            wm.addView(panel, params)
            panelView = panel
            panelParams = params
        }
    }

    private fun removePanel() {
        panelView?.let { v ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        panelView = null
        panelParams = null
    }

    private fun buildPanelView(): View {
        val ctx = this
        val density = resources.displayMetrics.density
        fun dpx(v: Int) = (v * density).toInt()
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F22A2828"))
                cornerRadius = dpx(14).toFloat()
            }
            setPadding(dpx(6), dpx(4), dpx(6), dpx(4))
        }
        fun row(label: String, onClick: () -> Unit) {
            panel.addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dpx(14), dpx(12), dpx(14), dpx(12))
                background = ctx.obtainStyledAttributes(
                    intArrayOf(android.R.attr.selectableItemBackground)
                ).use { it.getDrawable(0) }
                setOnClickListener {
                    onClick()
                    removePanel()
                }
            })
        }
        row("✏️ 记一笔") { openAddEdit() }
        row("📥 收件箱${if (pendingCount > 0) " ($pendingCount)" else ""}") {
            runCatching {
                startActivity(
                    Intent(ctx, CaptureInboxActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        row("📌 贴边收纳") {
            removePanel()
            val bp = ballParams ?: return@row
            val nearRight = bp.x + dp(BALL_SIZE_DP) / 2 > resources.displayMetrics.widthPixels / 2
            removeBall()
            showDocked(dockRight = nearRight, y = bp.y)
        }
        row("🧘 想好再买：${if (ballEnabled()) "已开启" else "仅悬浮球"}") {
            // 语义：跳转到设置页调整（避免面板内误关守护）
            runCatching {
                startActivity(
                    Intent(ctx, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        row("✕ 隐藏悬浮球") { hideBall() }
        return panel
    }

    private fun updateBallBadge() {
        val ball = ballView ?: return
        scope.launch {
            val count = runCatching {
                AppDatabase.getDatabase(applicationContext).transactionDao().countPending()
            }.getOrDefault(0)
            pendingCount = count
            handler.post {
                ballBadge?.apply {
                    visibility = if (count > 0) View.VISIBLE else View.GONE
                    text = if (count > 99) "99+" else count.toString()
                }
            }
        }
    }

}

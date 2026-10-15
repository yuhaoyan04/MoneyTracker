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

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            if (screenOn) handler.postDelayed(pollRunnable, 200L)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (screenOn) runCatching { checkForeground() }
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        instance = null
        removePanel()
        removeBall()
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
        val events = usm.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestPkg = event.packageName
                latestCls = event.className ?: ""
                maxTs = maxOf(maxTs, event.timeStamp)
            }
        }
        lastPoll = if (maxTs > 0) maxTs else (now - 1500L)
        val fg = latestPkg ?: currentFg ?: return
        val cls = latestCls ?: currentCls ?: ""
        if (fg == currentFg && cls == currentCls) return

        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val scopeMode = prefs.getString("guard_scope", "payment") ?: "payment"
        val watchedHit = fg in watchedPackages
        val payScreen = watchedHit && isPaymentScreen(fg, cls)
        if (watchedHit) {
            Log.d("PurchaseGuard", "fg=$fg cls=$cls payScreen=$payScreen")
            pushHistory(fg, cls, payScreen)
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
        if (shouldNudge) {
            // 2 分钟授权冷却：确认放行后同一支付意图不重复拦截（持久化，跨进程重启有效）
            val lastPkg = prefs.getString("guard_last_pkg", "")
            val lastAt = prefs.getLong("guard_last_at", 0L)
            if (fg == lastPkg && now - lastAt < APPROVAL_COOLDOWN_MS) return
            lastNudgeAt = now
            prefs.edit()
                .putString("guard_last_pkg", fg)
                .putLong("guard_last_at", now)
                .apply()
            nudge()
        } else if (prev in watchedPackages) {
            PurchaseGuardNotifier.cancelThink(this)
        }
        debugPkg = fg
        debugCls = cls
        debugPay = payScreen
        debugUpdatedAt = now
        debugHistory = historyDeque.toList()
    }

    private fun isPaymentScreen(pkg: String, cls: String): Boolean {
        val c = cls.lowercase()
        if (c.contains("alipay") && (c.contains("pay") || c.contains("cashier"))) return true
        if (c.contains("cashier")) return true
        return when {
            pkg == "com.tencent.mm" ->
                listOf("offline", "remittance", "wallet.pay", "pay").any { c.contains(it) }
            pkg.startsWith("com.eg.android.AlipayGphone") ->
                listOf("payui", "payment", "pay").any { c.contains(it) }
            else -> listOf("pay", "trade").any { c.contains(it) }
        }
    }

    private fun pushHistory(pkg: String, cls: String, pay: Boolean) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        historyDeque.addLast("$stamp ${pkg.substringAfterLast('.')} | ${cls.substringAfterLast('.')} | ${if (pay) "✓支付页" else "—"}")
        while (historyDeque.size > 12) historyDeque.removeFirst()
    }

    private fun nudge() {
        scope.launch {
            runCatching {
                val dao = AppDatabase.getDatabase(applicationContext).transactionDao()
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                val end = cal.clone() as Calendar
                end.add(Calendar.MONTH, 1)
                val spent = dao.sumByType(
                    com.mudasir.smartledger.data.TransactionRecord.TYPE_EXPENSE,
                    cal.timeInMillis, end.timeInMillis
                )
                val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
                val budget = prefs.getFloat("monthly_budget", 0f).toDouble()
                val pct = if (budget > 0) ((spent / budget) * 100).toInt().coerceIn(0, 100) else null
                val mode = prefs.getString("guard_mode", "overlay") ?: "overlay"
                withContext(Dispatchers.Main) {
                    if (mode == "overlay" && Settings.canDrawOverlays(this@PurchaseGuardService)) {
                        showCooldownOverlay(spent, pct)
                    } else {
                        PurchaseGuardNotifier.notifyThink(applicationContext, spent, pct)
                    }
                }
            }
        }
    }

    // ===== 强制冷静浮层 =====

    private var overlayView: View? = null
    private var overlayAutoRemove: Runnable? = null

    private fun showCooldownOverlay(spent: Double, budgetPct: Int?) {
        if (overlayView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = android.view.LayoutInflater.from(this)
            .inflate(R.layout.view_cooldown_overlay, null)

        val spendText = buildString {
            append("本月已支出 ¥${"%.0f".format(spent)}")
            budgetPct?.let { append(" · 预算已用 $it%") }
        }
        view.findViewById<TextView>(R.id.tvCooldownSpend).text = spendText

        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val cooldown = prefs.getInt("guard_cooldown_seconds", 5).coerceIn(3, 30)
        val tvCountdown = view.findViewById<TextView>(R.id.tvCountdown)
        val btnContinue = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnContinue)
        val btnWishlist = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnWishlist)

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
                    btnContinue.text = "我已三思，继续付款"
                }
            }
        }
        handler.postDelayed(tick, 1000)

        btnContinue.setOnClickListener {
            getSharedPreferences("guard_prefs", Context.MODE_PRIVATE).edit()
                .putString("guard_last_pkg", currentFg ?: "")
                .putLong("guard_last_at", System.currentTimeMillis())
                .apply()
            removeOverlay()
        }
        btnWishlist.setOnClickListener {
            getSharedPreferences("guard_prefs", Context.MODE_PRIVATE).edit()
                .putString("guard_last_pkg", currentFg ?: "")
                .putLong("guard_last_at", System.currentTimeMillis())
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        runCatching {
            wm.addView(view, params)
            overlayView = view
            val autoRemove = Runnable { removeOverlay() }
            overlayAutoRemove = autoRemove
            handler.postDelayed(autoRemove, 60_000)
        }.onFailure { overlayView = null }
    }

    private fun removeOverlay() {
        overlayAutoRemove?.let { handler.removeCallbacks(it) }
        overlayAutoRemove = null
        overlayView?.let { v ->
            runCatching {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            }
        }
        overlayView = null
    }

    // ===== 悬浮球 =====

    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var ballBadge: TextView? = null
    private var pendingSingleTap: Runnable? = null
    private var lastTapUpElapsed = 0L
    private var pendingCount = 0

    private fun ballEnabled(): Boolean =
        getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
            .getBoolean("ball_enabled", false)

    private fun applyBallVisibility() {
        if (ballEnabled() && Settings.canDrawOverlays(this)) showBall() else { removePanel(); removeBall() }
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6C15F3C"))
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            x = dp(12)
            y = resources.displayMetrics.heightPixels / 3
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

        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels
                        params.x = (startX + dx).toInt().coerceIn(0, screenW - sizePx)
                        params.y = (startY + dy).toInt().coerceIn(0, screenH - sizePx)
                        runCatching {
                            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                                .updateViewLayout(view, params)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdge(params, sizePx)
                        runCatching {
                            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                                .updateViewLayout(view, params)
                        }
                    } else handleTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, sizePx: Int) {
        val screenW = resources.displayMetrics.widthPixels
        params.x = if (params.x + sizePx / 2 < screenW / 2) dp(8) else screenW - sizePx - dp(8)
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

    // ===== 检测自检数据 =====

    private val historyDeque = ArrayDeque<String>()

    /** 检测自检：守护服务最近一次识别到的前台页面与判定（设置页展示用）。 */
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
}

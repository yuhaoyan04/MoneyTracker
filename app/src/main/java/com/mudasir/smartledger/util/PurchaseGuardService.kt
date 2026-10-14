package com.mudasir.smartledger.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ServiceCompat
import android.widget.Toast
import com.mudasir.smartledger.R
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
 * 「想好再买」守护服务（前台服务 + UsageStats）：
 *
 * 用户进入微信/支付宝等支付应用时，立即弹出冷静提醒
 * （本月已支出语境 + 预算进度），离开支付应用自动撤下。
 *
 * 平台约束说明：
 * - Android 不允许第三方 App 在付款确认前读取金额/商户，因此提醒基于
 *   「进入支付应用」这一语境，而非具体订单 —— 这是隐私与能力的诚实边界
 * - 使用 UsageStatsManager（使用情况访问权限），稳定性远好于无障碍方案
 * - 屏幕熄灭时暂停轮询以省电
 */
class PurchaseGuardService : Service() {

    companion object {
        const val CHANNEL_ACTIVE = PurchaseGuardNotifier.CHANNEL_ACTIVE

        /** 同进程内可读的运行态（设置页状态展示用）。 */
        @Volatile
        var isRunning = false
            private set

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
        private const val MIN_NUDGE_INTERVAL_MS = 5 * 60_000L
        private const val NOTIFICATION_ID = 2001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private var lastPoll = 0L
    private var currentFg: String? = null
    private var currentCls: String? = null
    private var lastKey = ""
    private var lastNudgeAt = 0L
    private var screenOn = true

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            if (screenOn) handler.postDelayed(pollRunnable, 200L)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (screenOn) runCatching { checkForeground() }
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        PurchaseGuardNotifier.ensureChannels(this)
        // 前台服务类型：specialUse —— 支付语境提醒无法归入其他 FGS 类型
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
        handler.post(pollRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        PurchaseGuardNotifier.cancelThink(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun checkForeground() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val begin = if (lastPoll in 1 until now - 1000) lastPoll else now - 10_000
        var latestPkg: String? = null
        var latestCls: String? = null
        val events = usm.queryEvents(begin, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latestPkg = event.packageName
                latestCls = event.className ?: ""
            }
        }
        lastPoll = now
        val fg = latestPkg ?: currentFg ?: return
        val cls = latestCls ?: currentCls ?: ""
        if (fg == currentFg && cls == currentCls) return

        val prefs = getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val scopeMode = prefs.getString("guard_scope", "payment") ?: "payment"
        val watchedHit = fg in watchedPackages
        val payScreen = watchedHit && isPaymentScreen(fg, cls)
        // 状态键：包名 + 是否支付界面（精确模式下同 App 内进入付款页也要触发）
        val newKey = "$fg|$payScreen"
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
            // 精确模式下同款页面 60 秒内只提醒一次；宽松模式 5 分钟
            val interval = if (scopeMode == "app") 5 * 60_000L else 60_000L
            if (now - lastNudgeAt >= interval) {
                lastNudgeAt = now
                nudge()
            }
        } else if (prev in watchedPackages) {
            // 离开支付应用/支付页面 → 撤下提醒
            PurchaseGuardNotifier.cancelThink(this)
        }
    }

    /**
     * 支付界面判定（按 Activity 类名关键词，大小写不敏感）：
     * - 微信：付款码(offline) / 转账(remittance) / 收付款入口(wallet.pay)
     * - 支付宝及其它：收银台(cashier) / 支付页(pay)
     * 各版本类名可能变化，若失效应切换到设置中的「宽松模式」。
     */
    private fun isPaymentScreen(pkg: String, cls: String): Boolean {
        val c = cls.lowercase()
        return when {
            pkg == "com.tencent.mm" ->
                listOf("offline", "remittance", "wallet.pay").any { c.contains(it) }
            pkg.startsWith("com.eg.android.AlipayGphone") ->
                listOf("cashier", "payui", "payment", "pay").any { c.contains(it) }
            else -> listOf("cashier", "pay").any { c.contains(it) }
        }
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

    // ===== 全屏强制冷静浮层 =====

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
                    btnContinue.text = "我已三思，继续"
                }
            }
        }
        handler.postDelayed(tick, 1000)

        btnContinue.setOnClickListener { removeOverlay() }
        btnWishlist.setOnClickListener {
            removeOverlay()
            // SYSTEM_ALERT_WINDOW 已授权 → 允许从后台启动输入页（平台豁免）
            runCatching {
                startActivity(
                    Intent(this@PurchaseGuardService, WishlistAddActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                Toast.makeText(this@PurchaseGuardService, "记录后 24 小时，我们会问你还想不想买", Toast.LENGTH_LONG).show()
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
            // 安全阀：60 秒无操作自动移除，绝不困住用户
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
}

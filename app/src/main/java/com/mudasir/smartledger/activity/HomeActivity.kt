package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.mudasir.smartledger.R
import com.mudasir.smartledger.adapter.TransactionAdapter
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.BottomNavHelper
import com.mudasir.smartledger.util.FormatUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class HomeActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }

    private lateinit var adapter: TransactionAdapter
    private var allRecords: List<TransactionRecord> = emptyList()
    private var searchQuery: String = ""
    private var currentExpense: Double = 0.0

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // 前台定位（粗略/精确任一）已授 → 引导后台定位（支付发生在后台，必需）
        if (grants.values.any { it }) maybeRequestBackgroundLocation()
    }

    private val bgLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPendingNotification()
    }

    /** 后台定位只主动引导一次，避免反复打扰；之后可在设置 → 自动抓取中开启。 */
    private fun maybeRequestBackgroundLocation() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        val prefs = getSharedPreferences("perm_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("bg_loc_asked", false)) return
        prefs.edit().putBoolean("bg_loc_asked", true).apply()
        bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun refreshPendingNotification() {
        lifecycleScope.launch {
            runCatching { com.mudasir.smartledger.util.PendingNotifier.update(this@HomeActivity) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        locationLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        )
        // Android 13+ 通知权限（待确认常驻通知需要）
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 自愈：启动时若有待确认交易，确保常驻通知存在
        refreshPendingNotification()

        adapter = TransactionAdapter { r ->
            startActivity(Intent(this, AddEditTransactionActivity::class.java).putExtra(AddEditTransactionActivity.EXTRA_ID, r.id))
        }

        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvRecent).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = this@HomeActivity.adapter
            isNestedScrollingEnabled = false
        }

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            startActivity(Intent(this, AddEditTransactionActivity::class.java))
        }
        findViewById<View>(R.id.tvSeeStats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        findViewById<TextInputEditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applySearch()
            }
        })

        findViewById<View>(R.id.balanceCard).setOnLongClickListener {
            showBudgetDialog()
            true
        }

        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_home)

        com.mudasir.smartledger.util.BackupWorker.schedulePeriodic(this)
        com.mudasir.smartledger.util.SilentConfirmWorker.schedulePeriodic(this)

        observeData()
    }

    private fun observeData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cats = db.categoryDao().getAll()
            val map = cats.filter { it.parentName != null }.associate { it.name to it.parentName!! }
            withContext(Dispatchers.Main) {
                adapter.parentMap = map
            }
        }

        lifecycleScope.launch {
            db.transactionDao().observeRecent(100).collectLatest { list ->
                allRecords = list
                applySearch()
                findViewById<View>(R.id.tvEmptyRecent).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                refreshSummary()
            }
        }
        lifecycleScope.launch {
            db.transactionDao().observePendingCount().collectLatest {
                findViewById<android.widget.TextView>(R.id.tvPending).text = it.toString()
                // 待确认数量变化 → 常驻通知自动同步（显示/更新/消失）
                runCatching { com.mudasir.smartledger.util.PendingNotifier.update(this@HomeActivity) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 底部导航选中态校正（REORDER_TO_FRONT 复用时 onCreate 不执行）
        com.mudasir.smartledger.util.BottomNavHelper.sync(
            findViewById(R.id.bottomNav), R.id.nav_tab_home
        )
        // 自愈：回到首页时同步常驻通知（重启/系统清除后重发）
        refreshPendingNotification()
    }

    private fun applySearch() {
        val filtered = if (searchQuery.isBlank()) {
            allRecords
        } else {
            val q = searchQuery.lowercase()
            allRecords.filter { r ->
                r.merchant?.lowercase()?.contains(q) == true ||
                r.categoryName.lowercase().contains(q) ||
                r.channelName.lowercase().contains(q) ||
                r.note?.lowercase()?.contains(q) == true ||
                r.locationName?.lowercase()?.contains(q) == true
            }
        }
        adapter.submitList(filtered.take(40))
    }

    private suspend fun refreshSummary() = withContext(Dispatchers.IO) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis

        val income = db.transactionDao().sumByType(TransactionRecord.TYPE_INCOME, start, end)
        val expense = db.transactionDao().sumByType(TransactionRecord.TYPE_EXPENSE, start, end)
        currentExpense = expense
        // 迷你趋势：本月每日支出
        val monthRecords = db.transactionDao().getInRange(start, end)
        val daily = IntArray(31)
        monthRecords.filter { it.type == TransactionRecord.TYPE_EXPENSE }.forEach {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = it.timestamp }
            val d = c.get(java.util.Calendar.DAY_OF_MONTH)
            if (d in 1..31) daily[d - 1] += it.amount.toInt()
        }
        val now = java.util.Calendar.getInstance()
        withContext(Dispatchers.Main) {
            findViewById<android.widget.TextView>(R.id.tvMonthLabel).text =
                FormatUtil.monthLabel(now.get(Calendar.YEAR), now.get(Calendar.MONTH)) + " · 结余"
            animateBalance(income - expense)
            findViewById<android.widget.TextView>(R.id.tvIncome).text = FormatUtil.money(income)
            findViewById<android.widget.TextView>(R.id.tvExpense).text = FormatUtil.money(expense)
            updateBudgetProgress(expense)
            renderMiniTrend(daily, now.get(java.util.Calendar.DAY_OF_MONTH), expense)
        }
    }

    /** 余额卡内的迷你支出趋势线：极简风格（无轴/无图例/细线+渐变填充），点击跳统计页。 */
    private fun renderMiniTrend(daily: IntArray, todayDom: Int, monthExpense: Double) {
        val section = findViewById<View>(R.id.miniTrendSection)
        val chart = findViewById<com.github.mikephil.charting.charts.LineChart>(R.id.miniTrendChart)
        val days = todayDom.coerceIn(1, 31)
        val entries = (0 until days).map { com.github.mikephil.charting.data.Entry(it.toFloat(), daily[it].toFloat()) }
        if (entries.size < 2 || monthExpense <= 0.0) {
            section.visibility = View.GONE
            return
        }
        findViewById<TextView>(R.id.tvTrendDailyAvg).text = "日均 ${FormatUtil.money(monthExpense / days)}"

        val lineColorRes = ContextCompat.getColor(this, R.color.teal_main)
        val fillColorRes = ContextCompat.getColor(this, R.color.teal_light)
        val dataSet = com.github.mikephil.charting.data.LineDataSet(entries, "").apply {
            color = lineColorRes
            lineWidth = 1.5f
            setDrawCircles(false)
            setDrawValues(false)
            mode = com.github.mikephil.charting.data.LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = fillColorRes
            fillAlpha = 90
        }
        chart.apply {
            data = com.github.mikephil.charting.data.LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            axisLeft.isEnabled = false
            axisRight.isEnabled = false
            xAxis.isEnabled = false
            axisLeft.setDrawGridLines(false)
            xAxis.setDrawGridLines(false)
            setViewPortOffsets(0f, 4f, 0f, 4f)
            setTouchEnabled(false)
            setDragEnabled(false)
            setScaleEnabled(false)
            invalidate()
        }
        section.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }
        section.visibility = View.VISIBLE
    }

    private var currentBalanceValue = 0.0

    private fun animateBalance(target: Double) {
        val tv = findViewById<android.widget.TextView>(R.id.tvBalance)
        if (kotlin.math.abs(target - currentBalanceValue) < 0.005) {
            tv.text = FormatUtil.money(target)
            return
        }
        val animator = android.animation.ValueAnimator.ofFloat(currentBalanceValue.toFloat(), target.toFloat())
        animator.duration = 650
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { anim ->
            tv.text = FormatUtil.money((anim.animatedValue as Float).toDouble())
        }
        animator.start()
        currentBalanceValue = target
    }

    private fun updateBudgetProgress(expense: Double) {
        val prefs = getSharedPreferences("budget_prefs", Context.MODE_PRIVATE)
        val budget = prefs.getFloat("monthly_budget", 0f).toDouble()
        val budgetSection = findViewById<View>(R.id.budgetSection)
        if (budget <= 0) {
            budgetSection.visibility = View.GONE
            return
        }
        budgetSection.visibility = View.VISIBLE
        val pct = if (budget > 0) ((expense / budget) * 100).toInt().coerceIn(0, 100) else 0
        findViewById<TextView>(R.id.tvBudgetInfo).text =
            "${FormatUtil.money(expense)} / ${FormatUtil.money(budget)}"
        val bar = findViewById<ProgressBar>(R.id.budgetProgress)
        bar.progress = pct
        val color = when {
            pct >= 90 -> ContextCompat.getColor(this, R.color.color_expense)
            pct >= 70 -> ContextCompat.getColor(this, R.color.color_warning)
            else -> ContextCompat.getColor(this, R.color.color_income)
        }
        bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun showBudgetDialog() {
        val prefs = getSharedPreferences("budget_prefs", Context.MODE_PRIVATE)
        val current = prefs.getFloat("monthly_budget", 0f)
        val et = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "如 5000"
            setText(if (current > 0) current.toInt().toString() else "")
            setSelection(text.length)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 24, 56, 0)
            addView(et)
        }
        AlertDialog.Builder(this)
            .setTitle("设置月度支出预算")
            .setMessage("长按余额卡片可随时修改。设置后将显示进度条。")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val value = et.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
                prefs.edit().putFloat("monthly_budget", value.toFloat()).apply()
                updateBudgetProgress(currentExpense)
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("清除") { _, _ ->
                prefs.edit().remove("monthly_budget").apply()
                updateBudgetProgress(currentExpense)
            }
            .show()
    }
}

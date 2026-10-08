package com.mudasir.smartledger.activity

import android.content.res.Configuration
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.AiHelper
import com.mudasir.smartledger.util.AiSettings
import com.mudasir.smartledger.util.BottomNavHelper
import com.mudasir.smartledger.util.FormatUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class TimeRange(val days: Int) { YEAR(365), QUARTER(90), MONTH(30), WEEK(7), DAY(1) }
enum class DataAgg { DAY, WEEK, MONTH, QUARTER }

class StatsActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var pieChart: PieChart
    private lateinit var lineChart: LineChart
    private lateinit var barChartHourly: BarChart
    private lateinit var llBreakdown: LinearLayout
    private lateinit var sectionCategory: View
    private lateinit var sectionTrend: View
    private lateinit var tvTrendSummary: TextView
    private lateinit var toggleAgg: MaterialButtonToggleGroup

    private val cal = Calendar.getInstance()
    private var year: Int = cal.get(Calendar.YEAR)
    private var month: Int = cal.get(Calendar.MONTH)

    private var records: List<TransactionRecord> = emptyList()
    private var expenseByCat: List<Pair<String, Double>> = emptyList()
    private var incomeByCat: List<Pair<String, Double>> = emptyList()
    private var totalExpense: Double = 0.0
    private var totalIncome: Double = 0.0
    private var catColors: Map<String, Int> = emptyMap()
    private var pieModeExpense: Boolean = true

    private var currentTimeRange: TimeRange = TimeRange.MONTH
    private var currentDataAgg: DataAgg = DataAgg.DAY
    private var trendLoaded = false
    private var suppressAgg = false

    // 语义化色板：低饱和高级暖色系（饼图/柱状图/明细共用）
    private val rootColorMap = mapOf(
        "餐饮" to "#C15F3C", "交通" to "#6B8CAE", "网购" to "#9C6B8F",
        "日用" to "#94A374", "娱乐" to "#CE9B4E", "医疗" to "#C07A8A",
        "居住" to "#8C9BAB", "通讯" to "#55917F", "教育" to "#6D7BB5",
        "其他" to "#A8A29A",
        "工资" to "#7B9E6B", "理财" to "#55917F", "红包" to "#C07A8A",
        "退款" to "#6B8CAE", "其他收入" to "#A8A29A"
    )
    private val fallbackPalette = intArrayOf(
        Color.parseColor("#C15F3C"), Color.parseColor("#6B8CAE"), Color.parseColor("#9C6B8F"),
        Color.parseColor("#94A374"), Color.parseColor("#CE9B4E"), Color.parseColor("#C07A8A"),
        Color.parseColor("#8C9BAB"), Color.parseColor("#55917F"), Color.parseColor("#6D7BB5"),
        Color.parseColor("#A8A29A"), Color.parseColor("#B08968"), Color.parseColor("#7F9BA6")
    )

    /** 趋势图/柱状图点击气泡：显示「标签 ¥金额」。 */
    private class TrendMarkerView(context: Context, val labelOf: (Int) -> String) :
        com.github.mikephil.charting.components.MarkerView(context, R.layout.marker_trend) {
        private val tv = findViewById<TextView>(R.id.tvMarker)
        override fun refreshContent(e: com.github.mikephil.charting.data.Entry?, highlight: com.github.mikephil.charting.highlight.Highlight?) {
            val idx = e?.x?.toInt() ?: 0
            tv.text = "${labelOf(idx)}  ${FormatUtil.money((e?.y ?: 0f).toDouble())}"
            super.refreshContent(e, highlight)
        }
        override fun getOffset(): com.github.mikephil.charting.utils.MPPointF =
            com.github.mikephil.charting.utils.MPPointF.getInstance(-(width / 2f), -height - 14f)
    }

    // 趋势图主题色
    private val trendLineColor = Color.parseColor("#FF5252")
    private val trendFillTop = Color.parseColor("#33FF5252")
    private val trendFillBottom = Color.parseColor("#05FF5252")

    // 待渲染数据
    private data class PendingLineData(val entries: List<Entry>, val labels: List<String>, val total: Float, val agg: DataAgg)
    private data class PendingBarData(val byHour: FloatArray, val total: Float)
    private var pendingLineData: Any? = null
    private var pendingBarData: Any? = null

    // ===== Lifecycle =====

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }
        pieChart = findViewById(R.id.pieChart)
        lineChart = findViewById(R.id.lineChartTrend)
        barChartHourly = findViewById(R.id.barChartHourly)
        llBreakdown = findViewById(R.id.llBreakdown)
        sectionCategory = findViewById(R.id.sectionCategory)
        sectionTrend = findViewById(R.id.sectionTrend)
        tvTrendSummary = findViewById(R.id.tvTrendSummary)
        toggleAgg = findViewById(R.id.toggleAgg)

        findViewById<View>(R.id.btnPrev).setOnClickListener { shiftMonth(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { shiftMonth(1) }
        findViewById<View>(R.id.btnAi).setOnClickListener { runAiAnalysis() }

        findViewById<MaterialButtonToggleGroup>(R.id.toggleDim).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sectionCategory.visibility = if (checkedId == R.id.btnDimCategory) View.VISIBLE else View.GONE
            sectionTrend.visibility = if (checkedId == R.id.btnDimTrend) View.VISIBLE else View.GONE
            if (checkedId == R.id.btnDimTrend) {
                if (!trendLoaded) {
                    trendLoaded = true
                    updateAggUI()
                }
                loadTrend()
            }
        }
        findViewById<MaterialButtonToggleGroup>(R.id.togglePie).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            pieModeExpense = checkedId == R.id.btnPieExpense
            renderPie(); renderBreakdown()
        }

        findViewById<MaterialButtonToggleGroup>(R.id.toggleRange).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentTimeRange = when (checkedId) {
                R.id.btnRangeYear -> TimeRange.YEAR
                R.id.btnRangeQuarter -> TimeRange.QUARTER
                R.id.btnRangeMonth -> TimeRange.MONTH
                R.id.btnRangeWeek -> TimeRange.WEEK
                R.id.btnRangeDay -> TimeRange.DAY
                else -> TimeRange.MONTH
            }
            updateAggUI()
            loadTrend()
        }

        toggleAgg.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressAgg) return@addOnButtonCheckedListener
            currentDataAgg = when (checkedId) {
                R.id.btnAggDay -> DataAgg.DAY
                R.id.btnAggWeek -> DataAgg.WEEK
                R.id.btnAggMonth -> DataAgg.MONTH
                R.id.btnAggQuarter -> DataAgg.QUARTER
                else -> DataAgg.DAY
            }
            loadTrend()
        }

        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_stats)
        load()
    }

    override fun onResume() {
        super.onResume()
        BottomNavHelper.sync(findViewById(R.id.bottomNav), R.id.nav_tab_stats)
        load()
        if (trendLoaded && sectionTrend.visibility == View.VISIBLE) {
            loadTrend()
        }
    }

    // ===== Navigation =====

    private fun shiftMonth(delta: Int) {
        cal.set(year, month, 1); cal.add(Calendar.MONTH, delta)
        year = cal.get(Calendar.YEAR); month = cal.get(Calendar.MONTH)
        load()
    }

    // ===== Category helpers =====

    private fun parentOf(catByName: Map<String, Category>, name: String): String {
        val raw = name.ifBlank { "未分类" }
        return catByName[raw]?.parentName ?: raw
    }

    private fun validAggs(range: TimeRange): List<DataAgg> = when (range) {
        TimeRange.YEAR -> listOf(DataAgg.DAY, DataAgg.WEEK, DataAgg.MONTH, DataAgg.QUARTER)
        TimeRange.QUARTER -> listOf(DataAgg.DAY, DataAgg.WEEK, DataAgg.MONTH)
        TimeRange.MONTH -> listOf(DataAgg.DAY, DataAgg.WEEK)
        TimeRange.WEEK -> listOf(DataAgg.DAY)
        TimeRange.DAY -> emptyList()
    }

    private fun defaultAgg(range: TimeRange): DataAgg = when (range) {
        TimeRange.YEAR -> DataAgg.MONTH
        TimeRange.QUARTER -> DataAgg.WEEK
        TimeRange.MONTH -> DataAgg.DAY
        TimeRange.WEEK -> DataAgg.DAY
        TimeRange.DAY -> DataAgg.DAY
    }

    private fun updateAggUI() {
        val range = currentTimeRange
        if (range == TimeRange.DAY || range == TimeRange.WEEK) {
            toggleAgg.visibility = View.GONE
            currentDataAgg = DataAgg.DAY
        } else {
            toggleAgg.visibility = View.VISIBLE
            val valid = validAggs(range)
            findViewById<MaterialButton>(R.id.btnAggDay).isEnabled = DataAgg.DAY in valid
            findViewById<MaterialButton>(R.id.btnAggWeek).isEnabled = DataAgg.WEEK in valid
            findViewById<MaterialButton>(R.id.btnAggMonth).isEnabled = DataAgg.MONTH in valid
            findViewById<MaterialButton>(R.id.btnAggQuarter).isEnabled = DataAgg.QUARTER in valid
            if (currentDataAgg !in valid) {
                currentDataAgg = defaultAgg(range)
                val btnId = when (currentDataAgg) {
                    DataAgg.DAY -> R.id.btnAggDay
                    DataAgg.WEEK -> R.id.btnAggWeek
                    DataAgg.MONTH -> R.id.btnAggMonth
                    DataAgg.QUARTER -> R.id.btnAggQuarter
                }
                suppressAgg = true
                toggleAgg.check(btnId)
                suppressAgg = false
            }
        }
    }

    // ===== Data loading =====

    private fun load() {
        findViewById<TextView>(R.id.tvMonth).text = FormatUtil.monthLabel(year, month)
        lifecycleScope.launch(Dispatchers.IO) {
            val (start, end) = FormatUtil.monthRange(year, month)
            records = db.transactionDao().getInRange(start, end)
            // 上月数据（环比洞察用）
            val lastCal = Calendar.getInstance().apply { set(year, month, 1) }
            lastCal.add(Calendar.MONTH, -1)
            val (lastStart, lastEnd) = FormatUtil.monthRange(lastCal.get(Calendar.YEAR), lastCal.get(Calendar.MONTH))
            val lastExpense = db.transactionDao().sumByType(TransactionRecord.TYPE_EXPENSE, lastStart, lastEnd)
            val cats = db.categoryDao().getAll()
            val catByName = cats.associateBy { it.name }
            totalIncome = records.filter { it.type == TransactionRecord.TYPE_INCOME }.sumOf { it.amount }
            totalExpense = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }.sumOf { it.amount }
            expenseByCat = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }
                .groupBy { parentOf(catByName, it.categoryName) }
                .map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second }
            incomeByCat = records.filter { it.type == TransactionRecord.TYPE_INCOME }
                .groupBy { parentOf(catByName, it.categoryName) }
                .map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second }
            val pieRoots = (expenseByCat.map { it.first } + incomeByCat.map { it.first }).distinct()
            catColors = buildSemanticColors(pieRoots)
            val insights = buildInsights(lastExpense, catByName)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                findViewById<TextView>(R.id.tvSumIncome).text = FormatUtil.money(totalIncome)
                findViewById<TextView>(R.id.tvSumExpense).text = FormatUtil.money(totalExpense)
                findViewById<TextView>(R.id.tvSumBalance).text = FormatUtil.money(totalIncome - totalExpense)
                findViewById<View>(R.id.tvEmpty).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                renderInsights(insights)
                renderPie(); renderBreakdown()
            }
        }
    }

    /** 本地规则洞察：环比 / 头部分类 / 日均 / 最大单笔 / 笔数。纯离线，无 AI 依赖。 */
    private fun buildInsights(lastExpense: Double, catByName: Map<String, Category>): List<String> {
        val expenses = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }
        if (expenses.isEmpty()) return emptyList()
        val out = mutableListOf<String>()

        // 环比
        if (lastExpense > 0.01) {
            val diff = totalExpense - lastExpense
            val pct = (diff / lastExpense * 100).toInt()
            out.add(
                if (diff >= 0) "支出比上月增长 ${pct}%（${FormatUtil.money(diff)}）"
                else "支出比上月节省 ${-pct}%（${FormatUtil.money(-diff)}）"
            )
        }

        // 头部分类
        val top = expenseByCat.firstOrNull()
        if (top != null && totalExpense > 0) {
            val share = (top.second / totalExpense * 100).toInt()
            out.add("最大支出：${top.first} ${FormatUtil.money(top.second)}（占 ${share}%）")
        }

        // 日均（仅当月有意义，其他月份按 30 天算）
        val days = if (year == Calendar.getInstance().get(Calendar.YEAR) && month == Calendar.getInstance().get(Calendar.MONTH))
            Calendar.getInstance().get(Calendar.DAY_OF_MONTH) else 30
        if (days > 0) out.add("日均支出 ${FormatUtil.money(totalExpense / days)}")

        // 最大单笔
        val biggest = expenses.maxByOrNull { it.amount }
        if (biggest != null && biggest.amount > 0) {
            val label = biggest.merchant?.takeIf { it.isNotBlank() } ?: parentOf(catByName, biggest.categoryName)
            out.add("最大单笔：${FormatUtil.money(biggest.amount)}（$label）")
        }

        // 笔数
        out.add("共 ${expenses.size} 笔支出 · ${records.count { it.type == TransactionRecord.TYPE_INCOME }} 笔收入")
        return out
    }

    private fun renderInsights(insights: List<String>) {
        val card = findViewById<View>(R.id.cardInsights)
        val container = findViewById<LinearLayout>(R.id.llInsights)
        if (insights.isEmpty()) {
            card.visibility = View.GONE
            return
        }
        findViewById<TextView>(R.id.tvInsightsTitle).text =
            "${FormatUtil.monthLabel(year, month).replace("年", "年").replace("月", "月")}洞察"
        container.removeAllViews()
        insights.forEach { text ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6.dp, 0, 6.dp)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(6.dp, 6.dp).apply {
                    rightMargin = 10.dp
                }
                setBackgroundResource(R.drawable.bg_insight_dot)
            }
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                this.text = text
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@StatsActivity, R.color.text_primary))
            }
            row.addView(dot)
            row.addView(tv)
            container.addView(row)
        }
        card.visibility = View.VISIBLE
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private fun buildSemanticColors(roots: List<String>): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        var idx = 0
        for (root in roots) {
            val hex = rootColorMap[root]
            if (hex != null) {
                result[root] = Color.parseColor(hex)
            } else {
                result[root] = fallbackPalette[idx % fallbackPalette.size]
                idx++
            }
        }
        return result
    }

    private fun loadTrend() {
        val range = currentTimeRange; val agg = currentDataAgg
        lifecycleScope.launch(Dispatchers.IO) {
            val now = Calendar.getInstance(); val start = Calendar.getInstance()
            when (range) {
                TimeRange.YEAR -> start.add(Calendar.DAY_OF_YEAR, -365)
                TimeRange.QUARTER -> start.add(Calendar.DAY_OF_YEAR, -90)
                TimeRange.MONTH -> start.add(Calendar.DAY_OF_YEAR, -30)
                TimeRange.WEEK -> start.add(Calendar.DAY_OF_YEAR, -7)
                TimeRange.DAY -> start.add(Calendar.HOUR_OF_DAY, -24)
            }
            start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
            val startMs = start.timeInMillis
            val recs = db.transactionDao().getInRange(startMs, now.timeInMillis + 1)
            val expenses = recs.filter { it.type == TransactionRecord.TYPE_EXPENSE }

            if (range == TimeRange.DAY) {
                val byHour = FloatArray(24)
                expenses.forEach { r ->
                    val c = Calendar.getInstance().apply { timeInMillis = r.timestamp }
                    byHour[c.get(Calendar.HOUR_OF_DAY)] += r.amount.toFloat()
                }
                val total = byHour.sum()
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    tvTrendSummary.text = "过去24小时共支出 ${FormatUtil.money(total.toDouble())}"
                    renderHourly(byHour, total)
                }
                return@launch
            }

            val dayMs = 86400000L
            val entries: List<Entry>; val labels: List<String>; val total: Float

            when (agg) {
                DataAgg.DAY -> {
                    val days = range.days
                    val arr = FloatArray(days)
                    expenses.forEach { r ->
                        val idx = ((r.timestamp - startMs) / dayMs).toInt()
                        if (idx in 0 until days) arr[idx] += r.amount.toFloat()
                    }
                    val sdf = SimpleDateFormat("M/d", Locale.getDefault())
                    labels = (0 until days).map { i -> val c = (start.clone() as Calendar); c.add(Calendar.DAY_OF_YEAR, i); sdf.format(c.time) }
                    entries = (0 until days).map { Entry(it.toFloat(), arr[it]) }
                    total = arr.sum()
                }
                DataAgg.WEEK -> {
                    val weeks = (range.days + 6) / 7
                    val arr = FloatArray(weeks)
                    expenses.forEach { r ->
                        val idx = ((r.timestamp - startMs) / (7 * dayMs)).toInt()
                        if (idx in 0 until weeks) arr[idx] += r.amount.toFloat()
                    }
                    val sdf = SimpleDateFormat("M/d", Locale.getDefault())
                    labels = (0 until weeks).map { i -> val c = (start.clone() as Calendar); c.add(Calendar.DAY_OF_YEAR, i * 7); sdf.format(c.time) }
                    entries = (0 until weeks).map { Entry(it.toFloat(), arr[it]) }
                    total = arr.sum()
                }
                DataAgg.MONTH -> {
                    val startM = start.get(Calendar.YEAR) * 12 + start.get(Calendar.MONTH)
                    val endM = now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH)
                    val months = (endM - startM + 1).coerceAtLeast(1)
                    val arr = FloatArray(months)
                    expenses.forEach { r ->
                        val c = Calendar.getInstance().apply { timeInMillis = r.timestamp }
                        val m = c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH) - startM
                        if (m in 0 until months) arr[m] += r.amount.toFloat()
                    }
                    labels = (0 until months).map { i -> val c = (start.clone() as Calendar); c.add(Calendar.MONTH, i); "${c.get(Calendar.MONTH) + 1}月" }
                    entries = (0 until months).map { Entry(it.toFloat(), arr[it]) }
                    total = arr.sum()
                }
                DataAgg.QUARTER -> {
                    val startQ = start.get(Calendar.YEAR) * 4 + start.get(Calendar.MONTH) / 3
                    val endQ = now.get(Calendar.YEAR) * 4 + now.get(Calendar.MONTH) / 3
                    val quarters = (endQ - startQ + 1).coerceAtLeast(1)
                    val arr = FloatArray(quarters)
                    expenses.forEach { r ->
                        val c = Calendar.getInstance().apply { timeInMillis = r.timestamp }
                        val q = c.get(Calendar.YEAR) * 4 + c.get(Calendar.MONTH) / 3 - startQ
                        if (q in 0 until quarters) arr[q] += r.amount.toFloat()
                    }
                    labels = (0 until quarters).map { i -> val c = (start.clone() as Calendar); c.add(Calendar.MONTH, i * 3); "Q${c.get(Calendar.MONTH) / 3 + 1}" }
                    entries = (0 until quarters).map { Entry(it.toFloat(), arr[it]) }
                    total = arr.sum()
                }
            }

            val avg = if (entries.isNotEmpty()) total / entries.size else 0f
            val aggLabel = when (agg) { DataAgg.DAY -> "日均"; DataAgg.WEEK -> "周均"; DataAgg.MONTH -> "月均"; DataAgg.QUARTER -> "季均" }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                tvTrendSummary.text = "过去${range.days}天共支出 ${FormatUtil.money(total.toDouble())} · ${aggLabel} ${FormatUtil.money(avg.toDouble())}"
                renderLineChart(entries, labels, total, agg)
            }
        }
    }

    // ===== Pie Chart =====

    private fun renderPie() {
        try {
            val data = if (pieModeExpense) expenseByCat else incomeByCat
            if (data.isEmpty()) {
                pieChart.clear()
                pieChart.setNoDataText("暂无数据")
                pieChart.setNoDataTextColor(chartTextColor())
                return
            }
            val total = data.sumOf { it.second }
            val entries = data.map { (name, amt) -> PieEntry(amt.toFloat(), name) }
            val colorList = data.mapIndexed { i, (name, _) ->
                catColors[name] ?: fallbackPalette[i % fallbackPalette.size]
            }

            val set = PieDataSet(entries, "").apply {
                setColors(colorList)
                setDrawValues(true)
                valueLineColor = Color.TRANSPARENT
                sliceSpace = 3f
                selectionShift = 8f
                setAutomaticallyDisableSliceSpacing(true)
            }

            pieChart.apply {
                this.data = PieData(set).apply {
                    setValueFormatter(PercentFormatter(pieChart))
                    setValueTextSize(11f)
                    setValueTextColor(Color.WHITE)
                }
                description.isEnabled = false
                setUsePercentValues(true)
                setHoleColor(Color.TRANSPARENT)
                holeRadius = 42f
                transparentCircleRadius = 47f
                setTransparentCircleColor(Color.WHITE)
                setTransparentCircleAlpha(50)
                setDrawEntryLabels(false)
                setExtraOffsets(8f, 8f, 8f, 8f)
                val centerLabel = if (pieModeExpense) "总支出" else "总收入"
                centerText = SpannableString("$centerLabel\n${FormatUtil.money(total)}").apply {
                    setSpan(RelativeSizeSpan(0.75f), 0, centerLabel.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.NORMAL), 0, centerLabel.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), centerLabel.length, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                setDrawCenterText(true)
                setCenterTextSize(14f)
                legend.apply {
                    isEnabled = true
                    verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    orientation = Legend.LegendOrientation.HORIZONTAL
                    setDrawInside(false)
                    textSize = 11f
                    form = Legend.LegendForm.CIRCLE
                    formSize = 9f
                    xEntrySpace = 10f
                    yEntrySpace = 4f
                    textColor = chartTextColor()
                }
                animateY(700)
                invalidate()
            }
        } catch (e: Exception) { pieChart.clear() }
    }

    private fun renderBreakdown() {
        llBreakdown.removeAllViews()
        val data = if (pieModeExpense) expenseByCat else incomeByCat
        val total = if (pieModeExpense) totalExpense else totalIncome
        if (data.isEmpty()) { llBreakdown.visibility = View.GONE; return }
        llBreakdown.visibility = View.VISIBLE
        data.forEachIndexed { i, (name, amt) ->
            val v = LayoutInflater.from(this).inflate(R.layout.item_category_breakdown, llBreakdown, false)
            val pct = if (total > 0) (amt / total * 100).toInt() else 0
            v.findViewById<TextView>(R.id.tvName).text = name
            v.findViewById<TextView>(R.id.tvAmount).text = FormatUtil.money(amt)
            v.findViewById<TextView>(R.id.tvPercent).text = "$pct%"
            val color = catColors[name] ?: fallbackPalette[i % fallbackPalette.size]
            v.findViewById<View>(R.id.vDot).background?.mutate()?.setTint(color)
            val bar = v.findViewById<ProgressBar>(R.id.progress)
            bar.max = 100; bar.progress = pct
            bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            llBreakdown.addView(v)
        }
    }

    // ===== Line Chart =====

    private fun renderLineChart(entries: List<Entry>, labels: List<String>, total: Float, agg: DataAgg) {
        if (entries.isEmpty()) {
            lineChart.clear()
            lineChart.setNoDataText("暂无数据")
            lineChart.setNoDataTextColor(chartTextColor())
            return
        }
        lineChart.visibility = View.VISIBLE
        barChartHourly.visibility = View.GONE
        pendingLineData = PendingLineData(entries, labels, total, agg)
        doRenderLineChart(0)
    }

    private fun doRenderLineChart(retry: Int) {
        val pd = pendingLineData as? PendingLineData ?: return
        if (pd.entries.isEmpty()) return

        if (lineChart.width == 0 || lineChart.height == 0) {
            if (retry < 20) {
                lineChart.postDelayed({ doRenderLineChart(retry + 1) }, 50L)
            }
            return
        }

        try {
            val entries = pd.entries; val labels = pd.labels; val total = pd.total; val agg = pd.agg
            val isManyPoints = entries.size > 30
            val tc = chartTextColor()
            val gc = Color.parseColor("#30808080")
            val legendLabel = when (agg) {
                DataAgg.DAY -> "每日支出"; DataAgg.WEEK -> "每周支出"
                DataAgg.MONTH -> "每月支出"; DataAgg.QUARTER -> "每季支出"
            }

            val set = LineDataSet(entries, "$legendLabel  ${FormatUtil.money(total.toDouble())}").apply {
                color = trendLineColor
                lineWidth = if (isManyPoints) 1.2f else 2.5f
                setDrawCircles(true)
                circleRadius = if (isManyPoints) 1.5f else 4f
                circleHoleRadius = if (isManyPoints) 0f else 2f
                setCircleColor(trendLineColor)
                setDrawValues(false)
                setDrawFilled(true)
                fillDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(trendFillTop, trendFillBottom))
                mode = if (isManyPoints) LineDataSet.Mode.LINEAR else LineDataSet.Mode.CUBIC_BEZIER
                cubicIntensity = 0.2f
                setHighlightEnabled(true)
                setHighLightColor(Color.parseColor("#80FF5252"))
                highlightLineWidth = 1.5f
                setDrawHorizontalHighlightIndicator(false)
            }

            lineChart.apply {
                data = LineData(set)
                description.isEnabled = false
                setDrawGridBackground(false)
                setBackgroundColor(Color.TRANSPARENT)
                setExtraOffsets(8f, 12f, 8f, 8f)
                setTouchEnabled(true)
                setDragEnabled(isManyPoints)
                setScaleEnabled(false)
                setPinchZoom(false)
                if (isManyPoints) setVisibleXRangeMaximum(60f)
                setNoDataText("暂无数据")
                setNoDataTextColor(tc)
                // 点击/拖动显示金额气泡
                marker = TrendMarkerView(this@StatsActivity) { idx -> labels.getOrElse(idx) { "" } }
                setHighlightPerDragEnabled(true)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    gridColor = gc
                    gridLineWidth = 0.5f
                    setDrawAxisLine(true)
                    axisLineColor = gc
                    setDrawLabels(true)
                    textColor = tc
                    textSize = 13f
                    granularity = 1f
                    labelCount = when {
                        entries.size <= 7 -> entries.size
                        entries.size <= 14 -> 7
                        entries.size <= 30 -> 6
                        else -> 5
                    }
                    labelRotationAngle = if (entries.size > 10) -25f else 0f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return labels.getOrElse(value.toInt()) { "" }
                        }
                    }
                }

                axisLeft.apply {
                    setDrawLabels(true)
                    valueFormatter = AxisMoneyFormatter()
                    textColor = tc
                    textSize = 13f
                    setDrawGridLines(true)
                    gridColor = gc
                    gridLineWidth = 0.5f
                    axisMinimum = 0f
                    setDrawAxisLine(true)
                    axisLineColor = gc
                    setDrawZeroLine(true)
                    zeroLineColor = gc
                }

                axisRight.isEnabled = false

                legend.apply {
                    isEnabled = true
                    verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    setDrawInside(false)
                    form = Legend.LegendForm.SQUARE
                    formSize = 10f
                    xEntrySpace = 8f
                    textSize = 13f
                    textColor = tc
                }

                notifyDataSetChanged()
                requestLayout()
                animateX(800)
                invalidate()
            }
        } catch (e: Exception) {
            lineChart.clear()
        }
    }

    // ===== Bar Chart (hourly) =====

    private fun renderHourly(byHour: FloatArray, total: Float) {
        lineChart.visibility = View.GONE
        barChartHourly.visibility = View.VISIBLE
        pendingBarData = PendingBarData(byHour, total)
        doRenderHourly(0)
    }

    private fun doRenderHourly(retry: Int) {
        val pd = pendingBarData as? PendingBarData ?: return

        if (barChartHourly.width == 0 || barChartHourly.height == 0) {
            if (retry < 20) {
                barChartHourly.postDelayed({ doRenderHourly(retry + 1) }, 50L)
            }
            return
        }

        try {
            val byHour = pd.byHour; val total = pd.total
            val tc = chartTextColor()
            val gc = Color.parseColor("#30808080")
            val entries = (0..23).map { BarEntry(it.toFloat(), byHour[it]) }

            val set = BarDataSet(entries, "每小时支出  ${FormatUtil.money(total.toDouble())}").apply {
                color = trendLineColor
                setGradientColor(Color.parseColor("#FF8A80"), Color.parseColor("#C62828"))
                setDrawValues(false)
                setHighlightEnabled(true)
                setHighLightColor(Color.parseColor("#80FF5252"))
            }

            barChartHourly.apply {
                data = BarData(set).apply { barWidth = 0.6f }
                description.isEnabled = false
                setFitBars(true)
                setExtraOffsets(8f, 12f, 8f, 8f)
                setTouchEnabled(true)
                setDrawGridBackground(false)
                setBackgroundColor(Color.TRANSPARENT)
                setNoDataText("暂无数据")
                setNoDataTextColor(tc)
                // 点击柱子显示金额气泡
                marker = TrendMarkerView(this@StatsActivity) { idx -> "$idx:00" }

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    granularity = 1f
                    setDrawGridLines(false)
                    setDrawAxisLine(true)
                    axisLineColor = gc
                    setDrawLabels(true)
                    textColor = tc
                    textSize = 13f
                    labelCount = 8
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String {
                            return "${value.toInt()}时"
                        }
                    }
                }

                axisLeft.apply {
                    setDrawLabels(true)
                    valueFormatter = AxisMoneyFormatter()
                    textColor = tc
                    textSize = 13f
                    setDrawGridLines(true)
                    gridColor = gc
                    gridLineWidth = 0.5f
                    axisMinimum = 0f
                    setDrawAxisLine(true)
                    axisLineColor = gc
                    setDrawZeroLine(true)
                    zeroLineColor = gc
                }

                axisRight.isEnabled = false

                legend.apply {
                    isEnabled = true
                    verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    setDrawInside(false)
                    form = Legend.LegendForm.SQUARE
                    formSize = 10f
                    xEntrySpace = 8f
                    textSize = 13f
                    textColor = tc
                }

                notifyDataSetChanged()
                requestLayout()
                animateY(600)
                invalidate()
            }
        } catch (e: Exception) {
            barChartHourly.clear()
        }
    }

    // ===== Helpers =====

    private fun chartTextColor(): Int {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
            Color.parseColor("#E0E0E0")
        } else {
            Color.parseColor("#424242")
        }
    }

    // ===== AI =====

    private fun runAiAnalysis() {
        val config = AiSettings.currentConfig(this)
        if (!config.isReady) {
            Toast.makeText(this, "请先在设置中配置 AI API Key", Toast.LENGTH_LONG).show()
            return
        }
        val loading = AlertDialog.Builder(this)
            .setTitle("AI 智能分析")
            .setMessage("正在生成本月洞察，请稍候…")
            .setCancelable(false)
            .create()
        loading.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val (start, end) = FormatUtil.monthRange(year, month)
            val recs = db.transactionDao().getInRange(start, end)
            val summary = AiHelper.summarizeTransactions(recs)
            val result = AiHelper.getInsight("transactions", summary, config)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                loading.dismiss()
                showAiDialog(result)
            }
        }
    }

    private fun showAiDialog(content: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_ai_result, null)
        view.findViewById<TextView>(R.id.tvAiResult).text = AiHelper.formatAiResponse(content)
        AlertDialog.Builder(this).setView(view).setPositiveButton("关闭", null).show()
    }

    private class AxisMoneyFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val v = value.toDouble()
            return when {
                v <= 0 -> ""
                v >= 10000 -> String.format("%.1f万", v / 10000)
                v >= 1000 -> String.format("%.0f", v)
                else -> String.format("%.0f", v)
            }
        }
    }
}

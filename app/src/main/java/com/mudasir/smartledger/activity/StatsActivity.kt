package com.mudasir.smartledger.activity

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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.AiHelper
import com.mudasir.smartledger.util.AiSettings
import com.mudasir.smartledger.util.BottomNavHelper
import com.mudasir.smartledger.util.FormatUtil
import com.mudasir.smartledger.util.ThemeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class TrendGranularity(val days: Int, val label: String, val hourly: Boolean) {
    YEAR(365, "年", false),
    QUARTER(90, "季", false),
    MONTH(30, "月", false),
    WEEK(7, "周", false),
    DAY(1, "天", true)
}

class StatsActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var pieChart: PieChart
    private lateinit var lineChart: LineChart
    private lateinit var barChartHourly: BarChart
    private lateinit var llBreakdown: LinearLayout
    private lateinit var sectionCategory: View
    private lateinit var sectionTrend: View
    private lateinit var tvTrendSummary: TextView

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
    private var currentGranularity: TrendGranularity = TrendGranularity.MONTH

    private val palette = intArrayOf(
        Color.parseColor("#F44336"), Color.parseColor("#2196F3"), Color.parseColor("#9C27B0"),
        Color.parseColor("#4CAF50"), Color.parseColor("#FF9800"), Color.parseColor("#009688"),
        Color.parseColor("#795548"), Color.parseColor("#607D8B"), Color.parseColor("#E91E63"),
        Color.parseColor("#3F51B5"), Color.parseColor("#FFC107"), Color.parseColor("#673AB7")
    )

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

        findViewById<View>(R.id.btnPrev).setOnClickListener { shiftMonth(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { shiftMonth(1) }
        findViewById<View>(R.id.btnAi).setOnClickListener { runAiAnalysis() }

        findViewById<MaterialButtonToggleGroup>(R.id.toggleDim).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sectionCategory.visibility = if (checkedId == R.id.btnDimCategory) View.VISIBLE else View.GONE
            sectionTrend.visibility = if (checkedId == R.id.btnDimTrend) View.VISIBLE else View.GONE
        }
        findViewById<MaterialButtonToggleGroup>(R.id.togglePie).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            pieModeExpense = checkedId == R.id.btnPieExpense
            renderPie()
            renderBreakdown()
        }
        findViewById<MaterialButtonToggleGroup>(R.id.toggleGranularity).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentGranularity = when (checkedId) {
                R.id.btnGranYear -> TrendGranularity.YEAR
                R.id.btnGranQuarter -> TrendGranularity.QUARTER
                R.id.btnGranMonth -> TrendGranularity.MONTH
                R.id.btnGranWeek -> TrendGranularity.WEEK
                R.id.btnGranDay -> TrendGranularity.DAY
                else -> TrendGranularity.MONTH
            }
            loadTrend()
        }

        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_stats)
        load()
        loadTrend()
    }

    private fun shiftMonth(delta: Int) {
        cal.set(year, month, 1)
        cal.add(Calendar.MONTH, delta)
        year = cal.get(Calendar.YEAR)
        month = cal.get(Calendar.MONTH)
        load()
    }

    private fun parentOf(catByName: Map<String, Category>, name: String): String {
        val raw = name.ifBlank { "未分类" }
        return catByName[raw]?.parentName ?: raw
    }

    // ---- 月度分类数据 ----

    private fun load() {
        findViewById<TextView>(R.id.tvMonth).text = FormatUtil.monthLabel(year, month)
        lifecycleScope.launch(Dispatchers.IO) {
            val (start, end) = FormatUtil.monthRange(year, month)
            records = db.transactionDao().getInRange(start, end)
            val cats = db.categoryDao().getAll()
            val catByName = cats.associateBy { it.name }

            totalIncome = records.filter { it.type == TransactionRecord.TYPE_INCOME }.sumOf { it.amount }
            totalExpense = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }.sumOf { it.amount }

            expenseByCat = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }
                .groupBy { parentOf(catByName, it.categoryName) }
                .map { (k, v) -> k to v.sumOf { it.amount } }
                .sortedByDescending { it.second }
            incomeByCat = records.filter { it.type == TransactionRecord.TYPE_INCOME }
                .groupBy { parentOf(catByName, it.categoryName) }
                .map { (k, v) -> k to v.sumOf { it.amount } }
                .sortedByDescending { it.second }

            val pieRoots = (expenseByCat.map { it.first } + incomeByCat.map { it.first }).distinct()
            catColors = buildUniqueColors(pieRoots)

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                findViewById<TextView>(R.id.tvSumIncome).text = FormatUtil.money(totalIncome)
                findViewById<TextView>(R.id.tvSumExpense).text = FormatUtil.money(totalExpense)
                findViewById<TextView>(R.id.tvSumBalance).text = FormatUtil.money(totalIncome - totalExpense)
                findViewById<View>(R.id.tvEmpty).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                renderPie()
                renderBreakdown()
            }
        }
    }

    private fun buildUniqueColors(roots: List<String>): Map<String, Int> {
        val used = mutableSetOf<Int>()
        val result = mutableMapOf<String, Int>()
        var idx = 0
        for (root in roots) {
            var color = palette[idx % palette.size]
            while (color in used) { idx++; color = palette[idx % palette.size] }
            used.add(color)
            result[root] = color
            idx++
        }
        return result
    }

    // ---- 趋势数据 ----

    private fun loadTrend() {
        val gran = currentGranularity
        lifecycleScope.launch(Dispatchers.IO) {
            val now = Calendar.getInstance()
            val start = Calendar.getInstance()
            when (gran) {
                TrendGranularity.YEAR -> start.add(Calendar.DAY_OF_YEAR, -365)
                TrendGranularity.QUARTER -> start.add(Calendar.DAY_OF_YEAR, -90)
                TrendGranularity.MONTH -> start.add(Calendar.DAY_OF_YEAR, -30)
                TrendGranularity.WEEK -> start.add(Calendar.DAY_OF_YEAR, -7)
                TrendGranularity.DAY -> start.add(Calendar.HOUR_OF_DAY, -24)
            }
            start.set(Calendar.HOUR_OF_DAY, 0)
            start.set(Calendar.MINUTE, 0)
            start.set(Calendar.SECOND, 0)
            start.set(Calendar.MILLISECOND, 0)
            val startMs = start.timeInMillis
            val recs = db.transactionDao().getInRange(startMs, now.timeInMillis + 1)

            if (gran.hourly) {
                val byHour = FloatArray(24)
                recs.filter { it.type == TransactionRecord.TYPE_EXPENSE }.forEach { r ->
                    val c = Calendar.getInstance().apply { timeInMillis = r.timestamp }
                    val h = c.get(Calendar.HOUR_OF_DAY)
                    byHour[h] += r.amount.toFloat()
                }
                val total = byHour.sum()
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    tvTrendSummary.text = "过去24小时共支出 ${FormatUtil.money(total.toDouble())}"
                    renderHourly(byHour, total)
                }
            } else {
                val days = gran.days
                val byDay = FloatArray(days)
                val dayMs = 24 * 60 * 60 * 1000L
                recs.filter { it.type == TransactionRecord.TYPE_EXPENSE }.forEach { r ->
                    val dayIdx = ((r.timestamp - startMs) / dayMs).toInt()
                    if (dayIdx in 0 until days) byDay[dayIdx] += r.amount.toFloat()
                }
                val labels = mutableListOf<String>()
                val sdf = if (gran == TrendGranularity.YEAR) SimpleDateFormat("M月", Locale.getDefault())
                           else SimpleDateFormat("MM/dd", Locale.getDefault())
                for (i in 0 until days) {
                    val c = start.clone() as Calendar
                    c.add(Calendar.DAY_OF_YEAR, i)
                    labels.add(sdf.format(c.time))
                }
                val entries = (0 until days).map { Entry(it.toFloat(), byDay[it]) }
                val total = byDay.sum()
                val avg = if (days > 0) total / days else 0f
                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) return@withContext
                    tvTrendSummary.text = "过去${days}天共支出 ${FormatUtil.money(total.toDouble())} · 日均 ${FormatUtil.money(avg.toDouble())}"
                    renderLineChart(entries, labels, total, gran)
                }
            }
        }
    }

    // ---- 渲染：饼图 ----

    private fun renderPie() {
        try {
            val data = if (pieModeExpense) expenseByCat else incomeByCat
            if (data.isEmpty()) { pieChart.clear(); pieChart.setNoDataText("暂无数据"); return }
            val total = data.sumOf { it.second }
            val entries = data.map { (name, amt) -> PieEntry(amt.toFloat(), name) }
            val colorList: List<Int> = data.mapIndexed { i, (name, _) -> catColors[name] ?: palette[i % palette.size] }
            val set = PieDataSet(entries, "").apply {
                setColors(colorList); setDrawValues(true); valueLineColor = Color.TRANSPARENT
                sliceSpace = 2f; selectionShift = 6f
            }
            pieChart.apply {
                this.data = PieData(set).apply {
                    setValueFormatter(PercentFormatter(pieChart)); setValueTextSize(11f); setValueTextColor(Color.WHITE)
                }
                description.isEnabled = false; setUsePercentValues(true); setHoleColor(Color.TRANSPARENT)
                holeRadius = 45f; transparentCircleRadius = 50f; setDrawEntryLabels(false)
                val centerLabel = if (pieModeExpense) "总支出" else "总收入"
                centerText = SpannableString("$centerLabel\n${FormatUtil.money(total)}").apply {
                    setSpan(RelativeSizeSpan(0.75f), 0, centerLabel.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.NORMAL), 0, centerLabel.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), centerLabel.length, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                setDrawCenterText(true)
                legend.apply {
                    isEnabled = true; verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    orientation = Legend.LegendOrientation.HORIZONTAL; setDrawInside(false)
                    textSize = 11f; form = Legend.LegendForm.CIRCLE; xEntrySpace = 12f; textColor = currentTextColor()
                }
                animateY(600); invalidate()
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
            val color = catColors[name] ?: palette[i % palette.size]
            v.findViewById<View>(R.id.vDot).background?.mutate()?.setTint(color)
            val bar = v.findViewById<ProgressBar>(R.id.progress)
            bar.max = 100; bar.progress = pct
            bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            llBreakdown.addView(v)
        }
    }

    // ---- 渲染：趋势折线图 ----

    private fun renderLineChart(entries: List<Entry>, labels: List<String>, total: Float, gran: TrendGranularity) {
        try {
            if (entries.isEmpty()) { lineChart.clear(); lineChart.setNoDataText("暂无数据"); return }
            lineChart.visibility = View.VISIBLE
            barChartHourly.visibility = View.GONE

            val isManyPoints = entries.size > 60
            val set = LineDataSet(entries, "每日支出 ${FormatUtil.money(total.toDouble())}").apply {
                color = Color.parseColor("#F44336")
                lineWidth = if (isManyPoints) 1f else 2f
                setDrawCircles(true)
                circleRadius = if (isManyPoints) 1.5f else 2.5f
                circleHoleRadius = if (isManyPoints) 0f else 1f
                setCircleColor(Color.parseColor("#F44336"))
                setDrawValues(false)
                setDrawFilled(true)
                fillDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.parseColor("#33F44336"), Color.parseColor("#05F44336"))
                )
                mode = if (isManyPoints) LineDataSet.Mode.LINEAR else LineDataSet.Mode.CUBIC_BEZIER
                cubicIntensity = 0.15f
                setHighlightEnabled(true)
                setHighLightColor(Color.parseColor("#88F44336"))
                highlightLineWidth = 1f
                setDrawHorizontalHighlightIndicator(false)
            }

            lineChart.apply {
                data = LineData(set)
                description.isEnabled = false
                setDrawGridBackground(false)
                setBackgroundColor(Color.TRANSPARENT)
                setTouchEnabled(true)
                setDragEnabled(isManyPoints)
                setScaleEnabled(false)
                setPinchZoom(false)
                if (isManyPoints) setVisibleXRangeMaximum(60f)

                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    setDrawGridLines(true)
                    gridColor = Color.parseColor("#14888888")
                    gridLineWidth = 0.5f
                    textColor = currentTextColor()
                    textSize = 10f
                    labelCount = if (gran == TrendGranularity.WEEK) 7 else 6
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String =
                            labels.getOrElse(value.toInt()) { "" }
                    }
                }
                axisLeft.apply {
                    valueFormatter = AxisMoneyFormatter()
                    textColor = currentTextColor()
                    textSize = 10f
                    setDrawGridLines(true)
                    gridColor = Color.parseColor("#14888888")
                    gridLineWidth = 0.5f
                    axisMinimum = 0f
                }
                axisRight.isEnabled = false
                legend.apply {
                    isEnabled = true
                    verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    setDrawInside(false)
                    form = Legend.LegendForm.SQUARE
                    formSize = 10f; textSize = 12f; textColor = currentTextColor()
                }
                animateX(800)
                invalidate()
            }
        } catch (e: Exception) { lineChart.clear() }
    }

    // ---- 渲染：小时柱状图 ----

    private fun renderHourly(byHour: FloatArray, total: Float) {
        try {
            lineChart.visibility = View.GONE
            barChartHourly.visibility = View.VISIBLE

            val entries = (0..23).map { BarEntry(it.toFloat(), byHour[it]) }
            val set = BarDataSet(entries, "每小时支出 ${FormatUtil.money(total.toDouble())}").apply {
                color = Color.parseColor("#F44336")
                setGradientColor(Color.parseColor("#FF8A80"), Color.parseColor("#C62828"))
                setDrawValues(false)
            }
            barChartHourly.apply {
                data = BarData(set).apply { barWidth = 0.6f }
                description.isEnabled = false; setFitBars(true)
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM; granularity = 1f; setDrawGridLines(false)
                    textColor = currentTextColor(); textSize = 10f; labelCount = 8
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String = "${value.toInt()}时"
                    }
                }
                axisLeft.apply {
                    valueFormatter = AxisMoneyFormatter(); textColor = currentTextColor(); textSize = 10f
                    setDrawGridLines(true); gridColor = Color.parseColor("#14888888"); gridLineWidth = 0.5f; axisMinimum = 0f
                }
                axisRight.isEnabled = false
                legend.apply {
                    isEnabled = true; verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER; setDrawInside(false)
                    form = Legend.LegendForm.SQUARE; formSize = 10f; textSize = 12f; textColor = currentTextColor()
                }
                animateY(500); invalidate()
            }
        } catch (e: Exception) { barChartHourly.clear() }
    }

    // ---- 工具 ----

    private fun currentTextColor(): Int = ThemeColor.onSurface(this)

    private fun runAiAnalysis() {
        val config = AiSettings.currentConfig(this)
        if (!config.isReady) {
            Toast.makeText(this, "请先在设置中配置 AI API Key", Toast.LENGTH_LONG).show(); return
        }
        val loading = AlertDialog.Builder(this)
            .setTitle("AI 智能分析").setMessage("正在生成本月洞察，请稍候…").setCancelable(false).create()
        loading.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val (start, end) = FormatUtil.monthRange(year, month)
            val recs = db.transactionDao().getInRange(start, end)
            val summary = AiHelper.summarizeTransactions(recs)
            val result = AiHelper.getInsight("transactions", summary, config)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                loading.dismiss(); showAiDialog(result)
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
                v >= 10000 -> String.format("%.0f万", v / 10000)
                v >= 1000 -> String.format("%.0f", v)
                else -> String.format("%.0f", v)
            }
        }
    }
}

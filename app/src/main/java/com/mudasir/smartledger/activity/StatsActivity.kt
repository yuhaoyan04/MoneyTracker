package com.mudasir.smartledger.activity

import android.graphics.Color
import android.graphics.Typeface
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
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
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
import java.util.Calendar

class StatsActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var pieChart: PieChart
    private lateinit var barChart: BarChart
    private lateinit var barChartDaily: BarChart
    private lateinit var llBreakdown: LinearLayout
    private lateinit var sectionCategory: View
    private lateinit var sectionTrend: View
    private lateinit var sectionTime: View

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
    private var trendData: List<Triple<Int, Int, Pair<Double, Double>>> = emptyList()

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
        barChart = findViewById(R.id.barChart)
        barChartDaily = findViewById(R.id.barChartDaily)
        llBreakdown = findViewById(R.id.llBreakdown)
        sectionCategory = findViewById(R.id.sectionCategory)
        sectionTrend = findViewById(R.id.sectionTrend)
        sectionTime = findViewById(R.id.sectionTime)

        findViewById<View>(R.id.btnPrev).setOnClickListener { shiftMonth(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { shiftMonth(1) }
        findViewById<View>(R.id.btnAi).setOnClickListener { runAiAnalysis() }

        findViewById<MaterialButtonToggleGroup>(R.id.toggleDim).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sectionCategory.visibility = if (checkedId == R.id.btnDimCategory) View.VISIBLE else View.GONE
            sectionTrend.visibility = if (checkedId == R.id.btnDimTrend) View.VISIBLE else View.GONE
            sectionTime.visibility = if (checkedId == R.id.btnDimTime) View.VISIBLE else View.GONE
        }
        findViewById<MaterialButtonToggleGroup>(R.id.togglePie).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            pieModeExpense = checkedId == R.id.btnPieExpense
            renderPie()
            renderBreakdown()
        }

        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_stats)
        load()
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

            val tm = mutableListOf<Triple<Int, Int, Pair<Double, Double>>>()
            val base = Calendar.getInstance().apply { set(year, month, 1) }
            for (i in 5 downTo 0) {
                val c = base.clone() as Calendar
                c.add(Calendar.MONTH, -i)
                val (s, e) = FormatUtil.monthRange(c.get(Calendar.YEAR), c.get(Calendar.MONTH))
                val inc = db.transactionDao().sumByType(TransactionRecord.TYPE_INCOME, s, e)
                val exp = db.transactionDao().sumByType(TransactionRecord.TYPE_EXPENSE, s, e)
                tm.add(Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), inc to exp))
            }
            trendData = tm

            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                findViewById<TextView>(R.id.tvSumIncome).text = FormatUtil.money(totalIncome)
                findViewById<TextView>(R.id.tvSumExpense).text = FormatUtil.money(totalExpense)
                findViewById<TextView>(R.id.tvSumBalance).text = FormatUtil.money(totalIncome - totalExpense)
                findViewById<View>(R.id.tvEmpty).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                renderPie()
                renderBreakdown()
                renderTrend()
                renderDaily()
            }
        }
    }

    private fun buildUniqueColors(roots: List<String>): Map<String, Int> {
        val used = mutableSetOf<Int>()
        val result = mutableMapOf<String, Int>()
        var idx = 0
        for (root in roots) {
            var color = palette[idx % palette.size]
            while (color in used) {
                idx++
                color = palette[idx % palette.size]
            }
            used.add(color)
            result[root] = color
            idx++
        }
        return result
    }

    private fun renderPie() {
        try {
            val data = if (pieModeExpense) expenseByCat else incomeByCat
            if (data.isEmpty()) {
                pieChart.clear()
                pieChart.setNoDataText("暂无数据")
                return
            }
            val total = data.sumOf { it.second }
            val entries = data.map { (name, amt) -> PieEntry(amt.toFloat(), name) }
            val colorList: List<Int> = data.mapIndexed { i, (name, _) ->
                catColors[name] ?: palette[i % palette.size]
            }
            val set = PieDataSet(entries, "").apply {
                setColors(colorList)
                setDrawValues(true)
                valueLineColor = Color.TRANSPARENT
                sliceSpace = 2f
                selectionShift = 6f
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
                holeRadius = 45f
                transparentCircleRadius = 50f
                setDrawEntryLabels(false)
                val centerLabel = if (pieModeExpense) "总支出" else "总收入"
                centerText = SpannableString("$centerLabel\n${FormatUtil.money(total)}").apply {
                    setSpan(RelativeSizeSpan(0.75f), 0, centerLabel.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.NORMAL), 0, centerLabel.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), centerLabel.length, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                setDrawCenterText(true)
                legend.apply {
                    isEnabled = true
                    verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    orientation = Legend.LegendOrientation.HORIZONTAL
                    setDrawInside(false)
                    textSize = 11f
                    form = Legend.LegendForm.CIRCLE
                    xEntrySpace = 12f
                    textColor = currentTextColor()
                }
                animateY(600)
                invalidate()
            }
        } catch (e: Exception) {
            pieChart.clear()
        }
    }

    private fun renderBreakdown() {
        llBreakdown.removeAllViews()
        val data = if (pieModeExpense) expenseByCat else incomeByCat
        val total = if (pieModeExpense) totalExpense else totalIncome
        if (data.isEmpty()) {
            llBreakdown.visibility = View.GONE
            return
        }
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
            bar.max = 100
            bar.progress = pct
            bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            llBreakdown.addView(v)
        }
    }

    private fun renderTrend() {
        try {
            if (trendData.isEmpty()) {
                barChart.setNoDataText("暂无数据")
                barChart.clear()
                return
            }
            val incomeEntries = mutableListOf<BarEntry>()
            val expenseEntries = mutableListOf<BarEntry>()
            val labels = mutableListOf<String>()
            trendData.forEachIndexed { i, (y, m, pair) ->
                incomeEntries.add(BarEntry(i.toFloat(), pair.first.toFloat()))
                expenseEntries.add(BarEntry(i.toFloat(), pair.second.toFloat()))
                labels.add("${m + 1}月")
            }
            val incomeSet = BarDataSet(incomeEntries, "收入").apply {
                color = Color.parseColor("#4CAF50")
                setDrawValues(true)
                valueTextSize = 9f
                valueTextColor = Color.parseColor("#2E7D32")
                valueFormatter = CompactMoneyFormatter()
            }
            val expenseSet = BarDataSet(expenseEntries, "支出").apply {
                color = Color.parseColor("#F44336")
                setDrawValues(true)
                valueTextSize = 9f
                valueTextColor = Color.parseColor("#C62828")
                valueFormatter = CompactMoneyFormatter()
            }
            val groupSpace = 0.2f
            val barSpace = 0.05f
            val barWidth = (1f - groupSpace) / 2 - barSpace
            barChart.apply {
                data = BarData(incomeSet, expenseSet).apply {
                    this.barWidth = barWidth
                    groupBars(0f, groupSpace, barSpace)
                }
                description.isEnabled = false
                setFitBars(true)
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    granularity = 1f
                    setDrawGridLines(false)
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String =
                            labels.getOrElse(value.toInt()) { "" }
                    }
                    textColor = currentTextColor()
                    textSize = 11f
                }
                axisLeft.apply {
                    valueFormatter = AxisMoneyFormatter()
                    textColor = currentTextColor()
                    textSize = 10f
                    setDrawGridLines(true)
                    axisMinimum = 0f
                }
                axisRight.isEnabled = false
                legend.apply {
                    isEnabled = true
                    verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    orientation = Legend.LegendOrientation.HORIZONTAL
                    setDrawInside(false)
                    form = Legend.LegendForm.SQUARE
                    formSize = 10f
                    textSize = 12f
                    xEntrySpace = 24f
                    textColor = currentTextColor()
                }
                animateY(500)
                invalidate()
            }
        } catch (e: Exception) {
            barChart.clear()
        }
    }

    private fun renderDaily() {
        try {
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val expenseByDay = FloatArray(daysInMonth + 1)
            records.filter { it.type == TransactionRecord.TYPE_EXPENSE }.forEach { r ->
                val d = r.day
                if (d in 1..daysInMonth) expenseByDay[d] += r.amount.toFloat()
            }
            val entries = (1..daysInMonth).map { BarEntry(it.toFloat(), expenseByDay[it]) }
            val monthTotal = expenseByDay.sum()
            val set = BarDataSet(entries, "每日支出 ¥${String.format("%.0f", monthTotal)}").apply {
                color = Color.parseColor("#F44336")
                setDrawValues(false)
            }
            barChartDaily.apply {
                data = BarData(set).apply { barWidth = 0.6f }
                description.isEnabled = false
                setFitBars(true)
                xAxis.apply {
                    position = XAxis.XAxisPosition.BOTTOM
                    granularity = 1f
                    setDrawGridLines(false)
                    textColor = currentTextColor()
                    textSize = 10f
                    labelCount = 7
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String = "${value.toInt()}日"
                    }
                }
                axisLeft.apply {
                    valueFormatter = AxisMoneyFormatter()
                    textColor = currentTextColor()
                    textSize = 10f
                    setDrawGridLines(true)
                    axisMinimum = 0f
                }
                axisRight.isEnabled = false
                legend.apply {
                    isEnabled = true
                    verticalAlignment = Legend.LegendVerticalAlignment.TOP
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    setDrawInside(false)
                    form = Legend.LegendForm.SQUARE
                    formSize = 10f
                    textSize = 12f
                    textColor = currentTextColor()
                }
                animateY(400)
                invalidate()
            }
        } catch (e: Exception) {
            barChartDaily.clear()
        }
    }

    private fun currentTextColor(): Int = ThemeColor.onSurface(this)

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

    private class CompactMoneyFormatter : ValueFormatter() {
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

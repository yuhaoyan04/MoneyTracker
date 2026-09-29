package com.mudasir.smartledger.activity

import android.graphics.Color
import android.os.Bundle
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

    // 缓存本月数据，切换维度时无需重复查询
    private var records: List<TransactionRecord> = emptyList()
    private var expenseByCat: List<Pair<String, Double>> = emptyList()
    private var incomeByCat: List<Pair<String, Double>> = emptyList()
    private var totalExpense: Double = 0.0
    private var totalIncome: Double = 0.0
    private var catColors: Map<String, Int> = emptyMap()
    private var pieModeExpense: Boolean = true

    private val palette = intArrayOf(
        Color.parseColor("#FF7043"), Color.parseColor("#29B6F6"), Color.parseColor("#AB47BC"),
        Color.parseColor("#66BB6A"), Color.parseColor("#FFCA28"), Color.parseColor("#EF5350"),
        Color.parseColor("#26A69A"), Color.parseColor("#5C6BC0"), Color.parseColor("#8D6E63"),
        Color.parseColor("#78909C")
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

    private fun load() {
        findViewById<TextView>(R.id.tvMonth).text = FormatUtil.monthLabel(year, month)
        lifecycleScope.launch(Dispatchers.IO) {
            val (start, end) = FormatUtil.monthRange(year, month)
            records = db.transactionDao().getInRange(start, end)
            totalIncome = records.filter { it.type == TransactionRecord.TYPE_INCOME }.sumOf { it.amount }
            totalExpense = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }.sumOf { it.amount }
            expenseByCat = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }
                .groupBy { it.categoryName.ifBlank { "未分类" } }
                .map { (k, v) -> k to v.sumOf { it.amount } }
                .sortedByDescending { it.second }
            incomeByCat = records.filter { it.type == TransactionRecord.TYPE_INCOME }
                .groupBy { it.categoryName.ifBlank { "未分类" } }
                .map { (k, v) -> k to v.sumOf { it.amount } }
                .sortedByDescending { it.second }
            catColors = db.categoryDao().getAll().associate { it.name to parseColor(it.color) }

            withContext(Dispatchers.Main) {
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

    private fun renderPie() {
        try {
            val data = if (pieModeExpense) expenseByCat else incomeByCat
            if (data.isEmpty()) {
                pieChart.clear()
                return
            }
            val entries = data.map { (name, amt) -> PieEntry(amt.toFloat(), name) }
            // 关键修复：使用 List<Int> 重载（按 ARGB 原值），不要用 setColors(IntArray, Context)——后者把整型当作资源 ID 解析会抛 NotFoundException
            val colorList: List<Int> = data.mapIndexed { i, (name, _) ->
                catColors[name] ?: palette[i % palette.size]
            }
            val set = PieDataSet(entries, "").apply {
                setColors(colorList)
                setDrawValues(false)
                sliceSpace = 2f
                valueTextColor = Color.WHITE
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
                legend.apply {
                    verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                    horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                    orientation = Legend.LegendOrientation.HORIZONTAL
                    setDrawInside(false)
                    textSize = 11f
                    form = Legend.LegendForm.CIRCLE
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
        lifecycleScope.launch(Dispatchers.IO) {
            val months = mutableListOf<Pair<Int, Int>>()
            val base = Calendar.getInstance().apply { set(year, month, 1) }
            for (i in 5 downTo 0) {
                val c = base.clone() as Calendar
                c.add(Calendar.MONTH, -i)
                months.add(c.get(Calendar.YEAR) to c.get(Calendar.MONTH))
            }
            val incomeEntries = mutableListOf<BarEntry>()
            val expenseEntries = mutableListOf<BarEntry>()
            months.forEachIndexed { i, (y, m) ->
                val (s, e) = FormatUtil.monthRange(y, m)
                incomeEntries.add(BarEntry(i.toFloat(), db.transactionDao().sumByType(TransactionRecord.TYPE_INCOME, s, e).toFloat()))
                expenseEntries.add(BarEntry(i.toFloat(), db.transactionDao().sumByType(TransactionRecord.TYPE_EXPENSE, s, e).toFloat()))
            }
            withContext(Dispatchers.Main) {
                try {
                    val incomeSet = BarDataSet(incomeEntries, "收入").apply { color = Color.parseColor("#1F9D6A") }
                    val expenseSet = BarDataSet(expenseEntries, "支出").apply { color = Color.parseColor("#E5484D") }
                    val groupSpace = 0.2f
                    val barSpace = 0.05f
                    val barWidth = (1f - groupSpace) / 2 - barSpace
                    val labels = months.map { (y, m) -> "${m + 1}月" }
                    barChart.apply {
                        data = BarData(incomeSet, expenseSet).apply {
                            this.barWidth = barWidth
                            groupBars(0f, groupSpace, barSpace)
                            setValueTextSize(0f)
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
                        }
                        axisLeft.valueFormatter = object : ValueFormatter() {
                            override fun getFormattedValue(value: Float): String = FormatUtil.money(value.toDouble())
                        }
                        axisLeft.textColor = currentTextColor()
                        axisRight.isEnabled = false
                        legend.textColor = currentTextColor()
                        legend.form = Legend.LegendForm.SQUARE
                        animateY(500)
                        invalidate()
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun renderDaily() {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val expenseByDay = FloatArray(daysInMonth + 1)
                records.filter { it.type == TransactionRecord.TYPE_EXPENSE }.forEach { r ->
                    val d = r.day
                    if (d in 1..daysInMonth) expenseByDay[d] += r.amount.toFloat()
                }
                val entries = (1..daysInMonth).map { BarEntry(it.toFloat(), expenseByDay[it]) }
                val set = BarDataSet(entries, "每日支出").apply {
                    color = Color.parseColor("#E5484D")
                    setDrawValues(false)
                }
                barChartDaily.apply {
                    data = BarData(set).apply {
                        barWidth = 0.5f
                    }
                    description.isEnabled = false
                    setFitBars(true)
                    xAxis.apply {
                        position = XAxis.XAxisPosition.BOTTOM
                        granularity = 1f
                        setDrawGridLines(false)
                        textColor = currentTextColor()
                    }
                    axisLeft.textColor = currentTextColor()
                    axisLeft.valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(value: Float): String = FormatUtil.money(value.toDouble())
                    }
                    axisRight.isEnabled = false
                    legend.form = Legend.LegendForm.SQUARE
                    legend.textColor = currentTextColor()
                    animateY(400)
                    invalidate()
                }
            } catch (_: Exception) { }
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

    private fun parseColor(hex: String): Int =
        runCatching { Color.parseColor(hex) }.getOrDefault(Color.parseColor("#179A9D"))
}

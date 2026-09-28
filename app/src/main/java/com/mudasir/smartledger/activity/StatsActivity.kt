package com.mudasir.smartledger.activity

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.AiHelper
import com.mudasir.smartledger.util.AiSettings
import com.mudasir.smartledger.util.BottomNavHelper
import com.mudasir.smartledger.util.FormatUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class StatsActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var pieChart: PieChart
    private lateinit var barChart: BarChart
    private lateinit var llBreakdown: android.widget.LinearLayout

    private val cal = Calendar.getInstance()
    private var year: Int = cal.get(Calendar.YEAR)
    private var month: Int = cal.get(Calendar.MONTH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }
        pieChart = findViewById(R.id.pieChart)
        barChart = findViewById(R.id.barChart)
        llBreakdown = findViewById(R.id.llBreakdown)

        findViewById<View>(R.id.btnPrev).setOnClickListener { shiftMonth(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { shiftMonth(1) }
        findViewById<View>(R.id.btnAi).setOnClickListener { runAiAnalysis() }

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
            val records = db.transactionDao().getInRange(start, end)
            val income = records.filter { it.type == TransactionRecord.TYPE_INCOME }.sumOf { it.amount }
            val expense = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }.sumOf { it.amount }
            val expenseByCat = records.filter { it.type == TransactionRecord.TYPE_EXPENSE }
                .groupBy { it.categoryName.ifBlank { "未分类" } }
                .map { (k, v) -> k to v.sumOf { it.amount } }
                .sortedByDescending { it.second }

            val catColors = db.categoryDao().getAll().associate { it.name to parseColor(it.color) }

            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.tvSumIncome).text = FormatUtil.money(income)
                findViewById<TextView>(R.id.tvSumExpense).text = FormatUtil.money(expense)
                findViewById<TextView>(R.id.tvSumBalance).text = FormatUtil.money(income - expense)
                findViewById<View>(R.id.tvEmpty).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
                renderPie(expenseByCat, catColors)
                renderBreakdown(expenseByCat, expense, catColors)
                renderBarChart()
            }
        }
    }

    private fun renderPie(data: List<Pair<String, Double>>, catColors: Map<String, Int>) {
        if (data.isEmpty()) {
            pieChart.clear()
            return
        }
        val palette = intArrayOf(
            Color.parseColor("#FF7043"), Color.parseColor("#29B6F6"), Color.parseColor("#AB47BC"),
            Color.parseColor("#66BB6A"), Color.parseColor("#FFCA28"), Color.parseColor("#EF5350"),
            Color.parseColor("#26A69A"), Color.parseColor("#5C6BC0"), Color.parseColor("#8D6E63"),
            Color.parseColor("#78909C")
        )
        val entries = data.mapIndexed { i, (name, amt) ->
            PieEntry(amt.toFloat(), name).also { it.icon = null }
        }
        val set = PieDataSet(entries, "").apply {
            setColors(data.indices.map { i ->
                catColors[data[i].first] ?: palette[i % palette.size]
            }.toIntArray(), this@StatsActivity)
            setDrawValues(false)
            sliceSpace = 2f
            valueTextColor = Color.WHITE
            selectionShift = 6f
        }
        pieChart.apply {
            this.data = PieData(set).apply { setValueFormatter(PercentFormatter(pieChart)); setValueTextSize(11f); setValueTextColor(Color.WHITE) }
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
    }

    private fun renderBreakdown(data: List<Pair<String, Double>>, totalExpense: Double, catColors: Map<String, Int>) {
        llBreakdown.removeAllViews()
        if (data.isEmpty()) {
            llBreakdown.visibility = View.GONE
            return
        }
        llBreakdown.visibility = View.VISIBLE
        val palette = intArrayOf(
            Color.parseColor("#FF7043"), Color.parseColor("#29B6F6"), Color.parseColor("#AB47BC"),
            Color.parseColor("#66BB6A"), Color.parseColor("#FFCA28"), Color.parseColor("#EF5350")
        )
        data.forEachIndexed { i, (name, amt) ->
            val v = LayoutInflater.from(this).inflate(R.layout.item_category_breakdown, llBreakdown, false)
            val pct = if (totalExpense > 0) (amt / totalExpense * 100).toInt() else 0
            v.findViewById<TextView>(R.id.tvName).text = name
            v.findViewById<TextView>(R.id.tvAmount).text = FormatUtil.money(amt)
            v.findViewById<TextView>(R.id.tvPercent).text = "$pct%"
            val color = catColors[name] ?: palette[i % palette.size]
            v.findViewById<View>(R.id.vDot).background?.mutate()?.setTint(color)
            val bar = v.findViewById<android.widget.ProgressBar>(R.id.progress)
            bar.max = 100
            bar.progress = pct
            bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
            llBreakdown.addView(v)
        }
    }

    private fun renderBarChart() {
        lifecycleScope.launch(Dispatchers.IO) {
            val now = Calendar.getInstance()
            val months = mutableListOf<Pair<Int, Int>>() // (year, month0)
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
                val inc = db.transactionDao().sumByType(TransactionRecord.TYPE_INCOME, s, e).toFloat()
                val exp = db.transactionDao().sumByType(TransactionRecord.TYPE_EXPENSE, s, e).toFloat()
                incomeEntries.add(BarEntry(i.toFloat(), inc))
                expenseEntries.add(BarEntry(i.toFloat(), exp))
            }
            withContext(Dispatchers.Main) {
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
            }
        }
    }

    private fun currentTextColor(): Int =
        com.mudasir.smartledger.util.ThemeColor.onSurface(this)

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
            val records = db.transactionDao().getInRange(start, end)
            val summary = AiHelper.summarizeTransactions(records)
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

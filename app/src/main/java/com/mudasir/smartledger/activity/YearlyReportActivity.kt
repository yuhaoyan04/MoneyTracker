package com.mudasir.smartledger.activity

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.appbar.MaterialToolbar
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.FormatUtil
import com.mudasir.smartledger.util.ProManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/** 年度趋势报告（Pro）：12 个月收支柱状图 + 月度明细。 */
class YearlyReportActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private var year = Calendar.getInstance().get(Calendar.YEAR)

    private lateinit var adapter: MonthAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!ProManager.isPro(this)) {
            // 非 Pro 直达引导
            setContentView(R.layout.activity_pro_gate)
            findViewById<MaterialToolbar>(R.id.topAppBar).apply {
                title = "年度趋势报告"
                setNavigationOnClickListener { finish() }
            }
            findViewById<View>(R.id.btnGoPro).setOnClickListener {
                startActivity(android.content.Intent(this, SettingsActivity::class.java))
                finish()
            }
            return
        }

        setContentView(R.layout.activity_yearly_report)
        adapter = MonthAdapter()
        findViewById<RecyclerView>(R.id.rvMonths).apply {
            layoutManager = LinearLayoutManager(this@YearlyReportActivity)
            adapter = this@YearlyReportActivity.adapter
        }
        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnPrevYear).setOnClickListener { year--; load() }
        findViewById<ImageView>(R.id.btnNextYear).setOnClickListener { year++; load() }
        load()
    }

    private fun load() {
        findViewById<TextView>(R.id.tvYear).text = year.toString()
        lifecycleScope.launch(Dispatchers.IO) {
            val cal = Calendar.getInstance().apply { set(year, 0, 1, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            val start = cal.timeInMillis
            val endCal = cal.clone() as Calendar
            endCal.add(Calendar.YEAR, 1)
            val recs = db.transactionDao().getInRange(start, endCal.timeInMillis)

            val monthExpense = FloatArray(12)
            val monthIncome = DoubleArray(12)
            recs.forEach { r ->
                val c = Calendar.getInstance().apply { timeInMillis = r.timestamp }
                if (c.get(Calendar.YEAR) == year) {
                    val m = c.get(Calendar.MONTH)
                    if (r.type == TransactionRecord.TYPE_EXPENSE) monthExpense[m] += r.amount.toFloat()
                    else monthIncome[m] += r.amount
                }
            }
            val yearExpense = monthExpense.sum().toDouble()
            val yearIncome = monthIncome.sum().toDouble()
            val avg = yearExpense / 12.0

            val rows = (0 until 12).map { m ->
                MonthRow("${m + 1}月", monthIncome[m].toDouble(), monthExpense[m].toDouble())
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                findViewById<TextView>(R.id.tvYearExpense).text = FormatUtil.money(yearExpense)
                findViewById<TextView>(R.id.tvYearIncome).text = FormatUtil.money(yearIncome)
                findViewById<TextView>(R.id.tvYearAvg).text = FormatUtil.money(avg)
                renderBarChart(monthExpense)
                adapter.submit(rows, yearExpense)
                findViewById<View>(R.id.tvEmpty).visibility =
                    if (recs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderBarChart(values: FloatArray) {
        val entries = values.mapIndexed { i, v -> com.github.mikephil.charting.data.BarEntry(i.toFloat(), v) }
        val set = com.github.mikephil.charting.data.BarDataSet(entries, "").apply {
            color = Color.parseColor("#C15F3C")
            setDrawValues(false)
            isHighlightEnabled = false
        }
        findViewById<BarChart>(R.id.barChart).apply {
            data = BarData(set).apply { barWidth = 0.6f }
            description.isEnabled = false
            legend.isEnabled = false
            axisRight.isEnabled = false
            setScaleEnabled(false)
            setPinchZoom(false)
            setTouchEnabled(false)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                granularity = 1f
                setDrawGridLines(false)
                textColor = getColor(R.color.text_secondary)
                textSize = 11f
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = "${value.toInt() + 1}月"
                }
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.parseColor("#33E8E6E1")
                textColor = getColor(R.color.text_secondary)
                textSize = 11f
                axisMinimum = 0f
            }
            notifyDataSetChanged()
            invalidate()
        }
    }

    private data class MonthRow(val label: String, val income: Double, val expense: Double)

    private class MonthAdapter : RecyclerView.Adapter<MonthAdapter.VH>() {
        private val rows = mutableListOf<MonthRow>()
        private var yearTotal = 0.0

        fun submit(rows: List<MonthRow>, yearExpense: Double) {
            this.rows.clear()
            this.rows.addAll(rows)
            this.yearTotal = yearExpense
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(v)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val share = if (yearTotal > 0) (r.expense / yearTotal * 100).toInt() else 0
            h.tv1.text = "${r.label} · 支出 ${com.mudasir.smartledger.util.FormatUtil.money(r.expense)}"
            h.tv2.text = "收入 ${com.mudasir.smartledger.util.FormatUtil.money(r.income)} · 占全年支出 $share%"
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tv1: TextView = v.findViewById(android.R.id.text1)
            val tv2: TextView = v.findViewById(android.R.id.text2)
        }
    }
}

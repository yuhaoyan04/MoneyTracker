package com.mudasir.smartledger.activity

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.mudasir.smartledger.R
import com.mudasir.smartledger.adapter.TransactionAdapter
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.Category
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.FormatUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 单个大类的支出/收入详情：
 * - 子类占比饼图（如 餐饮 → 食堂/外卖/下馆子 各自占比）
 * - 该分类完整交易清单（本月 / 全部历史切换），点击任一笔可进入编辑修正打标
 */
class CategoryDetailActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: TransactionAdapter
    private lateinit var pieChart: PieChart

    private var rootName = ""
    private var type = TransactionRecord.TYPE_EXPENSE
    private var year = 0
    private var month = 0
    private var allHistory = false

    /** 与统计页一致的低饱和高级色板（子类饼图用）。 */
    private val palette = intArrayOf(
        Color.parseColor("#C15F3C"), Color.parseColor("#6B8CAE"), Color.parseColor("#9C6B8F"),
        Color.parseColor("#94A374"), Color.parseColor("#CE9B4E"), Color.parseColor("#C07A8A"),
        Color.parseColor("#8C9BAB"), Color.parseColor("#55917F"), Color.parseColor("#6D7BB5"),
        Color.parseColor("#A8A29A"), Color.parseColor("#B08968"), Color.parseColor("#7F9BA6")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_category_detail)

        rootName = intent.getStringExtra(EXTRA_ROOT) ?: ""
        type = intent.getStringExtra(EXTRA_TYPE) ?: TransactionRecord.TYPE_EXPENSE
        val cal = Calendar.getInstance()
        year = intent.getIntExtra(EXTRA_YEAR, cal.get(Calendar.YEAR))
        month = intent.getIntExtra(EXTRA_MONTH, cal.get(Calendar.MONTH))
        if (rootName.isBlank()) { finish(); return }

        findViewById<MaterialToolbar>(R.id.topAppBar).apply {
            title = rootName
            subtitle = "${if (type == TransactionRecord.TYPE_INCOME) "收入" else "支出"} · " +
                FormatUtil.monthLabel(year, month)
            setNavigationOnClickListener { finish() }
        }

        adapter = TransactionAdapter { r ->
            // 点击清单中任一笔 → 编辑页修正打标（闭环）
            startActivity(
                Intent(this, AddEditTransactionActivity::class.java)
                    .putExtra(AddEditTransactionActivity.EXTRA_ID, r.id)
            )
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvList).apply {
            layoutManager = LinearLayoutManager(this@CategoryDetailActivity)
            adapter = this@CategoryDetailActivity.adapter
            isNestedScrollingEnabled = false
        }

        pieChart = findViewById(R.id.pieChart)

        findViewById<MaterialButtonToggleGroup>(R.id.toggleScope).addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newAll = checkedId == R.id.btnAllHistory
            if (newAll != allHistory) {
                allHistory = newAll
                refreshSubtitle()
                load()
            }
        }

        load()
    }

    private fun refreshSubtitle() {
        findViewById<MaterialToolbar>(R.id.topAppBar).subtitle =
            "${if (type == TransactionRecord.TYPE_INCOME) "收入" else "支出"} · " +
                if (allHistory) "全部历史" else FormatUtil.monthLabel(year, month)
    }

    private fun parentOf(name: String, catByName: Map<String, Category>): String =
        catByName[name]?.parentName ?: name

    private fun load() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cats = db.categoryDao().getAll()
            val catByName = cats.associateBy { it.name }
            val parentMap = cats.filter { it.parentName != null }.associate { it.name to it.parentName!! }

            val base = if (allHistory) {
                db.transactionDao().getActiveRaw()
            } else {
                val (s, e) = FormatUtil.monthRange(year, month)
                db.transactionDao().getInRange(s, e)
            }
            val recs = base
                .filter { it.type == type && parentOf(it.categoryName, catByName) == rootName }
                .sortedByDescending { it.timestamp }

            val subEntries = recs
                .groupBy { it.categoryName.ifBlank { "（未分类）" } }
                .map { (n, list) -> Triple(n, list.sumOf { it.amount }, list.size) }
                .sortedByDescending { it.second }

            val total = recs.sumOf { it.amount }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                findViewById<TextView>(R.id.tvTotal).text = FormatUtil.money(total)
                findViewById<TextView>(R.id.tvCount).text =
                    "共 ${recs.size} 笔记录 · ${subEntries.size} 个子类"
                renderPie(subEntries, total)
                adapter.parentMap = parentMap
                adapter.submitList(recs)
                findViewById<View>(R.id.tvEmpty).visibility =
                    if (recs.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View>(R.id.pieCard).visibility =
                    if (subEntries.size >= 2) View.VISIBLE else if (subEntries.size == 1) View.VISIBLE else View.GONE
            }
        }
    }

    private fun renderPie(
        subEntries: List<Triple<String, Double, Int>>,
        total: Double
    ) {
        if (subEntries.isEmpty()) {
            pieChart.clear()
            pieChart.setNoDataText("暂无数据")
            pieChart.setNoDataTextColor(getColor(R.color.text_tertiary))
            return
        }
        val entries = subEntries.map { (name, amt, _) -> PieEntry(amt.toFloat(), name) }
        val colorList = subEntries.indices.map { palette[it % palette.size] }

        val set = PieDataSet(entries, "").apply {
            setColors(colorList)
            setDrawValues(true)
            valueLineColor = Color.TRANSPARENT
            sliceSpace = 3f
            selectionShift = 8f
            setAutomaticallyDisableSliceSpacing(true)
        }
        pieChart.apply {
            data = PieData(set).apply {
                setValueFormatter(PercentFormatter(pieChart))
                setValueTextSize(11f)
                setValueTextColor(Color.WHITE)
            }
            description.isEnabled = false
            setUsePercentValues(true)
            setHoleColor(Color.TRANSPARENT)
            holeRadius = 42f
            transparentCircleRadius = 47f
            setDrawEntryLabels(false)
            setExtraOffsets(8f, 8f, 8f, 8f)
            centerText = SpannableString("合计\n${FormatUtil.money(total)}").apply {
                setSpan(RelativeSizeSpan(0.75f), 0, 2, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.NORMAL), 0, 2, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 2, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
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
                textColor = getColor(R.color.text_secondary)
            }
            animateY(700)
            invalidate()
        }
    }

    companion object {
        const val EXTRA_ROOT = "extra_root"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_YEAR = "extra_year"
        const val EXTRA_MONTH = "extra_month"

        fun start(
            context: android.content.Context,
            root: String,
            type: String,
            year: Int,
            month: Int
        ) {
            context.startActivity(
                Intent(context, CategoryDetailActivity::class.java)
                    .putExtra(EXTRA_ROOT, root)
                    .putExtra(EXTRA_TYPE, type)
                    .putExtra(EXTRA_YEAR, year)
                    .putExtra(EXTRA_MONTH, month)
            )
        }
    }
}

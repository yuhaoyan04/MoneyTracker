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
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)

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
            }
        }
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
        val now = java.util.Calendar.getInstance()
        withContext(Dispatchers.Main) {
            findViewById<android.widget.TextView>(R.id.tvMonthLabel).text =
                FormatUtil.monthLabel(now.get(Calendar.YEAR), now.get(Calendar.MONTH)) + " · 结余"
            findViewById<android.widget.TextView>(R.id.tvBalance).text = FormatUtil.money(income - expense)
            findViewById<android.widget.TextView>(R.id.tvIncome).text = FormatUtil.money(income)
            findViewById<android.widget.TextView>(R.id.tvExpense).text = FormatUtil.money(expense)
            updateBudgetProgress(expense)
        }
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
            pct >= 70 -> android.graphics.Color.parseColor("#FFB300")
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

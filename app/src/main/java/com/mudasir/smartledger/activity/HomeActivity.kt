package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
        val now = java.util.Calendar.getInstance()
        withContext(Dispatchers.Main) {
            findViewById<android.widget.TextView>(R.id.tvMonthLabel).text =
                FormatUtil.monthLabel(now.get(Calendar.YEAR), now.get(Calendar.MONTH)) + " · 结余"
            findViewById<android.widget.TextView>(R.id.tvBalance).text = FormatUtil.money(income - expense)
            findViewById<android.widget.TextView>(R.id.tvIncome).text = FormatUtil.money(income)
            findViewById<android.widget.TextView>(R.id.tvExpense).text = FormatUtil.money(expense)
        }
    }
}

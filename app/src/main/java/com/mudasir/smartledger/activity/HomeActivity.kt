package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不重要：未授权则 AddEdit 静默跳过地点 */ }

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

        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_home)

        // 启动即调度周期备份，确保即使用户不进设置也能自动备份到本机
        com.mudasir.smartledger.util.BackupWorker.schedulePeriodic(this)
        // 静默确认：7天未修正的 PENDING 记录自动确认并学习，闭环自进化
        com.mudasir.smartledger.util.SilentConfirmWorker.schedulePeriodic(this)

        observeData()
    }

    private fun observeData() {
        // 加载分类父级映射，用于列表显示「大类·小类」
        lifecycleScope.launch(Dispatchers.IO) {
            val cats = db.categoryDao().getAll()
            val map = cats.filter { it.parentName != null }.associate { it.name to it.parentName!! }
            withContext(Dispatchers.Main) {
                adapter.parentMap = map
            }
        }

        lifecycleScope.launch {
            db.transactionDao().observeRecent(40).collectLatest { list ->
                adapter.submitList(list)
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

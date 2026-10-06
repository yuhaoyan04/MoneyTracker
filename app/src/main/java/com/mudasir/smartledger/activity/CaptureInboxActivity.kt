package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.mudasir.smartledger.R
import com.mudasir.smartledger.adapter.CaptureAdapter
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureInboxActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: CaptureAdapter

    private val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionBanner() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_inbox)

        findViewById<MaterialToolbar>(R.id.topAppBar).apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }

        adapter = CaptureAdapter(
            onConfirm = { r ->
                lifecycleScope.launch {
                    db.transactionDao().updateStatus(r.id, TransactionRecord.STATUS_CONFIRMED)
                    // 用户直接确认 = 认可该分类，作为正样本训练打标器；并增量备份
                    if (r.categoryName.isNotBlank()) {
                        runCatching {
                            com.mudasir.smartledger.ml.PersonalTagger.learn(
                                this@CaptureInboxActivity, r.type, r.timestamp, r.amount, r.channelName, r.categoryName, r.merchant
                            )
                        }
                    }
                    runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(this@CaptureInboxActivity) }
                    com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
                }
            },
            onEdit = { r ->
                startActivity(Intent(this, AddEditTransactionActivity::class.java)
                    .putExtra(AddEditTransactionActivity.EXTRA_PENDING_ID, r.id))
            },
            onDismiss = { r ->
                lifecycleScope.launch {
                    db.transactionDao().moveToTrash(r.id)
                    com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
                }
            }
        )

        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvPending).apply {
            layoutManager = LinearLayoutManager(this@CaptureInboxActivity)
            this.adapter = this@CaptureInboxActivity.adapter
            isNestedScrollingEnabled = false
        }

        findViewById<View>(R.id.btnEnableNotif).setOnClickListener {
            PermissionHelper.openNotificationListenerSettings(this)
        }
        findViewById<View>(R.id.btnEnableSms).setOnClickListener {
            smsPermLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }

        findViewById<View>(R.id.btnConfirmAll).setOnClickListener {
            lifecycleScope.launch {
                adapter.currentList.forEach {
                    db.transactionDao().updateStatus(it.id, TransactionRecord.STATUS_CONFIRMED)
                    if (it.categoryName.isNotBlank()) {
                        runCatching {
                            com.mudasir.smartledger.ml.PersonalTagger.learn(
                                this@CaptureInboxActivity, it.type, it.timestamp, it.amount, it.channelName, it.categoryName, it.merchant
                            )
                        }
                    }
                }
                runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(this@CaptureInboxActivity) }
                com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
            }
        }

        observe()
        loadCategoryMap()
        com.mudasir.smartledger.util.BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_inbox)
    }

    private fun loadCategoryMap() {
        lifecycleScope.launch {
            val cats = db.categoryDao().getAll()
            val map = cats.filter { it.parentName != null }.associate { it.name to it.parentName!! }
            adapter.parentMap = map
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            db.transactionDao().observePending().collectLatest { list ->
                adapter.submitList(list)
                findViewById<TextView>(R.id.tvEmpty).visibility =
                    if (list.isEmpty()) View.VISIBLE else View.GONE
                findViewById<View>(R.id.btnConfirmAll).visibility =
                    if (list.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBanner()
        // 静默确认/其他端操作后回来时刷新常驻通知
        lifecycleScope.launch {
            com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
        }
    }

    private fun refreshPermissionBanner() {
        val notifOk = PermissionHelper.isNotificationListenerEnabled(this)
        val smsOk = PermissionHelper.hasSmsPermission(this)
        val banner = findViewById<View>(R.id.permBanner)
        banner.visibility = if (notifOk && smsOk) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnEnableNotif).visibility = if (notifOk) View.GONE else View.VISIBLE
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEnableSms).apply {
            visibility = if (smsOk) View.GONE else View.VISIBLE
            text = if (smsOk) "短信已授权" else "授予短信权限"
        }
    }
}

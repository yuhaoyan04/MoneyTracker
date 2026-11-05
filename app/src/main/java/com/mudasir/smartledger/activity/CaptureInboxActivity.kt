package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.mudasir.smartledger.R
import com.mudasir.smartledger.adapter.CaptureAdapter
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.PermissionHelper
import com.mudasir.smartledger.util.CaptureReviewPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureInboxActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private lateinit var adapter: CaptureAdapter
    private val processingIds = mutableSetOf<Long>()

    private val smsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionBanner() }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshNotificationPermBanner() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_inbox)

        findViewById<MaterialToolbar>(R.id.topAppBar).apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { finish() }
        }

        adapter = CaptureAdapter(
            onConfirm = confirm@{ r ->
                if (!processingIds.add(r.id)) return@confirm
                lifecycleScope.launch {
                    try {
                        val fresh = db.transactionDao().getById(r.id)
                        if (fresh != null && CaptureReviewPolicy.canConfirm(fresh.amount) &&
                            db.transactionDao().confirmPending(fresh.id) > 0) {
                            withContext(Dispatchers.IO) {
                                com.mudasir.smartledger.ml.HabitLearning.recordReviewed(
                                    this@CaptureInboxActivity, fresh.copy(status = TransactionRecord.STATUS_CONFIRMED)
                                )
                            }
                            if (fresh.locationName.isNullOrBlank() && (fresh.latitude == null || fresh.latitude == 0.0)) {
                                val place = withContext(Dispatchers.IO) {
                                    runCatching { com.mudasir.smartledger.util.LocationHelper.lastPlace(this@CaptureInboxActivity) }.getOrNull()
                                }
                                if (place != null) db.transactionDao().fillMissingLocation(fresh.id, place.latitude, place.longitude, place.name)
                            }
                            runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(this@CaptureInboxActivity) }
                        }
                        com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
                    } finally {
                        processingIds.remove(r.id)
                    }
                }
            },
            onEdit = { r ->
                startActivity(Intent(this, AddEditTransactionActivity::class.java)
                    .putExtra(AddEditTransactionActivity.EXTRA_PENDING_ID, r.id))
            },
            onDismiss = dismiss@{ r ->
                if (!processingIds.add(r.id)) return@dismiss
                lifecycleScope.launch {
                    try {
                        db.transactionDao().moveToTrash(r.id)
                        com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
                    } finally {
                        processingIds.remove(r.id)
                    }
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
            val all = adapter.currentList.toList()
            val duplicateIds = CaptureReviewPolicy.possiblyDuplicateIds(all)
            val valid = all.filter { CaptureReviewPolicy.canConfirm(it.amount) && it.id !in duplicateIds }
            if (valid.isEmpty()) {
                android.widget.Toast.makeText(this, "请先核对疑似重复交易或补充金额", android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val missing = all.size - valid.size
            AlertDialog.Builder(this)
                .setTitle("确认 ${valid.size} 笔交易？")
                .setMessage("请先核对金额与分类。确认后会记入账本并用于分类学习；与已确认交易疑似重复的记录会保留供核对。" +
                    if (missing > 0) "\n\n另有 $missing 笔金额待补充或疑似重复，会保留在收件箱供逐笔核对。" else "")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认入账") { _, _ ->
            lifecycleScope.launch {
                valid.forEach {
                    if (db.transactionDao().countPossibleCrossSourceDuplicates(it.id, it.type, it.amount,
                            it.timestamp - 300_000L, it.timestamp + 300_000L, it.source, it.packageName) > 0) return@forEach
                    // 幂等确认：只有第一次生效
                    val changed = db.transactionDao().confirmPending(it.id)
                    if (changed > 0 && it.categoryName.isNotBlank()) {
                        withContext(Dispatchers.IO) {
                            com.mudasir.smartledger.ml.HabitLearning.recordReviewed(
                                this@CaptureInboxActivity, it.copy(status = TransactionRecord.STATUS_CONFIRMED)
                            )
                        }
                    }
                }
                runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(this@CaptureInboxActivity) }
                com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
                android.widget.Toast.makeText(this@CaptureInboxActivity, "已处理所选交易", android.widget.Toast.LENGTH_SHORT).show()
            }
                }.show()
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
                adapter.possibleDuplicateIds = CaptureReviewPolicy.possiblyDuplicateIds(list)
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
        com.mudasir.smartledger.util.BottomNavHelper.sync(
            findViewById(R.id.bottomNav), R.id.nav_tab_inbox
        )
        refreshPermissionBanner()
        refreshNotificationPermBanner()
        // 静默确认/其他端操作后回来时刷新常驻通知
        lifecycleScope.launch {
            com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
        }
    }

    /** Android 13+ 通知权限：未授权时显示引导横幅；永久拒绝则跳转系统设置。 */
    private fun refreshNotificationPermBanner() {
        val banner = findViewById<View>(R.id.notifPermBanner)
        if (android.os.Build.VERSION.SDK_INT < 33) {
            banner.visibility = View.GONE
            return
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        banner.visibility = if (granted) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnNotifPerm).setOnClickListener {
            val prefs = getSharedPreferences("perm_prefs", android.content.Context.MODE_PRIVATE)
            val asked = prefs.getBoolean("notif_perm_asked", false)
            val canDialog = !asked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (canDialog) {
                prefs.edit().putBoolean("notif_perm_asked", true).apply()
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // 永久拒绝 → 引导到应用设置
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                ))
            }
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

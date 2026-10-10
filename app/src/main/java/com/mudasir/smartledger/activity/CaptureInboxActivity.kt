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
            onConfirm = { r ->
                // 乐观移除，避免连点导致重复处理
                val current = adapter.currentList.filter { it.id != r.id }
                adapter.submitList(current)
                lifecycleScope.launch {
                    // 对标「记一笔」：记录无位置时补记当前位置（确认时人通常就在附近；
                    // 已有抓取时位置则保留，绝不覆盖）
                    val fresh = db.transactionDao().getById(r.id)
                    var confirmed = false
                    if (fresh != null && fresh.status == TransactionRecord.STATUS_PENDING) {
                        val updated = if (fresh.locationName.isNullOrBlank() &&
                            (fresh.latitude == null || fresh.latitude == 0.0)
                        ) {
                            // 反地理编码是网络请求，在 IO 线程执行，避免主线程卡死
                            val place = withContext(Dispatchers.IO) {
                                runCatching {
                                    com.mudasir.smartledger.util.LocationHelper.lastPlace(this@CaptureInboxActivity)
                                }.getOrNull()
                            }
                            fresh.copy(
                                status = TransactionRecord.STATUS_CONFIRMED,
                                latitude = place?.latitude,
                                longitude = place?.longitude,
                                locationName = place?.name
                            )
                        } else {
                            fresh.copy(status = TransactionRecord.STATUS_CONFIRMED)
                        }
                        db.transactionDao().update(updated)
                        confirmed = true
                    }
                    if (confirmed && r.categoryName.isNotBlank()) {
                        runCatching {
                            com.mudasir.smartledger.ml.PersonalTagger.learn(
                                this@CaptureInboxActivity, r.type, r.timestamp, r.amount, r.channelName, r.categoryName, r.merchant
                            )
                        }
                        runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(this@CaptureInboxActivity) }
                    }
                    com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
                    // 对标「记一笔」：全部处理完毕自动退回上一级页面
                    maybeFinishWhenEmpty()
                }
            },
            onEdit = { r ->
                startActivity(Intent(this, AddEditTransactionActivity::class.java)
                    .putExtra(AddEditTransactionActivity.EXTRA_PENDING_ID, r.id))
            },
            onDismiss = { r ->
                // 乐观移除
                val current = adapter.currentList.filter { it.id != r.id }
                adapter.submitList(current)
                lifecycleScope.launch {
                    db.transactionDao().moveToTrash(r.id)
                    com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
                    maybeFinishWhenEmpty()
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
        findViewById<View>(R.id.btnEnableAcc).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SHOW_CAPTURE_REPAIR, true))
        }

        findViewById<View>(R.id.btnConfirmAll).setOnClickListener {
            lifecycleScope.launch {
                adapter.currentList.toList().forEach {
                    // 幂等确认：只有第一次生效
                    val changed = db.transactionDao().confirmPending(it.id)
                    if (changed > 0 && it.categoryName.isNotBlank()) {
                        runCatching {
                            com.mudasir.smartledger.ml.PersonalTagger.learn(
                                this@CaptureInboxActivity, it.type, it.timestamp, it.amount, it.channelName, it.categoryName, it.merchant
                            )
                        }
                    }
                }
                runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(this@CaptureInboxActivity) }
                com.mudasir.smartledger.util.PendingNotifier.update(this@CaptureInboxActivity)
                // 全部处理完自动退回上一级
                maybeFinishWhenEmpty()
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

    /** 收件箱清空时自动退回上一级（对标「记一笔」保存即退出）。 */
    private suspend fun maybeFinishWhenEmpty() {
        val remaining = db.transactionDao().countPending()
        if (remaining == 0 && !isFinishing && !isDestroyed) {
            withContext(Dispatchers.Main) { finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        PermissionHelper.requestNotificationListenerRebind(this)
        com.mudasir.smartledger.util.BottomNavHelper.sync(
            findViewById(R.id.bottomNav), R.id.nav_tab_inbox
        )
        refreshPermissionBanner()
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) refreshPermissionBanner()
        }, 900L)
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
        val notifAuthorized = PermissionHelper.isNotificationListenerEnabled(this)
        val notifOk = notifAuthorized &&
            com.mudasir.smartledger.util.CaptureServiceState.isNotificationConnected()
        val smsOk = PermissionHelper.hasSmsPermission(this)
        val accOk = PermissionHelper.isAccessibilityServiceEnabled(this) &&
            com.mudasir.smartledger.util.CaptureServiceState.isAccessibilityConnected()
        val banner = findViewById<View>(R.id.permBanner)
        banner.visibility = if (notifOk && smsOk && accOk) View.GONE else View.VISIBLE
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEnableNotif).apply {
            visibility = if (notifOk) View.GONE else View.VISIBLE
            text = if (notifAuthorized) "修复通知监听连接" else "授予通知使用权"
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEnableSms).apply {
            visibility = if (smsOk) View.GONE else View.VISIBLE
            text = if (smsOk) "短信已授权" else "授予短信权限"
        }
        findViewById<View>(R.id.btnEnableAcc).visibility = if (accOk) View.GONE else View.VISIBLE
    }
}

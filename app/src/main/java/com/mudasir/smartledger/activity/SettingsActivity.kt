package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.mudasir.smartledger.BuildConfig
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.AppDatabase
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.AiSettings
import com.mudasir.smartledger.util.AutoBackupManager
import com.mudasir.smartledger.util.BackupWorker
import com.mudasir.smartledger.util.BottomNavHelper
import com.mudasir.smartledger.util.FormatUtil
import com.mudasir.smartledger.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }

    private val smsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissionStatus()
    }

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissionStatus()
        // 权限变化 → 同步常驻通知
        lifecycleScope.launch {
            runCatching { com.mudasir.smartledger.util.PendingNotifier.update(this@SettingsActivity) }
        }
    }

    private val bgLocationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissionStatus()
    }

    private val csvExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let { exportCsv(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.cardAi).setOnClickListener {
            startActivity(Intent(this, AiConfigActivity::class.java))
        }

        findViewById<View>(R.id.btnNotif).setOnClickListener {
            PermissionHelper.openNotificationListenerSettings(this)
        }
        findViewById<View>(R.id.btnSms).setOnClickListener {
            smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }
        findViewById<View>(R.id.btnCaptureRepair).setOnClickListener {
            showCaptureRepairDialog()
        }

        findViewById<View>(R.id.btnBackupNow).setOnClickListener { doBackup() }
        findViewById<View>(R.id.btnRestore).setOnClickListener { doRestore() }
        findViewById<View>(R.id.btnExportCsv).setOnClickListener {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            csvExportLauncher.launch("SmartLedger_${sdf.format(Date())}.csv")
        }
        findViewById<View>(R.id.btnClearExpense).setOnClickListener { confirmClear(TransactionRecord.TYPE_EXPENSE, "支出") }
        findViewById<View>(R.id.btnClearIncome).setOnClickListener { confirmClear(TransactionRecord.TYPE_INCOME, "收入") }

        findViewById<TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"
        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_settings)
        if (intent.getBooleanExtra(EXTRA_SHOW_CAPTURE_REPAIR, false)) {
            window.decorView.post { showCaptureRepairDialog() }
        }
    }

    override fun onResume() {
        super.onResume()
        PermissionHelper.requestNotificationListenerRebind(this)
        com.mudasir.smartledger.util.BottomNavHelper.sync(
            findViewById(R.id.bottomNav), R.id.nav_tab_settings
        )
        refreshPermissionStatus()
        // 从系统设置返回或冷启动时，系统绑定回调可能稍晚于 Activity 恢复。
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) refreshPermissionStatus()
        }, 900L)
        refreshAiStatus()
        refreshBackupStatus()
        BackupWorker.schedulePeriodic(this)
    }

    private fun refreshAiStatus() {
        val cfg = AiSettings.currentConfig(this)
        val status = findViewById<TextView>(R.id.tvAiStatus)
        if (cfg.isReady) {
            status.text = "已配置 · ${cfg.providerName} · ${cfg.model}"
            status.setTextColor(getColorCompat(R.color.color_income))
        } else {
            status.text = "未配置 · 点击填写"
            status.setTextColor(getColorCompat(R.color.color_expense))
        }
    }

    private fun refreshBackupStatus() {
        findViewById<TextView>(R.id.tvBackupStatus).text = AutoBackupManager.lastBackupInfo(this)
    }

    private fun doBackup() {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { AutoBackupManager.backup(this@SettingsActivity) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                refreshBackupStatus()
                Toast.makeText(this@SettingsActivity, if (ok) "已备份到 Download/MoneyTracker" else "备份失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doRestore() {
        AlertDialog.Builder(this)
            .setTitle("从备份恢复")
            .setMessage("将把 Download/MoneyTracker 中的备份覆盖写入当前账本（保留历史清理状态）。继续？")
            .setPositiveButton("恢复") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val n = AutoBackupManager.restore(this@SettingsActivity)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@SettingsActivity,
                            if (n >= 0) "已恢复 $n 条记录" else "未找到备份或恢复失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClear(type: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle("清理$label")
            .setMessage("将把所有已确认的${label}记录移出显示（软删除）。备份文件不会改动，历史仍可恢复。继续？")
            .setPositiveButton("清理") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.transactionDao().clearByType(type)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "已清理$label", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshPermissionStatus() {
        val notifOk = PermissionHelper.isNotificationListenerEnabled(this)
        val smsOk = PermissionHelper.hasSmsPermission(this)
        val tvNotif = findViewById<TextView>(R.id.tvNotifStatus)
        val btnNotif = findViewById<View>(R.id.btnNotif)
        val notifConnected = com.mudasir.smartledger.util.CaptureServiceState.isNotificationConnected()
        tvNotif.text = when {
            !notifOk -> "未授权"
            notifConnected -> "已连接"
            else -> "已授权·连接中"
        }
        tvNotif.setTextColor(getColorCompat(when {
            notifConnected -> R.color.color_income
            notifOk -> R.color.color_warning
            else -> R.color.color_expense
        }))
        btnNotif.visibility = if (notifOk && notifConnected) View.GONE else View.VISIBLE

        val tvSms = findViewById<TextView>(R.id.tvSmsStatus)
        val btnSms = findViewById<View>(R.id.btnSms)
        tvSms.text = if (smsOk) "已授权" else "未授权"
        tvSms.setTextColor(getColorCompat(if (smsOk) R.color.color_income else R.color.color_expense))
        btnSms.visibility = if (smsOk) View.GONE else View.VISIBLE

        refreshNotifPermStatus()
        refreshBgLocStatus()
        refreshAccStatus()
    }

    /** 付款码抓取（无障碍服务）。 */
    private fun refreshAccStatus() {
        val tv = findViewById<TextView>(R.id.tvAccStatus)
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAcc)
        val hint = findViewById<TextView>(R.id.tvAccHint)
        val enabled = PermissionHelper.isAccessibilityServiceEnabled(this)
        val connected = com.mudasir.smartledger.util.CaptureServiceState.isAccessibilityConnected()
        tv.text = when {
            !enabled -> "未授权"
            connected -> "已连接"
            else -> "已授权·未连接"
        }
        tv.setTextColor(getColorCompat(when {
            connected -> R.color.color_income
            enabled -> R.color.color_warning
            else -> R.color.color_expense
        }))
        hint.text = when {
            connected -> "运行正常：系统已绑定付款码监听服务。"
            enabled -> "授权条目存在，但服务尚未绑定。请点“修复连接”检查受限设置和后台保护。"
            android.os.Build.VERSION.SDK_INT >= 33 -> "侧载安装需先在“应用信息”右上角允许受限制的设置，再开启无障碍服务。"
            else -> "请在系统无障碍服务列表中开启“付款码自动记账”。"
        }
        btn.text = if (enabled) "修复连接" else "检查并开启"
        btn.visibility = if (connected) View.GONE else View.VISIBLE
        btn.setOnClickListener {
            showCaptureRepairDialog()
        }
    }

    private fun showCaptureRepairDialog() {
        val huawei = android.os.Build.MANUFACTURER.contains("huawei", ignoreCase = true) ||
            android.os.Build.BRAND.contains("huawei", ignoreCase = true) ||
            android.os.Build.BRAND.contains("honor", ignoreCase = true)
        val vendorTip = if (huawei) {
            "\n\n检测到华为/荣耀设备：还需在“应用启动管理”关闭自动管理，并允许自动启动、关联启动和后台活动。"
        } else {
            "\n\n若服务过一段时间被关闭，请同时取消电池优化并允许应用后台运行。"
        }
        AlertDialog.Builder(this)
            .setTitle("自动抓取修复")
            .setMessage(
                "1. Android 13 及以上侧载安装：先到应用信息右上角菜单，选择“允许受限制的设置”。\n" +
                    "2. 再进入无障碍，开启“付款码自动记账”。\n" +
                    "3. 通知使用权和短信权限分别负责支付通知与银行卡短信。" + vendorTip
            )
            .setPositiveButton("进入无障碍") { _, _ -> PermissionHelper.openAccessibilitySettings(this) }
            .setNeutralButton("应用信息") { _, _ -> PermissionHelper.openAppDetails(this) }
            .setNegativeButton("电池优化") { _, _ -> PermissionHelper.openBatteryOptimizationSettings(this) }
            .show()
    }

    /** 通知栏提醒（Android 13+ POST_NOTIFICATIONS）。 */
    private fun refreshNotifPermStatus() {
        val tv = findViewById<TextView>(R.id.tvNotifPermStatus)
        val btn = findViewById<View>(R.id.btnNotifPerm)
        val granted = android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        tv.text = if (granted) "已开启" else "未开启"
        tv.setTextColor(getColorCompat(if (granted) R.color.color_income else R.color.color_expense))
        btn.visibility = if (granted) View.GONE else View.VISIBLE
        btn.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val prefs = getSharedPreferences("perm_prefs", android.content.Context.MODE_PRIVATE)
                val asked = prefs.getBoolean("notif_perm_asked", false)
                val canDialog = !asked || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                if (canDialog) {
                    prefs.edit().putBoolean("notif_perm_asked", true).apply()
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    ))
                }
            }
        }
    }

    /** 支付时记录位置（后台定位，Android 10+）。 */
    private fun refreshBgLocStatus() {
        val tv = findViewById<TextView>(R.id.tvBgLocStatus)
        val btn = findViewById<View>(R.id.btnBgLoc)
        val fgOk = androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val bgOk = com.mudasir.smartledger.util.LocationHelper.hasBackgroundPermission(this)
        when {
            fgOk && bgOk -> {
                tv.text = "已开启"
                tv.setTextColor(getColorCompat(R.color.color_income))
                btn.visibility = View.GONE
            }
            else -> {
                tv.text = if (fgOk) "需允许「始终允许」" else "未开启"
                tv.setTextColor(getColorCompat(R.color.color_expense))
                btn.visibility = View.VISIBLE
            }
        }
        btn.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                // Android 11+：后台定位只能在系统设置里选「始终允许」
                startActivity(Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                ))
                Toast.makeText(this, "请在 权限 → 位置 中选择「始终允许」", Toast.LENGTH_LONG).show()
            } else {
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private fun getColorCompat(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)

    companion object {
        const val EXTRA_SHOW_CAPTURE_REPAIR = "show_capture_repair"
    }

    private fun exportCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val records = db.transactionDao().getActiveRaw()
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                val sb = StringBuilder()
                sb.append("日期,类型,金额,分类,渠道,支付方式,商户,备注,地点\n")
                for (r in records) {
                    sb.append(sdf.format(Date(r.timestamp))).append(",")
                    sb.append(if (r.type == TransactionRecord.TYPE_EXPENSE) "支出" else "收入").append(",")
                    sb.append(r.amount).append(",")
                    sb.append(escapeCsv(r.categoryName)).append(",")
                    sb.append(escapeCsv(r.channelName)).append(",")
                    sb.append(escapeCsv(r.paymentMethod ?: "")).append(",")
                    sb.append(escapeCsv(r.merchant ?: "")).append(",")
                    sb.append(escapeCsv(r.note ?: "")).append(",")
                    sb.append(escapeCsv(r.locationName ?: "")).append("\n")
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                    out.write(sb.toString().toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "已导出 ${records.size} 条交易记录", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun escapeCsv(s: String): String {
        return if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            "\"${s.replace("\"", "\"\"")}\""
        } else s
    }
}

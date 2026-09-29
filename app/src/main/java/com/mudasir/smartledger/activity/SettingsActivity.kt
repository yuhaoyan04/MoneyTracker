package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Intent
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
import com.mudasir.smartledger.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }

    private val smsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissionStatus()
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

        findViewById<View>(R.id.btnBackupNow).setOnClickListener { doBackup() }
        findViewById<View>(R.id.btnRestore).setOnClickListener { doRestore() }
        findViewById<View>(R.id.btnClearExpense).setOnClickListener { confirmClear(TransactionRecord.TYPE_EXPENSE, "支出") }
        findViewById<View>(R.id.btnClearIncome).setOnClickListener { confirmClear(TransactionRecord.TYPE_INCOME, "收入") }

        findViewById<TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"
        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_settings)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
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
        tvNotif.text = if (notifOk) "已开启" else "未开启"
        tvNotif.setTextColor(getColorCompat(if (notifOk) R.color.color_income else R.color.color_expense))
        btnNotif.visibility = if (notifOk) View.GONE else View.VISIBLE

        val tvSms = findViewById<TextView>(R.id.tvSmsStatus)
        val btnSms = findViewById<View>(R.id.btnSms)
        tvSms.text = if (smsOk) "已授权" else "未授权"
        tvSms.setTextColor(getColorCompat(if (smsOk) R.color.color_income else R.color.color_expense))
        btnSms.visibility = if (smsOk) View.GONE else View.VISIBLE
    }

    private fun getColorCompat(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)
}

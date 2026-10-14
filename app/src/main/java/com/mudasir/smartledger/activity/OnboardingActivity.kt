package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.mudasir.smartledger.R
import com.mudasir.smartledger.data.TransactionRecord
import com.mudasir.smartledger.util.BillImportParser
import com.mudasir.smartledger.util.BillImporter
import com.mudasir.smartledger.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首启引导向导（仅首次展示）：
 * 1 欢迎 → 2 权限三件套（实时状态） → 3 导入历史账单（aha 时刻 + 喂饱打标引擎） → 4 完成
 */
class OnboardingActivity : AppCompatActivity() {

    private var step = 1
    private var importing = false

    private val smsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshStep2()
    }

    private val billImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importBill(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        findViewById<MaterialButton>(R.id.btnObNotif).setOnClickListener {
            PermissionHelper.openNotificationListenerSettings(this)
            Toast.makeText(this, "请在列表中找到 SmartLedger 并允许", Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialButton>(R.id.btnObSms).setOnClickListener {
            smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }
        findViewById<MaterialButton>(R.id.btnObUsage).setOnClickListener {
            PermissionHelper.openUsageAccessSettings(this)
            Toast.makeText(this, "请允许「使用情况访问权限」", Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialButton>(R.id.btnObImport).setOnClickListener {
            if (importing) return@setOnClickListener
            runCatching {
                billImportLauncher.launch(arrayOf("*/*"))
            }.onFailure { Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show() }
        }
        findViewById<TextView>(R.id.tvObSkip).setOnClickListener { next() }
        findViewById<MaterialButton>(R.id.btnObMain).setOnClickListener { next() }

        showStep(1)
    }

    override fun onResume() {
        super.onResume()
        if (step == 2) refreshStep2()
    }

    private fun next() {
        when (step) {
            1 -> showStep(2)
            2 -> showStep(3)
            3 -> showStep(4)
            else -> finishOnboarding()
        }
    }

    private fun showStep(n: Int) {
        step = n
        findViewById<View>(R.id.step1).visibility = if (n == 1) View.VISIBLE else View.GONE
        findViewById<View>(R.id.step2).visibility = if (n == 2) View.VISIBLE else View.GONE
        findViewById<View>(R.id.step3).visibility = if (n == 3) View.VISIBLE else View.GONE
        findViewById<View>(R.id.step4).visibility = if (n == 4) View.VISIBLE else View.GONE

        findViewById<View>(R.id.dot1).setBackgroundResource(if (n == 1) R.color.teal_main else R.color.divider)
        findViewById<View>(R.id.dot2).setBackgroundResource(if (n == 2) R.color.teal_main else R.color.divider)
        findViewById<View>(R.id.dot3).setBackgroundResource(if (n == 3) R.color.teal_main else R.color.divider)
        findViewById<View>(R.id.dot4).setBackgroundResource(if (n == 4) R.color.teal_main else R.color.divider)

        val btn = findViewById<MaterialButton>(R.id.btnObMain)
        btn.text = when (n) {
            1 -> "开始"
            2 -> "下一步"
            3 -> "下一步"
            else -> "开始使用"
        }
        findViewById<TextView>(R.id.tvObSkip).visibility = if (n in 1..3) View.VISIBLE else View.GONE

        if (n == 2) refreshStep2()
    }

    private fun refreshStep2() {
        val notifOk = PermissionHelper.isNotificationListenerEnabled(this)
        val smsOk = PermissionHelper.hasSmsPermission(this)
        val usageOk = PermissionHelper.hasUsageAccess(this)

        findViewById<TextView>(R.id.tvObNotif).apply {
            text = if (notifOk) "✓ 已开启" else "未开启"
            setTextColor(getColor(if (notifOk) R.color.color_income else R.color.color_expense))
        }
        findViewById<MaterialButton>(R.id.btnObNotif).visibility = if (notifOk) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.tvObSms).apply {
            text = if (smsOk) "✓ 已授权" else "未授权"
            setTextColor(getColor(if (smsOk) R.color.color_income else R.color.color_expense))
        }
        findViewById<MaterialButton>(R.id.btnObSms).visibility = if (smsOk) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.tvObUsage).apply {
            text = if (usageOk) "✓ 已开启" else "未开启"
            setTextColor(getColor(if (usageOk) R.color.color_income else R.color.text_tertiary))
        }
        findViewById<MaterialButton>(R.id.btnObUsage).visibility = if (usageOk) View.GONE else View.VISIBLE
    }

    /** 导入历史账单：自动去重入库 + AI 分类（aha 时刻）。 */
    private fun importBill(uri: android.net.Uri) {
        importing = true
        val tvResult = findViewById<TextView>(R.id.tvObImportResult)
        tvResult.visibility = View.VISIBLE
        tvResult.text = "正在解析账单…"
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val parsed = bytes?.let { BillImportParser.parse(it) }
            when {
                bytes == null || parsed == null ->
                    withContext(Dispatchers.Main) { tvResult.text = "无法读取所选文件" }
                parsed is BillImportParser.Result.Failure ->
                    withContext(Dispatchers.Main) { tvResult.text = parsed.reason }
                parsed is BillImportParser.Result.Success -> {
                    val report = BillImporter.execute(
                        this@OnboardingActivity, parsed.bills, parsed.sourceChannel, skipDuplicates = true
                    )
                    withContext(Dispatchers.Main) {
                        tvResult.text = "已导入 ${report.inserted} 笔（跳过已有 ${report.skippedExisting} 笔）"
                        Toast.makeText(
                            this@OnboardingActivity,
                            "导入 ${report.inserted} 笔 · 去「统计」看看你的消费画像",
                            Toast.LENGTH_LONG
                        ).show()
                        // aha 时刻达成 → 直接引导到完成页
                        showStep(4)
                    }
                    runCatching { com.mudasir.smartledger.util.AutoBackupManager.backup(applicationContext) }
                }
            }
            importing = false
        }
    }

    private fun finishOnboarding() {
        getSharedPreferences("onboarding_prefs", MODE_PRIVATE).edit().putBoolean("done", true).apply()
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}

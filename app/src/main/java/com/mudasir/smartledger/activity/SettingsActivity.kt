package com.mudasir.smartledger.activity

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
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
import com.mudasir.smartledger.util.BillImportParser
import com.mudasir.smartledger.util.BillImporter
import com.mudasir.smartledger.util.BottomNavHelper
import com.mudasir.smartledger.util.FormatUtil
import com.mudasir.smartledger.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
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

    private val ledgerExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportPortableBackup(it) }
    }

    private val guardLogExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let {
            lifecycleScope.launch {
                runCatching { com.mudasir.smartledger.util.GuardDiagnosticLog.export(this@SettingsActivity, it) }
                    .onSuccess { bytes -> Toast.makeText(this@SettingsActivity, "诊断文件已保存（$bytes 字节）", Toast.LENGTH_LONG).show() }
                    .onFailure { Toast.makeText(this@SettingsActivity, "没有可导出的记录或保存失败，请重试", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private val ledgerImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { restorePortableBackup(it) }
    }

    private val billImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importBill(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setupSettingsSections(savedInstanceState)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.cardAi).setOnClickListener {
            startActivity(Intent(this, AiConfigActivity::class.java))
        }

        findViewById<View>(R.id.btnNotif).setOnClickListener {
            PermissionHelper.openNotificationListenerSettings(this)
        }
        findViewById<View>(R.id.btnGuardToggle).setOnClickListener { toggleGuard() }
        findViewById<View>(R.id.btnAccCapture).setOnClickListener {
            PermissionHelper.openAccessibilitySettings(this)
            Toast.makeText(this, "在「已下载的服务」中开启「支付页增强检测」", Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btnSms).setOnClickListener {
            smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }
        findViewById<View>(R.id.btnCaptureDiagnostics).setOnClickListener { showCaptureDiagnostics() }
        findViewById<View>(R.id.btnBackgroundSettings).setOnClickListener {
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                startActivity(Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                ))
            }
            val hint = if (android.os.Build.MANUFACTURER.contains("HUAWEI", ignoreCase = true)) {
                "还请在手机管家 → 应用启动管理中手动允许三项后台开关"
            } else {
                "请将 SmartLedger 设为不受电池优化限制"
            }
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        }

        findViewById<View>(R.id.btnBackupNow).setOnClickListener { doBackup() }
        findViewById<View>(R.id.btnExportBackup).setOnClickListener {
            ledgerExportLauncher.launch(AutoBackupManager.suggestedExportName())
        }
        findViewById<View>(R.id.btnRestore).setOnClickListener { doRestore() }
        findViewById<View>(R.id.btnImportBill).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("导入支付账单")
                .setMessage(
                    "支持微信（我→服务→钱包→账单→下载账单）和支付宝（我的→账单→开具交易流水）导出的原始 CSV / XLSX 文件。\n\n" +
                        "用于补记通知抓取覆盖不到的支付（如付款码、扫一扫），导入时自动按指纹去重并 AI 分类。"
                )
                .setPositiveButton("选择 CSV 文件") { _, _ ->
                    runCatching {
                        // 微信/支付宝账单在不同文件管理器下 MIME 各异（excel/csv/octet），放开为全部类型
                        billImportLauncher.launch(arrayOf("*/*"))
                    }.onFailure {
                        Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
        findViewById<View>(R.id.btnNotif).setOnClickListener {
            PermissionHelper.openNotificationListenerSettings(this)
        }
        findViewById<View>(R.id.btnThreshold).setOnClickListener { editThreshold() }
        findViewById<View>(R.id.btnGuardMode).setOnClickListener { editGuardMode() }
        findViewById<View>(R.id.btnCooldown).setOnClickListener { editCooldown() }
        findViewById<View>(R.id.btnGuardScope).setOnClickListener { editGuardScope() }
        findViewById<View>(R.id.btnGuardDebug).setOnClickListener { showGuardDebug() }
        findViewById<View>(R.id.btnVisualGuard).setOnClickListener { editVisualGuard() }
        findViewById<View>(R.id.btnVisualTaobaoSku).setOnClickListener { editVisualTaobaoSku() }
        findViewById<View>(R.id.btnVisualMulti).setOnClickListener { editVisualMulti() }
        findViewById<View>(R.id.btnVisualAlipay).setOnClickListener { editVisualAlipay() }
        findViewById<View>(R.id.btnAppearance).setOnClickListener { editAppearance() }
        refreshAppearance()
        findViewById<View>(R.id.btnAutoConfirm).setOnClickListener { editAutoConfirm() }
        findViewById<View>(R.id.btnBall).setOnClickListener { toggleBall() }
        findViewById<View>(R.id.btnWishlist).setOnClickListener {
            startActivity(Intent(this, WishlistActivity::class.java))
        }
        findViewById<View>(R.id.btnProActivate).setOnClickListener { activatePro() }
        findViewById<View>(R.id.btnProDeactivate).setOnClickListener {
            com.mudasir.smartledger.util.ProManager.deactivate(this)
            refreshProStatus()
            Toast.makeText(this, "已退出 Pro 激活", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnWebdavConfig).setOnClickListener { editWebdavConfig() }
        findViewById<View>(R.id.btnWebdavUpload).setOnClickListener { webdavUpload() }
        findViewById<View>(R.id.btnWebdavRestore).setOnClickListener { webdavRestore() }
        findViewById<View>(R.id.btnExportCsv).setOnClickListener {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            csvExportLauncher.launch("SmartLedger_${sdf.format(Date())}.csv")
        }
        findViewById<View>(R.id.btnClearExpense).setOnClickListener { confirmClear(TransactionRecord.TYPE_EXPENSE, "支出") }
        findViewById<View>(R.id.btnClearIncome).setOnClickListener { confirmClear(TransactionRecord.TYPE_INCOME, "收入") }

        findViewById<TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"
        findViewById<View>(R.id.rowPrivacy).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("隐私承诺")
                .setMessage(com.mudasir.smartledger.util.PrivacyText.FULL)
                .setPositiveButton("知道了", null)
                .show()
        }
        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_settings)
    }

    private data class SettingsSection(
        val key: String, val title: String, val header: Int, val body: Int, val chevron: Int,
        val expandedByDefault: Boolean = false
    )

    private val settingsSections = listOf(
        SettingsSection("guard", "想好再买", R.id.headerGuard, R.id.bodyGuard, R.id.chevronGuard),
        SettingsSection("appearance", "外观", R.id.headerAppearance, R.id.bodyAppearance, R.id.chevronAppearance),
        SettingsSection("capture", "自动记账与权限", R.id.headerCapture, R.id.bodyCapture, R.id.chevronCapture),
        SettingsSection("data", "数据与备份", R.id.headerData, R.id.bodyData, R.id.chevronData),
        SettingsSection("pro", "MoneyTracker Pro", R.id.headerPro, R.id.bodyPro, R.id.chevronPro),
        SettingsSection("about", "关于与隐私", R.id.headerAbout, R.id.bodyAbout, R.id.chevronAbout)
    )

    private fun setupSettingsSections(savedInstanceState: Bundle?) {
        settingsSections.forEach { section ->
            val header = findViewById<View>(section.header)
            val body = findViewById<View>(section.body)
            val chevron = findViewById<TextView>(section.chevron)
            fun setExpanded(expanded: Boolean) {
                body.visibility = if (expanded) View.VISIBLE else View.GONE
                chevron.text = if (expanded) "⌃" else "⌄"
                header.contentDescription = "${section.title}，${if (expanded) "已展开" else "已折叠"}"
            }
            setExpanded(savedInstanceState?.getBoolean("settings_section_${section.key}", section.expandedByDefault)
                ?: section.expandedByDefault)
            header.setOnClickListener { setExpanded(body.visibility != View.VISIBLE) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        settingsSections.forEach { section ->
            outState.putBoolean("settings_section_${section.key}", findViewById<View>(section.body).visibility == View.VISIBLE)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        com.mudasir.smartledger.util.GuardDiagnosticLog.settingsResumed()
        com.mudasir.smartledger.util.PurchaseGuardService.dismissForSettings()
        com.mudasir.smartledger.util.BottomNavHelper.sync(
            findViewById(R.id.bottomNav), R.id.nav_tab_settings
        )
        refreshPermissionStatus()
        refreshAiStatus()
        refreshBackupStatus()
        refreshGuardStatus()
        refreshAccCaptureStatus()
        refreshAutoConfirmStatus()
        refreshProStatus()
        BackupWorker.schedulePeriodic(this)
    }

    /** 想好再买：状态刷新（使用情况权限 + 守护服务运行态）。 */
    private fun refreshGuardStatus() {
        val tv = findViewById<TextView>(R.id.tvGuardStatus)
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGuardToggle)
        val usageOk = PermissionHelper.hasUsageAccess(this)
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val enabled = prefs.getBoolean("guard_enabled", false)
        val running = com.mudasir.smartledger.util.PurchaseGuardService.isRunning
        when {
            enabled -> {
                tv.text = when {
                    !running -> "已开启 · 守护服务未运行"
                    !usageOk -> "已开启 · 使用情况权限未授予"
                    else -> "已开启"
                }
                tv.setTextColor(getColorCompat(if (running && usageOk) R.color.color_income else R.color.color_warning))
                btn.visibility = View.VISIBLE
                btn.text = "关闭提醒"
            }
            !usageOk -> {
                tv.text = "需授权使用情况访问"
                tv.setTextColor(getColorCompat(R.color.color_expense))
                btn.visibility = View.VISIBLE
                btn.text = "去授权"
            }
            else -> {
                tv.text = "未开启"
                tv.setTextColor(getColorCompat(R.color.color_expense))
                btn.visibility = View.VISIBLE
                btn.text = "去开启"
            }
        }
        // 门槛显示
        val thresholdText = com.mudasir.smartledger.capture.GuardThreshold.read(prefs).stripTrailingZeros().toPlainString()
        findViewById<TextView>(R.id.tvThreshold).text = "¥$thresholdText"
        findViewById<TextView>(R.id.tvGuardOverview).text =
            "${if (enabled) "已开启" else "未开启"} · 提醒门槛 ¥$thresholdText"

        // WebDAV 配置状态
        findViewById<TextView>(R.id.tvWebdavStatus).text =
            if (com.mudasir.smartledger.util.WebDavSync.loadConfig(this) != null) "已配置" else "未配置"

        // 悬浮球状态
        val ballOn = prefs.getBoolean("ball_enabled", false) &&
            com.mudasir.smartledger.util.PurchaseGuardService.isRunning
        findViewById<TextView>(R.id.tvBallStatus).apply {
            text = if (ballOn) "已开启" else "已关闭"
            setTextColor(getColorCompat(if (ballOn) R.color.color_income else R.color.text_secondary))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBall).text =
            if (ballOn) "关闭悬浮球" else "开启悬浮球"

        // 提醒方式 / 冷静时长 / 提醒范围
        val mode = prefs.getString("guard_mode", "overlay") ?: "overlay"
        findViewById<TextView>(R.id.tvGuardMode).text =
            if (mode == "overlay") {
                if (android.provider.Settings.canDrawOverlays(this)) "全屏冷静" else "缺少悬浮窗权限，将使用通知"
            } else "仅通知"
        val cooldown = prefs.getInt("guard_cooldown_seconds", 5)
        findViewById<TextView>(R.id.tvCooldown).text = "$cooldown 秒"
        findViewById<TextView>(R.id.tvGuardScope).text =
            "付款页且金额超过门槛"
    }

    private fun refreshAppearance() {
        val selected = com.mudasir.smartledger.util.AppearancePreferences.selected(this)
        findViewById<TextView>(R.id.tvAppearance).text = when (selected) {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> "深色"
            else -> "跟随系统"
        }
    }

    private fun editAppearance() {
        val modes = listOf(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO, androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        val current = com.mudasir.smartledger.util.AppearancePreferences.selected(this)
        AlertDialog.Builder(this).setTitle("外观")
            .setSingleChoiceItems(arrayOf("跟随系统", "浅色", "深色"), modes.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                dialog.dismiss()
                com.mudasir.smartledger.util.AppearancePreferences.save(this, modes[which])
            }.setNegativeButton("取消", null).show()
    }

    private fun editVisualGuard() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val enabled = prefs.getBoolean("guard_visual_order_pages_enabled", false)
        AlertDialog.Builder(this)
            .setTitle("屏幕识别（测试）")
            .setMessage("${if (enabled) "当前已开启" else "当前已关闭"}。开启后，会在淘宝订单列表及订单确认页面尝试截取屏幕，在本机判断是否为单笔付款页面并识别金额；画面和识别文字不保存、不上传。识别组件由 Google ML Kit 提供，可能向 Google 发送设备和性能等使用指标。截图可能包含屏幕上的订单和个人信息；安全窗口无法保证读取。识别不确定时不会提醒，可能漏检或误判。")
            .setPositiveButton(if (enabled) "关闭" else "开启") { _, _ ->
                prefs.edit().putBoolean("guard_visual_order_pages_enabled", !enabled).apply()
                com.mudasir.smartledger.util.VisualPaymentHandoff.clear()
                Toast.makeText(this, if (enabled) "屏幕识别已关闭" else "屏幕识别已开启；请确认无障碍服务仍然启用", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun editVisualMulti() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val enabled = prefs.getBoolean("guard_visual_multi_app_enabled", false)
        AlertDialog.Builder(this).setTitle("更多购物应用识别（测试）")
            .setMessage("${if (enabled) "当前已开启" else "当前已关闭"}。开启后，在京东、拼多多、美团、美团外卖及 12306 等受支持应用的部分页面，本应用会截取当前屏幕并在本机判断付款信息。画面可能包含地址、订单和其他个人信息；图片和识别文字不保存、不上传。Google ML Kit 可能向 Google 发送设备和性能指标。各应用及支付宝、银行支付交接尚未全部真机验证，可能漏检或误判。")
            .setPositiveButton(if (enabled) "关闭" else "开启") { _, _ ->
                prefs.edit().putBoolean("guard_visual_multi_app_enabled", !enabled).apply()
                com.mudasir.smartledger.util.VisualPaymentHandoff.clear()
                Toast.makeText(this, if (enabled) "更多应用识别已关闭" else "更多应用识别已开启", Toast.LENGTH_LONG).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun editVisualTaobaoSku() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val enabled = prefs.getBoolean("guard_visual_taobao_sku_enabled", false)
        AlertDialog.Builder(this).setTitle("淘宝商品弹窗识别（测试）")
            .setMessage("${if (enabled) "当前已开启" else "当前已关闭"}。开启后，还需开启‘屏幕识别（测试）’和守护服务。淘宝商品规格或购买弹窗出现时，应用会短暂截取该购物页面，在本机寻找订单确认特征与明确待付金额。截图可能包含商品、订单及个人信息，图片和识别文字不保存、不上传；Google ML Kit 可能发送设备及性能指标。页面安全限制或识别错误可能导致漏检或误判，无法保证付款前弹出。")
            .setPositiveButton(if (enabled) "关闭" else "开启") { _, _ ->
                prefs.edit().putBoolean("guard_visual_taobao_sku_enabled", !enabled).apply()
                com.mudasir.smartledger.util.VisualPaymentHandoff.clear()
                Toast.makeText(this, if (enabled) "淘宝商品弹窗识别已关闭" else "已开启；请同时开启屏幕识别和守护服务", Toast.LENGTH_LONG).show()
            }.setNegativeButton("取消", null).show()
    }

    private fun editVisualAlipay() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val enabled = prefs.getBoolean("guard_visual_alipay_enabled", false)
        AlertDialog.Builder(this).setTitle("支付宝付款金额识别（测试）")
            .setMessage("${if (enabled) "当前已开启" else "当前已关闭"}。开启后，还需开启‘更多购物应用识别’和守护服务。仅在识别到京东待付款收银台的确认付款操作、随后进入支付宝指定付款窗口时，短暂截取支付宝画面以在本机识别优惠后的金额。画面可能包含账号、银行卡和交易信息；图片和识别文字不保存、不上传。Google ML Kit 可能发送设备和性能指标。安全窗口可能无法读取，识别及浮层可能失败；不会自动付款。")
            .setPositiveButton(if (enabled) "关闭" else "开启") { _, _ ->
                prefs.edit().putBoolean("guard_visual_alipay_enabled", !enabled).apply()
                Toast.makeText(this, if (enabled) "支付宝付款识别已关闭" else "支付宝付款识别已开启；请同时开启其他购物应用识别", Toast.LENGTH_LONG).show()
            }.setNegativeButton("取消", null).show()
    }

    /** 提醒范围：精确（进入付款页面）/ 宽松（打开支付应用）。 */
    private fun editGuardScope() {
        AlertDialog.Builder(this)
            .setTitle("付款页识别说明")
            .setMessage("监测支持的购物与支付应用，只在可读取的付款确认页中识别到明确实付金额，且金额严格大于门槛时提醒。无法读取或金额冲突时不会提醒。")
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 提醒方式：全屏冷静（强制手动确认才放行）/ 仅通知（轻提醒）。 */
    private fun editGuardMode() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val current = prefs.getString("guard_mode", "overlay") ?: "overlay"
        val options = arrayOf(
            "全屏冷静 · 倒计时后手动关闭（需要悬浮窗权限）",
            "仅通知 · 轻提醒，不打断操作"
        )
        var picked = if (current == "overlay") 0 else 1
        AlertDialog.Builder(this)
            .setTitle("冷静强度")
            .setSingleChoiceItems(options, picked) { _, which -> picked = which }
            .setPositiveButton("保存") { _, _ ->
                val mode = if (picked == 0) "overlay" else "notify"
                prefs.edit().putString("guard_mode", mode).apply()
                com.mudasir.smartledger.util.GuardDiagnosticLog.settingChanged("guard_mode", mode)
                if (mode == "overlay" && !android.provider.Settings.canDrawOverlays(this)) {
                    // 引导授予悬浮窗权限
                    runCatching {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.fromParts("package", packageName, null)
                            )
                        )
                    }
                    Toast.makeText(this, "请允许「显示在其他应用上层」，否则会退回通知提醒", Toast.LENGTH_LONG).show()
                }
                refreshGuardStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 冷静时长：3-30 秒。 */
    private fun editCooldown() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val current = prefs.getInt("guard_cooldown_seconds", 5)
        val options = arrayOf("3 秒", "5 秒", "10 秒", "15 秒", "20 秒")
        val values = intArrayOf(3, 5, 10, 15, 20)
        var picked = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("冷静时长")
            .setSingleChoiceItems(options, picked) { _, which -> picked = which }
            .setPositiveButton("保存") { _, _ ->
                prefs.edit().putInt("guard_cooldown_seconds", values[picked]).apply()
                com.mudasir.smartledger.util.GuardDiagnosticLog.settingChanged("cooldown", values[picked].toString())
                refreshGuardStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 守护服务开关：首次需授权「使用情况访问」，之后一键启停。 */
    private fun toggleGuard() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val usageOk = PermissionHelper.hasUsageAccess(this)
        val running = prefs.getBoolean("guard_enabled", false)
        when {
            running -> {
                prefs.edit().putBoolean("guard_enabled", false).apply()
                com.mudasir.smartledger.util.GuardDiagnosticLog.settingChanged("guard_enabled", "false")
                if (!prefs.getBoolean("ball_enabled", false)) {
                    stopService(Intent(this, com.mudasir.smartledger.util.PurchaseGuardService::class.java))
                }
                Toast.makeText(this, "想好再买已关闭", Toast.LENGTH_SHORT).show()
                refreshGuardStatus()
            }
            !usageOk -> {
                PermissionHelper.openUsageAccessSettings(this)
                Toast.makeText(this, "请允许 SmartLedger 的「使用情况访问权限」，返回后再次点击开启", Toast.LENGTH_LONG).show()
            }
            else -> {
                // 全屏冷静模式需要悬浮窗权限（未授权时服务自动退回通知模式）
                val mode = prefs.getString("guard_mode", "overlay") ?: "overlay"
                if (mode == "overlay" && !android.provider.Settings.canDrawOverlays(this)) {
                    runCatching {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.fromParts("package", packageName, null)
                            )
                        )
                    }
                    Toast.makeText(this, "建议允许「显示在其他应用上层」以启用全屏冷静，否则将使用通知提醒", Toast.LENGTH_LONG).show()
                }
                val started = runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(
                        this, Intent(this, com.mudasir.smartledger.util.PurchaseGuardService::class.java)
                    )
                }.onFailure {
                    Toast.makeText(this, "启动守护服务失败：${it.message}", Toast.LENGTH_LONG).show()
                }.isSuccess
                if (!started) return
                prefs.edit().putBoolean("guard_enabled", true).apply()
                com.mudasir.smartledger.util.GuardDiagnosticLog.settingChanged("guard_enabled", "true")
                Toast.makeText(this, "想好再买已开启 · 进入支付应用时会提醒你", Toast.LENGTH_LONG).show()
                refreshGuardStatus()
            }
        }
    }

    /** A stable, scrollable snapshot: explicit refresh never interrupts text selection. */
    private fun showGuardDebug() {
        val logStatus = TextView(this).apply { textSize = 12f }
        val recordButton = com.google.android.material.button.MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { textSize = 12f }
        val exportButton = com.google.android.material.button.MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "保存文件"; textSize = 12f }
        val clearButton = com.google.android.material.button.MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "清空"; textSize = 12f }
        fun refreshLogState() {
            val recording = com.mudasir.smartledger.util.GuardDiagnosticLog.isRecording()
            recordButton.text = if (recording) "停止记录" else "开始记录"
            val failure = com.mudasir.smartledger.util.GuardDiagnosticLog.lastError
            logStatus.text = when {
                failure != null -> "记录失败（$failure），请重新开始"
                recording -> "正在记录 · 最长 30 分钟"
                else -> "未记录 · 可保存已有记录"
            }
        }
        recordButton.setOnClickListener {
            val log = com.mudasir.smartledger.util.GuardDiagnosticLog
            if (log.isRecording()) log.stop() else log.start(this)
            refreshLogState()
        }
        exportButton.setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            guardLogExportLauncher.launch("MoneyTracker-guard-$stamp.txt")
        }
        clearButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("清空本机诊断记录？")
                .setMessage("会停止记录并删除应用内保存的诊断日志；已导出的文件不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ ->
                    lifecycleScope.launch {
                        recordButton.isEnabled = false
                        exportButton.isEnabled = false
                        clearButton.isEnabled = false
                        runCatching { com.mudasir.smartledger.util.GuardDiagnosticLog.clear(this@SettingsActivity) }
                            .onSuccess { Toast.makeText(this@SettingsActivity, "诊断记录已清空", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(this@SettingsActivity, "清空失败，请重试", Toast.LENGTH_SHORT).show() }
                        refreshLogState()
                        recordButton.isEnabled = true
                        exportButton.isEnabled = true
                        clearButton.isEnabled = true
                    }
                }.show()
        }
        refreshLogState()
        val tv = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        val dp = resources.displayMetrics.density
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(tv)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = (24 * dp).toInt()
            setPadding(horizontal, (4 * dp).toInt(), horizontal, 0)
            addView(TextView(this@SettingsActivity).apply {
                text = "可手动记录受支持应用的页面与操作类型，最多 30 分钟，保留最新约 256 KB，直至清空或卸载。不保存页面原文、输入内容或付款金额。导出前请检查设备信息。"
                textSize = 13f
            })
            addView(logStatus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * dp).toInt()
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                listOf(recordButton, exportButton, clearButton).forEach { button ->
                    addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
            })
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.27f).toInt()
                    .coerceAtLeast((80 * dp).toInt()).coerceAtMost((260 * dp).toInt())
            ).apply { topMargin = (12 * dp).toInt() })
        }
        fun snapshot(): String {
            val svc = com.mudasir.smartledger.util.PurchaseGuardService
            val guardPrefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
            return buildString {
                append("版本：${BuildConfig.VERSION_NAME}\n")
                append("守护服务：${if (svc.isRunning) "运行中" else "未运行"}\n")
                append("无障碍服务：${if (com.mudasir.smartledger.util.AccessibilityCaptureService.isRunning) "运行中" else "未运行"}\n")
                append("系统：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}，API ${android.os.Build.VERSION.SDK_INT}\n")
                append("开关：${guardPrefs.getBoolean("guard_enabled", false)}，模式：${guardPrefs.getString("guard_mode", "overlay")}，范围：${guardPrefs.getString("guard_scope", "payment")}\n")
                append("提醒门槛：${com.mudasir.smartledger.capture.GuardThreshold.read(guardPrefs).toPlainString()} 元\n")
                append("权限：使用情况=${PermissionHelper.hasUsageAccess(this@SettingsActivity)}，无障碍=${PermissionHelper.isAccessibilityCaptureEnabled(this@SettingsActivity)}，悬浮窗=${android.provider.Settings.canDrawOverlays(this@SettingsActivity)}\n")
                if (svc.debugUpdatedAt == 0L) {
                    append("\n尚无页面事件；请进入付款页面后返回刷新。")
                } else {
                    val age = ((System.currentTimeMillis() - svc.debugUpdatedAt) / 1000).coerceAtLeast(0)
                    append("前台应用：${svc.debugPkg}\n当前页面：${svc.debugCls}\n")
                    append("最近更新：${age} 秒前；匹配不等于成功拦截。")
                    if (svc.debugHistory.isNotEmpty()) {
                        append("\n\n最近页面：\n")
                        append(svc.debugHistory.joinToString("\n") { "· $it" })
                    }
                    if (svc.debugFailure.isNotBlank()) append("\n\n最近异常详情：\n${svc.debugFailure}")
                }
            }
        }
        tv.text = snapshot()
        val dialog = AlertDialog.Builder(this)
            .setTitle("检测自检")
            .setView(container)
            .setNeutralButton("刷新", null)
            .setNegativeButton("复制诊断", null)
            .setPositiveButton("关闭", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                refreshLogState()
                tv.text = snapshot()
                scroll.scrollTo(0, 0)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("支付提醒诊断", tv.text))
                Toast.makeText(this, "诊断信息已复制", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    /** 悬浮球开关：需悬浮窗权限；守护服务未运行时自动拉起。 */
    private fun toggleBall() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val enable = !prefs.getBoolean("ball_enabled", false)
        if (enable && !android.provider.Settings.canDrawOverlays(this)) {
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.fromParts("package", packageName, null)
                    )
                )
            }
            Toast.makeText(this, "请先允许「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            return
        }
        com.mudasir.smartledger.util.PurchaseGuardService.setBallEnabled(this, enable)
        refreshGuardStatus()
    }

    /** 大额消费提醒门槛输入。 */
    private fun editThreshold() {
        val prefs = getSharedPreferences("guard_prefs", MODE_PRIVATE)
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "金额（元），最低 0"
            setText(com.mudasir.smartledger.capture.GuardThreshold.read(prefs).stripTrailingZeros().toPlainString())
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("付款提醒金额门槛")
            .setMessage("付款页识别到明确实付金额且严格大于此值时提醒；支付后的大额提醒也使用此门槛。无法识别金额时不会提醒。")
            .setView(wrap)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty()
                val value = raw.toBigDecimalOrNull()
                if (!raw.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")) || value == null ||
                    value < java.math.BigDecimal.ZERO || value > java.math.BigDecimal("10000000")) {
                    input.error = "请输入 0 至 10000000 元，最多两位小数"
                    input.requestFocus()
                    return@setOnClickListener
                }
                prefs.edit().putString("big_spend_threshold_decimal", value.toPlainString()).apply()
                com.mudasir.smartledger.util.GuardDiagnosticLog.settingChanged("threshold", "updated")
                refreshGuardStatus()
                dialog.dismiss()
            }
        }
        dialog.show()
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
            val ok = runCatching { AutoBackupManager.backupNow(this@SettingsActivity) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                refreshBackupStatus()
                Toast.makeText(this@SettingsActivity, if (ok) "已生成不可变备份到 Download/SmartLedger" else "备份失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doRestore() {
        AlertDialog.Builder(this)
            .setTitle("导入 SmartLedger 备份")
            .setMessage("请选择 .json 备份文件。恢复会按交易指纹合并，不会用备份中的数据库 ID 覆盖现有记录。")
            .setPositiveButton("选择文件") { _, _ ->
                ledgerImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 微信/支付宝账单 CSV → 解析 → 确认 → 指纹去重入库 + 打标学习。 */
    private fun importBill(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                withContext(Dispatchers.Main) { Toast.makeText(this@SettingsActivity, "无法读取所选文件", Toast.LENGTH_SHORT).show() }
                return@launch
            }
            when (val parsed = BillImportParser.parse(bytes)) {
                is BillImportParser.Result.Failure -> withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, parsed.reason, Toast.LENGTH_LONG).show()
                }
                is BillImportParser.Result.Success -> {
                    val bills = parsed.bills
                    val expenseCount = bills.count { it.type == TransactionRecord.TYPE_EXPENSE }
                    val incomeCount = bills.size - expenseCount
                    val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                    val plan = BillImporter.plan(this@SettingsActivity, bills, parsed.sourceChannel)
                    val periodStr = "${sdf.format(java.util.Date(plan.periodStart))} ~ " +
                        sdf.format(java.util.Date(plan.periodEnd))
                    val sample = bills.take(3).joinToString("\n") { b ->
                        "· ${sdf.format(java.util.Date(b.timestamp))} ${b.merchant ?: b.note ?: ""} " +
                            "${if (b.type == TransactionRecord.TYPE_EXPENSE) "-" else "+"}¥${"%.2f".format(b.amount)}"
                    }
                    val dupSampleStr = if (plan.dupSamples.isNotEmpty())
                        "\n重复样例：\n" + plan.dupSamples.joinToString("\n") { "· $it" } else ""
                    val confirmedChoice = withContext(Dispatchers.Main) {
                        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                            var resumed = false
                            fun finish(v: Boolean?) {
                                if (!resumed) { resumed = true; cont.resume(v) }
                            }
                            AlertDialog.Builder(this@SettingsActivity)
                                .setTitle("${parsed.sourceChannel}账单 · 对账预检")
                                .setMessage(
                                    "解析到 ${bills.size} 笔收支（支出 $expenseCount · 收入 $incomeCount）\n" +
                                        "账单期间：$periodStr\n样例：\n$sample\n\n" +
                                        "与账本比对：全新 ${plan.fresh} 笔 · 可合并待确认 ${plan.pendingMatches} 笔 · " +
                                        "与已有记录重复 ${plan.exactDups} 笔$dupSampleStr\n\n" +
                                        "「去重导入」只导入全新记录；「全量导入」将所有解析记录逐条插入（含重复）。"
                                )
                                .setPositiveButton("去重导入") { _, _ -> finish(true) }
                                .setNeutralButton("全量导入") { _, _ -> finish(false) }
                                .setNegativeButton("取消") { _, _ -> finish(null) }
                                .setOnCancelListener { finish(null) }
                                .show()
                        }
                    }
                    when (confirmedChoice) {
                        null -> return@launch
                        else -> {
                            val skipDuplicates = confirmedChoice == true
                            val mode = if (skipDuplicates) "去重导入" else "全量导入"
                            val result = runCatching {
                                BillImporter.execute(this@SettingsActivity, bills, parsed.sourceChannel, skipDuplicates)
                            }
                            result.onFailure { e ->
                                // 协程内的任何异常都不允许静默消失
                                withContext(Dispatchers.Main) {
                                    AlertDialog.Builder(this@SettingsActivity)
                                        .setTitle("导入异常")
                                        .setMessage("导入过程发生未预期错误：\n${e.javaClass.simpleName}: ${e.message}")
                                        .setPositiveButton("知道了", null)
                                        .show()
                                }
                            }
                            result.getOrNull()?.let { report ->
                                // 导入后回查数据库：账单期间现有多少已确认记录（含导入来源多少笔）
                                val db = AppDatabase.getDatabase(this@SettingsActivity)
                                val inPeriod = db.transactionDao().getInRange(
                                    bills.minOf { it.timestamp } - 60_000,
                                    bills.maxOf { it.timestamp } + 60_000
                                )
                                val fromImport = inPeriod.count { it.source == TransactionRecord.SOURCE_IMPORT }
                                withContext(Dispatchers.Main) {
                                    refreshBackupStatus()
                                    val msg = buildString {
                                        append("$mode 完成：新增 ${report.inserted} · 合并待确认 ${report.mergedPending} · ")
                                        append("账本已有跳过 ${report.skippedExisting}")
                                        if (report.failed > 0) {
                                            append("\n\n⚠️ 写入失败 ${report.failed} 笔")
                                            report.firstError?.let { append("\n原因：$it") }
                                        }
                                        append("\n\n回查数据库：账单期间现有 ${inPeriod.size} 笔已确认记录")
                                        append("（其中导入来源 $fromImport 笔）")
                                        if (inPeriod.isEmpty()) {
                                            append("\n\n若为 0，请检查：统计页月份是否对应、或账本是否被「清理」过滤。")
                                        }
                                    }
                                    AlertDialog.Builder(this@SettingsActivity)
                                        .setTitle("导入结果")
                                        .setMessage(msg)
                                        .setPositiveButton("知道了", null)
                                        .show()
                                }
                                runCatching { AutoBackupManager.backup(this@SettingsActivity) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun exportPortableBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = AutoBackupManager.exportToUri(this@SettingsActivity, uri)
            withContext(Dispatchers.Main) {
                refreshBackupStatus()
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) "可迁移备份已导出" else "导出失败，请更换保存位置",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun restorePortableBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { AutoBackupManager.restoreFromUri(this@SettingsActivity, uri) }
            withContext(Dispatchers.Main) {
                result.onSuccess { report ->
                    refreshBackupStatus()
                    Toast.makeText(
                        this@SettingsActivity,
                        "恢复完成：新增 ${report.inserted}，更新 ${report.updated}，跳过 ${report.skipped}",
                        Toast.LENGTH_LONG
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this@SettingsActivity,
                        "恢复失败：${error.message ?: "文件无效或已损坏"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun confirmClear(type: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle("清理$label")
            .setMessage("将把所有已确认的${label}记录移出显示（软删除）。备份文件不会改动，历史仍可恢复。继续？")
            .setPositiveButton("清理") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.transactionDao().clearByType(type)
                    com.mudasir.smartledger.ml.HabitLearning.removeType(this@SettingsActivity, type)
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

        refreshNotifPermStatus()
        refreshBgLocStatus()
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

    /** 支付页增强检测（无障碍服务）状态刷新。 */
    private fun refreshAccCaptureStatus() {
        val tv = findViewById<TextView>(R.id.tvAccCaptureStatus)
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAccCapture)
        val enabled = PermissionHelper.isAccessibilityCaptureEnabled(this)
        tv.text = if (enabled) "已开启" else "未开启"
        val captureCount = listOf(PermissionHelper.isNotificationListenerEnabled(this),
            PermissionHelper.hasSmsPermission(this), enabled).count { it }
        findViewById<TextView>(R.id.tvCaptureOverview).text = "通知 · 短信 · 支付页检测（$captureCount/3 已开启）"
        tv.setTextColor(getColorCompat(if (enabled) R.color.color_income else R.color.color_expense))
        btn.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun showCaptureDiagnostics() {
        val diag = com.mudasir.smartledger.capture.CaptureDiagnostics
        val running = diag.enabled(this)
        val report = diag.report(this)
        AlertDialog.Builder(this).setTitle("收件箱采集诊断")
            .setMessage(report + if (!running) "\n\n开始记录会清空上次计数。" else "")
            .setPositiveButton(if (running) "停止记录" else "开始记录") { _, _ ->
                if (running) diag.stop(this) else diag.start(this)
                showCaptureDiagnostics()
            }
            .setNeutralButton("复制结果") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("收件箱采集诊断", report))
                Toast.makeText(this, "已复制诊断计数", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null).show()
    }

    private fun refreshAutoConfirmStatus() {
        val enabled = getSharedPreferences("capture_review_prefs", MODE_PRIVATE).getBoolean("auto_confirm_enabled", false)
        findViewById<TextView>(R.id.tvAutoConfirmStatus).text = if (enabled) "已开启 · 7 天前的交易按条件确认" else "已关闭 · 需手动核对"
    }

    private fun editAutoConfirm() {
        val prefs = getSharedPreferences("capture_review_prefs", MODE_PRIVATE)
        val enabled = prefs.getBoolean("auto_confirm_enabled", false)
        AlertDialog.Builder(this)
            .setTitle("自动确认候选交易")
            .setMessage("开启后，交易时间距今超过 7 天、金额和分类完整且建议分数达到门槛的候选交易会自动入账。新采集的旧交易可能很快被确认。自动确认不会作为用户反馈训练新学习器；系统建议仍可能有误。")
            .setPositiveButton(if (enabled) "关闭自动确认" else "开启自动确认") { _, _ ->
                prefs.edit().putBoolean("auto_confirm_enabled", !enabled).apply()
                refreshAutoConfirmStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Pro 授权状态刷新 + WebDAV 配置状态。 */
    private fun refreshProStatus() {
        val tv = findViewById<TextView>(R.id.tvProStatus)
        val btnActivate = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnProActivate)
        val btnDeactivate = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnProDeactivate)
        val info = if (com.mudasir.smartledger.util.ProManager.isPro(this))
            com.mudasir.smartledger.util.ProManager.verify(com.mudasir.smartledger.util.ProManager.storedLicense(this))
        else null
        if (info != null) {
            tv.text = if (info.name.isNotBlank()) "已激活 · ${info.name}" else "已激活 · ${info.email}"
            tv.setTextColor(getColorCompat(R.color.color_income))
            btnActivate.visibility = View.GONE
            btnDeactivate.visibility = View.VISIBLE
        } else {
            tv.text = "未激活"
            tv.setTextColor(getColorCompat(R.color.color_expense))
            btnActivate.visibility = View.VISIBLE
            btnActivate.text = "激活 Pro（输入授权码）"
            btnDeactivate.visibility = View.GONE
        }
        val cfg = com.mudasir.smartledger.util.WebDavSync.loadConfig(this)
        findViewById<TextView>(R.id.tvWebdavStatus).text = if (cfg != null) "已配置" else "未配置"
    }

    /** 激活 Pro：粘贴授权码离线验证。 */
    private fun activatePro() {
        val input = android.widget.EditText(this).apply {
            hint = "粘贴授权码（LICENSE=... 开头亦可）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@SettingsActivity).apply {
                text = "授权码获取与购买：yizhilaotian@gmail.com"
                textSize = 12f
                setTextColor(getColorCompat(R.color.text_secondary))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("激活 MoneyTracker Pro")
            .setView(wrap)
            .setPositiveButton("激活") { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val license = raw.substringAfter("LICENSE=", raw)
                val info = com.mudasir.smartledger.util.ProManager.activate(this, license)
                if (info != null) {
                    refreshProStatus()
                    Toast.makeText(this, "Pro 已激活 · 感谢支持！", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "授权码无效", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** WebDAV 服务器配置（服务器/账号/应用密码/加密口令）。 */
    private fun editWebdavConfig() {
        val prefs = getSharedPreferences("webdav_prefs", MODE_PRIVATE)
        val saved = com.mudasir.smartledger.util.WebDavSync.loadConfig(this)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        fun addField(label: String, hint: String, value: String, password: Boolean): android.widget.EditText {
            wrap.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(getColorCompat(R.color.text_secondary))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * resources.displayMetrics.density).toInt()
            })
            val et = android.widget.EditText(this).apply {
                this.hint = hint
                setText(value)
                inputType = if (password)
                    android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                else android.text.InputType.TYPE_CLASS_TEXT
            }
            wrap.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            return et
        }
        val etServer = addField("服务器地址", "如 https://dav.jianguoyun.com/dav/", saved?.serverUrl ?: "https://dav.jianguoyun.com/dav/", false)
        val etUser = addField("账号", "登录账号", saved?.user ?: "", false)
        val etPassword = addField("应用密码", "应用密码", saved?.password ?: "", true)
        val etPassphrase = addField("端到端加密口令", "忘记将无法恢复云端备份", "", true)

        AlertDialog.Builder(this)
            .setTitle("WebDAV 云同步配置")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                com.mudasir.smartledger.util.WebDavSync.saveConfig(
                    this,
                    com.mudasir.smartledger.util.WebDavSync.Config(
                        serverUrl = etServer.text?.toString()?.trim().orEmpty(),
                        user = etUser.text?.toString()?.trim().orEmpty(),
                        password = etPassword.text?.toString().orEmpty(),
                        passphrase = etPassphrase.text?.toString()?.trim().orEmpty()
                    )
                )
                refreshProStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun webdavUpload() {
        val cfg = com.mudasir.smartledger.util.WebDavSync.loadConfig(this)
        if (cfg == null) {
            Toast.makeText(this, "请先配置 WebDAV 服务器", Toast.LENGTH_SHORT).show()
            return
        }
        if (!com.mudasir.smartledger.util.ProManager.isPro(this)) {
            Toast.makeText(this, "云同步属于 Pro 功能", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            com.mudasir.smartledger.util.WebDavSync.upload(this@SettingsActivity, cfg)
                .onSuccess { name ->
                    refreshBackupStatus()
                    Toast.makeText(this@SettingsActivity, "已上传加密备份：$name", Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    Toast.makeText(this@SettingsActivity, "上传失败：${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun webdavRestore() {
        val cfg = com.mudasir.smartledger.util.WebDavSync.loadConfig(this)
        if (cfg == null) {
            Toast.makeText(this, "请先配置 WebDAV 服务器", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("从云端恢复")
            .setMessage("将下载云端最新加密备份并合并到本地账本（按指纹去重，不覆盖现有记录）。继续？")
            .setPositiveButton("恢复") { _, _ ->
                lifecycleScope.launch {
                    com.mudasir.smartledger.util.WebDavSync.restore(this@SettingsActivity, cfg)
                        .onSuccess { report ->
                            refreshBackupStatus()
                            Toast.makeText(
                                this@SettingsActivity,
                                "恢复完成：新增 ${report.inserted} · 更新 ${report.updated} · 跳过 ${report.skipped}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .onFailure {
                            Toast.makeText(this@SettingsActivity, "恢复失败：${it.message}", Toast.LENGTH_LONG).show()
                        }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun getColorCompat(resId: Int): Int = androidx.core.content.ContextCompat.getColor(this, resId)

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

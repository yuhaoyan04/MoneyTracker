package com.mudasir.smartledger.activity

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.mudasir.smartledger.BuildConfig
import com.mudasir.smartledger.R
import com.mudasir.smartledger.util.AiSettings
import com.mudasir.smartledger.util.BottomNavHelper
import com.mudasir.smartledger.util.PermissionHelper

class SettingsActivity : AppCompatActivity() {

    private lateinit var actvProvider: AutoCompleteTextView
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var etApiKey: TextInputEditText

    private val smsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }

        actvProvider = findViewById(R.id.actvProvider)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)

        val config = AiSettings.currentConfig(this)
        val presetNames = AiSettings.presets.map { it.name }
        actvProvider.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, presetNames))
        actvProvider.setText(config.providerName, false)
        etBaseUrl.setText(config.baseUrl)
        etModel.setText(config.model)
        etApiKey.setText(config.apiKey)

        actvProvider.setOnItemClickListener { parent, _, position, _ ->
            val name = parent.getItemAtPosition(position).toString()
            AiSettings.presets.find { it.name == name }?.let { p ->
                if (p.name != "自定义") {
                    etBaseUrl.setText(p.baseUrl)
                    if (etModel.text.isNullOrBlank() || etModel.text.toString() == config.model) {
                        etModel.setText(p.defaultModel)
                    }
                }
            }
        }

        findViewById<View>(R.id.btnSaveAi).setOnClickListener {
            AiSettings.save(
                this,
                providerName = actvProvider.text?.toString()?.trim().orEmpty().ifEmpty { presetNames[0] },
                baseUrl = etBaseUrl.text?.toString()?.trim().orEmpty(),
                model = etModel.text?.toString()?.trim().orEmpty(),
                apiKey = etApiKey.text?.toString()?.trim().orEmpty()
            )
            refreshAiStatus()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnNotif).setOnClickListener {
            PermissionHelper.openNotificationListenerSettings(this)
        }
        findViewById<View>(R.id.btnSms).setOnClickListener {
            smsLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }

        findViewById<TextView>(R.id.tvVersion).text = "v${BuildConfig.VERSION_NAME}"
        BottomNavHelper.setup(this, findViewById(R.id.bottomNav), R.id.nav_tab_settings)
        refreshAiStatus()
    }

    private fun refreshAiStatus() {
        val cfg = AiSettings.currentConfig(this)
        val status = findViewById<TextView>(R.id.tvAiStatus)
        if (cfg.isReady) {
            status.text = "已配置 · ${cfg.providerName} · ${cfg.model}"
            status.setTextColor(getColorCompat(R.color.color_income))
        } else {
            status.text = "未配置 · 请填写 API Key 与 Base URL、模型名"
            status.setTextColor(getColorCompat(R.color.color_expense))
        }
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

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshAiStatus()
    }
}

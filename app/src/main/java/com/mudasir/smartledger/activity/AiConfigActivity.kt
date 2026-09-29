package com.mudasir.smartledger.activity

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.mudasir.smartledger.R
import com.mudasir.smartledger.util.AiSettings

class AiConfigActivity : AppCompatActivity() {

    private lateinit var actvProvider: AutoCompleteTextView
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var etApiKey: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_config)

        findViewById<MaterialToolbar>(R.id.topAppBar).setNavigationOnClickListener { finish() }
        actvProvider = findViewById(R.id.actvProvider)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etModel = findViewById(R.id.etModel)
        etApiKey = findViewById(R.id.etApiKey)

        val config = AiSettings.currentConfig(this)
        val names = AiSettings.presets.map { it.name }
        actvProvider.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names))
        actvProvider.setText(config.providerName, false)
        etBaseUrl.setText(config.baseUrl)
        etModel.setText(config.model)
        // Key 不回显原文，避免明文；仅提示是否已存
        etApiKey.setText(if (config.apiKey.isNotBlank()) config.apiKey else "")

        actvProvider.setOnItemClickListener { parent, _, position, _ ->
            val name = parent.getItemAtPosition(position).toString()
            AiSettings.presets.find { it.name == name }?.let { p ->
                if (p.name != "自定义") {
                    etBaseUrl.setText(p.baseUrl)
                    etModel.setText(p.defaultModel)
                }
            }
        }

        findViewById<android.view.View>(R.id.btnSave).setOnClickListener {
            AiSettings.save(
                this,
                providerName = actvProvider.text?.toString()?.trim().orEmpty().ifEmpty { names[0] },
                baseUrl = etBaseUrl.text?.toString()?.trim().orEmpty(),
                model = etModel.text?.toString()?.trim().orEmpty(),
                apiKey = etApiKey.text?.toString()?.trim().orEmpty()
            )
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<android.view.View>(R.id.btnClear).setOnClickListener {
            AiSettings.clearKey(this)
            etApiKey.setText("")
            Toast.makeText(this, "已清除 Key", Toast.LENGTH_SHORT).show()
        }
    }
}

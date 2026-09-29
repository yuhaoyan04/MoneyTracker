package com.mudasir.smartledger.util

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * 应用内 LLM 配置存储（v2）。
 *
 * 设计要点：
 *  - 配置写入应用私有目录下的文件 ai_config.json（filesDir），不混入 SharedPreferences。
 *  - API Key 在文件内以 XOR+Base64 混淆存储，避免明文落盘；设置页不显示 Key 原文，
 *    仅展示「已配置/未配置」状态，编辑 Key 走独立配置页（密码式掩码，可切换显隐）。
 *  - 默认 DeepSeek，模型 deepseek-chat（DeepSeek 官方最快的 chat 模型；如后续 V4/Flash
 *    模型上线，用户可在配置页直接填入新 model id，无需改代码）。
 */
object AiSettings {

    private const val FILE_NAME = "ai_config.json"
    private const val K_PROVIDER = "provider"
    private const val K_BASE_URL = "base_url"
    private const val K_MODEL = "model"
    private const val K_KEY_ENC = "key_enc"

    private val obfuscateBytes = byteArrayOf(0x53, 0x4c, 0x65, 0x64, 0x67, 0x65, 0x72) // "SLedger"

    data class Provider(val name: String, val baseUrl: String, val defaultModel: String, val hint: String)

    val presets = listOf(
        Provider("DeepSeek", "https://api.deepseek.com/v1/", "deepseek-chat", "sk-... DeepSeek API Key"),
        Provider("GLM", "https://open.bigmodel.cn/api/paas/v4/", "glm-4-flash", "... 智谱 API Key"),
        Provider("Kimi", "https://api.moonshot.cn/v1/", "moonshot-v1-8k", "sk-... Moonshot API Key"),
        Provider("自定义", "https://api.example.com/v1/", "", "OpenAI 兼容接口")
    )

    val defaultProvider: Provider get() = presets[0]

    data class Config(
        val providerName: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String
    ) {
        val isReady: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
    }

    fun currentConfig(context: Context): Config {
        val json = readJson(context) ?: return Config(defaultProvider.name, defaultProvider.baseUrl, defaultProvider.defaultModel, "")
        val providerName = json.get(K_PROVIDER)?.asString ?: defaultProvider.name
        val preset = presets.find { it.name == providerName } ?: defaultProvider
        val baseUrl = json.get(K_BASE_URL)?.asString ?: preset.baseUrl
        val model = json.get(K_MODEL)?.asString?.takeIf { it.isNotBlank() } ?: preset.defaultModel
        val apiKey = obfuscateDecode(json.get(K_KEY_ENC)?.asString)
        return Config(providerName, baseUrl, model, apiKey)
    }

    fun save(context: Context, providerName: String, baseUrl: String, model: String, apiKey: String) {
        val json = JsonObject()
        json.addProperty(K_PROVIDER, providerName.ifBlank { defaultProvider.name })
        json.addProperty(K_BASE_URL, baseUrl)
        json.addProperty(K_MODEL, model)
        json.addProperty(K_KEY_ENC, obfuscateEncode(apiKey))
        writeJson(context, json)
    }

    fun clearKey(context: Context) {
        val cfg = currentConfig(context)
        save(context, cfg.providerName, cfg.baseUrl, cfg.model, "")
    }

    fun hasKey(context: Context): Boolean = currentConfig(context).isReady

    private fun file(context: Context) =
        java.io.File(context.applicationContext.filesDir, FILE_NAME)

    private fun readJson(context: Context): JsonObject? = try {
        val raw = file(context).readText()
        if (raw.isBlank()) null else Gson().fromJson(raw, JsonObject::class.java)
    } catch (e: Exception) { null }

    private fun writeJson(context: Context, json: JsonObject) {
        try {
            val f = file(context)
            f.parentFile?.mkdirs()
            f.writeText(Gson().toJson(json))
        } catch (_: Exception) { }
    }

    private fun obfuscateEncode(plain: String): String {
        if (plain.isEmpty()) return ""
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) out[i] = (bytes[i].toInt() xor obfuscateBytes[i % obfuscateBytes.size].toInt()).toByte()
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun obfuscateDecode(cipher: String?): String {
        if (cipher.isNullOrBlank()) return ""
        return try {
            val bytes = Base64.decode(cipher, Base64.NO_WRAP)
            val out = ByteArray(bytes.size)
            for (i in bytes.indices) out[i] = (bytes[i].toInt() xor obfuscateBytes[i % obfuscateBytes.size].toInt()).toByte()
            String(out, Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }
}

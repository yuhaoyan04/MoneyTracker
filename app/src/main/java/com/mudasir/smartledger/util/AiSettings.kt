package com.mudasir.smartledger.util

import android.content.Context
import android.util.Base64

/**
 * 应用内 LLM 配置存储。
 *
 * 用户可直接在 App 内输入 API Key，免去重新编译打包。
 * 采用轻量混淆（XOR + Base64）落 SharedPreferences，免额外依赖、
 * 适合个人自用机型；如需更强安全可后续替换为 EncryptedSharedPreferences。
 *
 * 支持多模型预设：DeepSeek（默认）、GLM、Kimi、自定义 OpenAI 兼容入口。
 */
object AiSettings {

    private const val PREFS = "smartledger_ai"
    private const val K_PROVIDER = "provider"
    private const val K_BASE_URL = "base_url"
    private const val K_MODEL = "model"
    private const val K_KEY = "api_key"

    private val obfuscateBytes = byteArrayOf(0x53, 0x4c, 0x65, 0x64, 0x67, 0x65, 0x72) // "SLedger"

    /** 模型预设。 */
    data class Provider(
        val name: String,
        val baseUrl: String,
        val defaultModel: String,
        val hint: String
    )

    val presets = listOf(
        Provider("DeepSeek", "https://api.deepseek.com/v1/", "deepseek-chat", "sk-... DeepSeek API Key"),
        Provider("GLM", "https://open.bigmodel.cn/api/paas/v4/", "glm-4-flash", "... 智谱 API Key"),
        Provider("Kimi", "https://api.moonshot.cn/v1/", "moonshot-v1-8k", "sk-... Moonshot API Key"),
        Provider("自定义", "https://api.example.com/v1/", "", "OpenAI 兼容接口")
    )

    data class Config(
        val providerName: String,
        val baseUrl: String,
        val model: String,
        val apiKey: String
    ) {
        val isReady: Boolean get() = apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()
    }

    fun currentConfig(context: Context): Config {
        val p = prefs(context)
        val providerName = p.getString(K_PROVIDER, presets[0].name) ?: presets[0].name
        val baseUrl = p.getString(K_BASE_URL, presets[0].baseUrl) ?: presets[0].baseUrl
        val model = p.getString(K_MODEL, presets[0].defaultModel).orEmpty()
        val apiKey = obfuscateDecode(p.getString(K_KEY, null))
        return Config(providerName, baseUrl, model.ifBlank { presets.find { it.name == providerName }?.defaultModel ?: model }, apiKey)
    }

    fun save(context: Context, providerName: String, baseUrl: String, model: String, apiKey: String) {
        prefs(context).edit()
            .putString(K_PROVIDER, providerName)
            .putString(K_BASE_URL, baseUrl)
            .putString(K_MODEL, model)
            .putString(K_KEY, obfuscateEncode(apiKey))
            .apply()
    }

    fun hasKey(context: Context): Boolean = currentConfig(context).isReady

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun byteArrayOf(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

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

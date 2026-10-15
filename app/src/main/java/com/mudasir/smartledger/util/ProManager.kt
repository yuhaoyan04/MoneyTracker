package com.mudasir.smartledger.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Pro 授权管理（离线 ECDSA 签名验证，无需联网）。
 *
 * 授权码格式：Base64(payloadJSON).Base64(ECDSA-SHA256签名)
 * payload：{"e":邮箱,"n":名称,"i":签发时间戳}
 * 验证失败的授权码一律拒绝；激活后本地持久化，每次启动静默复验。
 *
 * 购买/获取授权：yizhilaotian@gmail.com
 */
object ProManager {

    /** EC P-256 公钥（与开发者的签发私钥配对；私钥绝不入仓库）。 */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAENwaE7NSXFUd7ZvINZjg0syfrKoBpNfCOPdYfES2a2sBpJu8Q0DtC70UpNLNino0FrrNH0Nl65vD9KMpG9xmCkA=="

    data class ProInfo(val email: String, val name: String, val issuedAt: Long)

    private const val PREFS = "pro_prefs"
    private const val KEY_LICENSE = "license"

    /** 验证授权码（纯函数，可测试）。返回 ProInfo 或 null（无效）。 */
    fun verify(license: String): ProInfo? = runCatching {
        val parts = license.trim().split(".")
        if (parts.size != 2) return@runCatching null
        val payload = Base64.decode(parts[0], Base64.NO_WRAP or Base64.URL_SAFE)
        val sig = Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE)

        val kf = KeyFactory.getInstance("EC")
        val key = kf.generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.NO_WRAP)))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(payload)
        if (!verifier.verify(sig)) return@runCatching null

        val obj = JsonParser.parseString(String(payload, Charsets.UTF_8)).asJsonObject
        ProInfo(
            email = obj.get("e")?.asString.orEmpty(),
            name = obj.get("n")?.asString.orEmpty(),
            issuedAt = obj.get("i")?.asLong ?: 0L
        ).takeIf { it.email.isNotBlank() }
    }.getOrNull()

    fun activate(context: Context, license: String): ProInfo? {
        val info = verify(license) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LICENSE, license.trim()).apply()
        return info
    }

    fun deactivate(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LICENSE).apply()
    }

    fun storedLicense(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LICENSE, "").orEmpty()

    /** 当前是否已激活 Pro（每次调用都重新验签，防止本地篡改）。 */
    fun isPro(context: Context): Boolean = verify(storedLicense(context)) != null

    fun proEmail(context: Context): String = verify(storedLicense(context))?.email.orEmpty()
}

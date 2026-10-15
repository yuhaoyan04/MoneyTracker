package com.mudasir.smartledger.util

import java.net.HttpURLConnection
import java.net.URL

/** 极简 WebDAV 客户端（Basic 认证，PUT/GET 足够备份同步）。 */
object WebDavClient {

    data class Config(val serverUrl: String, val user: String, val password: String)

    fun normalizeServer(url: String): String = url.trim().trimEnd('/')

    fun latestUrl(cfg: Config): String =
        normalizeServer(cfg.serverUrl) + "/" + "MoneyTracker" + "/" + "smartledger-latest.enc"

    /** 上传字节（覆盖写）。返回 true=成功（2xx）。 */
    fun put(cfg: Config, fileName: String, bytes: ByteArray): Boolean {
        val url = normalizeServer(cfg.serverUrl) + "/MoneyTracker/" + fileName
        val conn = open(url, cfg).apply {
            requestMethod = "PUT"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            connectTimeout = 15000
            readTimeout = 60000
        }
        return runCatching {
            conn.outputStream.use { it.write(bytes) }
            conn.responseCode in 200..299
        }.getOrElse { false }.also { conn.disconnect() }
    }

    /** 下载文件字节。返回 null=不存在或失败。 */
    fun get(cfg: Config, fileName: String): ByteArray? {
        val url = normalizeServer(cfg.serverUrl) + "/MoneyTracker/" + fileName
        val conn = open(url, cfg).apply {
            connectTimeout = 15000
            readTimeout = 60000
        }
        return runCatching {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.use { it.readBytes() }
        }.getOrNull().also { conn.disconnect() }
    }

    /** 连接测试：对服务器根 GET。返回 true=可达且认证通过。 */
    fun test(cfg: Config): Boolean {
        val conn = open(normalizeServer(cfg.serverUrl), cfg).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
        }
        return runCatching { conn.responseCode in 200..399 }.getOrElse { false }.also { conn.disconnect() }
    }

    private fun open(url: String, cfg: WebDavClient.Config): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        val auth = android.util.Base64.encodeToString(
            (cfg.user + ":" + cfg.password).toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        conn.setRequestProperty("Authorization", "Basic $auth")
        return conn
    }
}

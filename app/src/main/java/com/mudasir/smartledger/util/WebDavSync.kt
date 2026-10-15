package com.mudasir.smartledger.util

import android.content.Context
import com.mudasir.smartledger.data.LedgerRestoreReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 加密云同步（Pro 功能）：
 * - 任意 WebDAV 服务（坚果云/群晖/自建），Basic 认证
 * - 备份信封先经 CryptoBox 端到端加密（口令派生 AES-256-GCM），服务端只见密文
 * - 覆盖写单一 latest 文件，简单可靠；恢复走与本地备份相同的校验+合并管线
 */
object WebDavSync {

    private const val FILE_NAME = "smartledger-latest.enc"

    data class Config(val serverUrl: String, val user: String, val password: String, val passphrase: String)

    fun loadConfig(context: Context): Config? {
        val p = context.getSharedPreferences("webdav_prefs", Context.MODE_PRIVATE)
        val url = p.getString("server", "")?.trim().orEmpty()
        val user = p.getString("user", "")?.trim().orEmpty()
        val password = p.getString("password", "").orEmpty()
        val passphrase = p.getString("passphrase", "").orEmpty()
        if (url.isBlank() || user.isBlank() || password.isBlank() || passphrase.isBlank()) return null
        return Config(WebDavClient.normalizeServer(url), user, password, passphrase)
    }

    fun saveConfig(context: Context, cfg: Config) {
        context.getSharedPreferences("webdav_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("server", cfg.serverUrl)
            .putString("user", cfg.user)
            .putString("password", cfg.password)
            .putString("passphrase", cfg.passphrase)
            .apply()
    }

    /** 上传加密备份。返回 Result（成功携带文件名）。 */
    suspend fun upload(context: Context, cfg: Config): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = AutoBackupManager.envelopeJsonForSync(context)
                ?: error("本地账本读取失败")
            val blob = CryptoBox.encrypt(json.toByteArray(Charsets.UTF_8), cfg.passphrase)
            val name = "backup-" + java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.US
            ).format(java.util.Date()) + ".enc"
            val clientCfg = WebDavClient.Config(cfg.serverUrl, cfg.user, cfg.password)
            if (!WebDavClient.put(clientCfg, name, blob)) error("上传失败（检查服务器/账号/网络）")
            // 覆盖写 latest，恢复端只取最新
            if (!WebDavClient.put(clientCfg, FILE_NAME, blob)) error("上传失败（latest 写入失败）")
            name
        }
    }

    /** 从云端恢复最新加密备份。返回 Result（成功携带恢复报告）。 */
    suspend fun restore(context: Context, cfg: Config): Result<LedgerRestoreReport> = withContext(Dispatchers.IO) {
        runCatching {
            val clientCfg = WebDavClient.Config(cfg.serverUrl, cfg.user, cfg.password)
            val blob = WebDavClient.get(clientCfg, FILE_NAME)
                ?: error("云端未找到备份文件（$FILE_NAME）")
            val json = runCatching {
                String(CryptoBox.decrypt(blob, cfg.passphrase), Charsets.UTF_8)
            }.getOrElse { error("解密失败：加密口令不正确或文件已损坏") }
            AutoBackupManager.restoreFromEnvelopeJson(context, json)
                ?: error("备份内容无效")
        }
    }
}

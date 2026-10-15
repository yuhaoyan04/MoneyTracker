package com.mudasir.smartledger.util

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * 端到端加密盒：AES-256-GCM，密钥由口令 PBKDF2 派生。
 * 文件格式：MTENC1 | salt(16) | iv(12) | ciphertext
 * 服务端只见密文——云同步也不破坏「数据只有你能读」的承诺。
 */
object CryptoBox {

    private val MAGIC = "MTENC1".toByteArray(Charsets.US_ASCII)

    fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain)
        return MAGIC + salt + iv + ct
    }

    fun decrypt(blob: ByteArray, passphrase: String): ByteArray {
        val magicLen = MAGIC.size
        require(blob.size > magicLen + 16 + 12) { "加密文件损坏" }
        require(blob.copyOfRange(0, magicLen).contentEquals(MAGIC)) { "不是本应用加密的文件" }
        val salt = blob.copyOfRange(magicLen, magicLen + 16)
        val iv = blob.copyOfRange(magicLen + 16, magicLen + 28)
        val ct = blob.copyOfRange(magicLen + 28, blob.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    /** PBKDF2-HMAC-SHA256，120k 迭代（OWASP 推荐）。 */
    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, 120_000, 256)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }
}

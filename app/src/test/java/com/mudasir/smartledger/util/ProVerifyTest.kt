package com.mudasir.smartledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Pro 授权签名/验证闭环测试（与 ProManager 相同的验证路径，纯 JVM）。
 */
class ProManagerVerifyTest {

    @Test
    fun `sign and verify roundtrip`() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val pub = Base64.getEncoder().encodeToString(kp.public.encoded)

        val payload = """{"e":"user@test.com","n":"测试","i":123}"""
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(payloadBytes)
        val license = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes) + "." +
            Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign())

        // 与 ProManager.verify 相同的验证路径
        val parts = license.trim().split(".")
        assertEquals(2, parts.size)
        val decPayload = Base64.getUrlDecoder().decode(parts[0])
        val decSig = Base64.getUrlDecoder().decode(parts[1])
        val kf = KeyFactory.getInstance("EC")
        val key = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pub)))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(decPayload)
        assertTrue(verifier.verify(decSig))
        assertTrue(String(decPayload).contains("user@test.com"))
    }

    @Test
    fun `tampered payload fails verification`() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val payload = """{"e":"a@b.c","i":1}"""
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(kp.private)
        sig.update(payload.toByteArray())
        // 篡改 payload 但保留原签名 → 验签必须失败
        val license = Base64.getUrlEncoder().withoutPadding().encodeToString(
            payload.replace("1", "2").toByteArray()
        ) + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign())

        val parts = license.split(".")
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(kp.public)
        verifier.update(Base64.getUrlDecoder().decode(parts[0]))
        assertFalse(verifier.verify(Base64.getUrlDecoder().decode(parts[1])))
    }
}

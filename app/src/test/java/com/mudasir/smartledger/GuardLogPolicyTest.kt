package com.mudasir.smartledger

import com.mudasir.smartledger.util.GuardLogPolicy
import org.junit.Assert.*
import org.junit.Test

class GuardLogPolicyTest {
    @org.junit.Test fun `price crop diagnostics contain format counts only`() {
        val stats = com.mudasir.smartledger.util.GuardLogPolicy.jdPriceCropStats("商户 ¥ 497．06 尾号 1234", true)
        org.junit.Assert.assertEquals("true", stats["currency"])
        org.junit.Assert.assertEquals("true", stats["decimal"])
        val rendered = stats.toString()
        org.junit.Assert.assertFalse(rendered.contains("497"))
        org.junit.Assert.assertFalse(rendered.contains("1234"))
        org.junit.Assert.assertFalse(rendered.contains("商户"))
        val lines = listOf(com.mudasir.smartledger.capture.VisualText("¥497.06 商户", 20, 30, 80, 50))
        val geometry = GuardLogPolicy.jdPriceLineStats(lines, 100, 100)
        assertEquals("20:30:60:20:5:1:1", geometry)
        assertFalse(geometry.contains("497"))
        assertFalse(geometry.contains("商户"))
    }
    @Test fun `unknown message and arbitrary app names are not retained`() {
        assertEquals("OTHER", GuardLogPolicy.status("secret merchant paid 123456"))
        assertEquals("other", GuardLogPolicy.safePackage("com.private.chat", "com.mudasir.smartledger"))
        assertEquals("com.tencent.mm", GuardLogPolicy.safePackage("com.tencent.mm", "com.mudasir.smartledger"))
        assertEquals("self", GuardLogPolicy.safePackage("com.mudasir.smartledger", "com.mudasir.smartledger"))
        assertEquals("", GuardLogPolicy.safeClass("card123456"))
        assertEquals("", GuardLogPolicy.safeClass("Secret merchant ¥123"))
    }

    @Test fun `only fixed counters and booleans are extracted from diagnostics`() {
        val result = GuardLogPolicy.evidence("节点=12, 操作=1, 裸金额=2, 订单列表=false, 秘密=¥9999")
        assertEquals(mapOf("nodes" to "12", "actions" to "1", "standalone_numbers" to "2", "order_list" to "false"), result)
        assertFalse(result.toString().contains("9999"))
        assertEquals("NO_AMOUNT", GuardLogPolicy.status("付款页候选：未识别到明确实付金额；秘密=¥9999"))
        assertTrue(GuardLogPolicy.evidence("节点=12345678, 操作=42secret")["nodes"] == null)
    }

    @Test fun `click classification never returns the supplied label`() {
        assertEquals("PAY_ACTION", GuardLogPolicy.clickRole(listOf("确认支付")))
        assertEquals("PAY_ACTION", GuardLogPolicy.clickRole(listOf("继续付款")))
        assertEquals("OTHER", GuardLogPolicy.clickRole(listOf("确认支付 ¥9999", "商户")))
        assertEquals("OTHER", GuardLogPolicy.clickRole(listOf("123456")))
    }
}

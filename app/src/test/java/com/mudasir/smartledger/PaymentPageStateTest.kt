package com.mudasir.smartledger

import com.mudasir.smartledger.capture.PaymentPageState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPageStateTest {
    @Test fun `only a clear successful result counts as payment`() {
        assertFalse(PaymentPageState.isSuccessful("确认付款 付款金额 ¥88.00"))
        assertFalse(PaymentPageState.isSuccessful("付款失败 ¥88.00"))
        assertFalse(PaymentPageState.isSuccessful("付款成功 已取消 ¥88.00"))
        assertTrue(PaymentPageState.isSuccessful("付款成功 实付金额 ¥88.00"))
    }
}

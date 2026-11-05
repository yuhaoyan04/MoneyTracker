package com.mudasir.smartledger

import com.mudasir.smartledger.capture.BankSmsGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankSmsGateTest {
    @Test fun `bank movement requires source account action and money`() {
        assertTrue(BankSmsGate.shouldCapture("95588", "【工商银行】您尾号1234账户消费人民币20.00元，余额900.00元。"))
        assertTrue(BankSmsGate.shouldCapture("106123456789", "【中国银行】您的尾号 1234账户转入100元。"))
        assertFalse(BankSmsGate.shouldCapture("13800138000", "【工商银行】您尾号1234账户消费20元。"))
        assertFalse(BankSmsGate.shouldCapture("106123456789", "您的尾号1234账户消费20元。"))
        assertFalse(BankSmsGate.shouldCapture("95588", "【工商银行】信用卡消费最高可领20元优惠券"))
        assertFalse(BankSmsGate.shouldCapture("95588", "【工商银行】您尾号1234支付失败20元。"))
        assertFalse(BankSmsGate.shouldCapture("95588", "【工商银行】尾号1234验证码123456，消费20元。"))
    }
}

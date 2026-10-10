package com.mudasir.smartledger.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialNotificationPolicyTest {
    @Test fun acceptsHuaweiWalletAndMajorBankSources() {
        assertTrue(FinancialNotificationPolicy.isFinancialSource(
            "com.huawei.wallet", "华为钱包"
        ))
        assertTrue(FinancialNotificationPolicy.isFinancialSource(
            "com.example.client", "招商银行"
        ))
    }

    @Test fun rejectsUnrelatedNotificationSource() {
        assertFalse(FinancialNotificationPolicy.isFinancialSource(
            "com.example.news", "今日新闻"
        ))
    }

    @Test fun recognizesCommonBankAmountFormats() {
        assertTrue(FinancialNotificationPolicy.hasTransactionAmount("尾号1234消费人民币88.60"))
        assertTrue(FinancialNotificationPolicy.hasTransactionAmount("账户支出 20.5 CNY"))
        assertTrue(FinancialNotificationPolicy.hasTransactionAmount("快捷支付：36.00"))
        assertFalse(FinancialNotificationPolicy.hasTransactionAmount("验证码 123456"))
    }
}

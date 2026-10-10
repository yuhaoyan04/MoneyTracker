package com.mudasir.smartledger.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPageParserTest {
    @Test fun parsesWechatResultWithSplitCurrencyText() {
        val result = PaymentPageParser.parse("微信支付 付款成功 ¥ 12.80 完成")!!
        assertEquals(12.80, result.amount, 0.001)
        assertFalse(result.isRefund)
    }

    @Test fun parsesAlipayAmountEndingInYuan() {
        val result = PaymentPageParser.parse("支付宝 支付成功 36.5元 查看账单")!!
        assertEquals(36.5, result.amount, 0.001)
    }

    @Test fun recognizesRefundAsIncomeSignal() {
        val result = PaymentPageParser.parse("退款成功 ￥9.90 返回首页")!!
        assertTrue(result.isRefund)
    }

    @Test fun rejectsHistoryLikeWeakTextWithoutResultAction() {
        assertNull(PaymentPageParser.parse("账单 交易成功 ￥20.00 商户详情"))
    }

    @Test fun rejectsUnrelatedAmount() {
        assertNull(PaymentPageParser.parse("余额 ￥100.00 今日优惠活动"))
    }
}

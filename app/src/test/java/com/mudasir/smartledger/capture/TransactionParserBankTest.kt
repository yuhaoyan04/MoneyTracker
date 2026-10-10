package com.mudasir.smartledger.capture

import com.mudasir.smartledger.data.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TransactionParserBankTest {
    @Test fun parsesRmbPrefixBankNotification() {
        val parsed = TransactionParser.parse(
            "招商银行 | 您尾号1234信用卡消费人民币88.60",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION,
            "cmb.pb"
        )
        assertNotNull(parsed)
        assertEquals(88.60, parsed!!.amount, 0.001)
        assertEquals("银行卡", parsed.channelName)
    }

    @Test fun parsesCnySuffixBankNotification() {
        val parsed = TransactionParser.parse(
            "建设银行 | 账户支出 20.50 CNY",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(parsed)
        assertEquals(20.50, parsed!!.amount, 0.001)
        assertEquals("银行卡", parsed.channelName)
    }

    @Test fun recognizesHuaweiWalletChannel() {
        assertEquals("华为支付", TransactionParser.channelForPackage("com.huawei.wallet"))
    }
}

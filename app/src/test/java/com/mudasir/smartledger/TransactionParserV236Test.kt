package com.mudasir.smartledger

import com.mudasir.smartledger.capture.TransactionParser
import com.mudasir.smartledger.data.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.36.0 证据测试：商户提取（自进化根基）+ 收支方向判定。
 */
class TransactionParserV236Test {

    // ===== 商户提取（Bug4：商户记忆层依赖商户名，此前几乎永远为 null） =====

    @Test
    fun `你在X支付格式提取商户`() {
        val r = TransactionParser.parse(
            "微信支付 | 你在瑞幸咖啡支付 ¥25.00",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals("瑞幸咖啡", r!!.merchant)
    }

    @Test
    fun `已转账给X格式提取商户`() {
        val r = TransactionParser.parse(
            "微信支付 | 已转账给张三 ¥200.00",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals("张三", r!!.merchant)
    }

    @Test
    fun `向X转账格式提取商户`() {
        val r = TransactionParser.parse(
            "微信支付 | 向李四转账 ¥300.00",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals("李四", r!!.merchant)
    }

    @Test
    fun `收款方格式提取商户`() {
        val r = TransactionParser.parse(
            "支付宝 | 收款方：肯德基 金额¥45.00",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals("肯德基", r!!.merchant)
    }

    // ===== 收支方向（Bug1：广告金额误判） =====

    @Test
    fun `已支付判定为支出`() {
        val r = TransactionParser.parse(
            "微信支付 | 已支付 ¥25.00",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals(TransactionRecord.TYPE_EXPENSE, r!!.type)
    }

    @Test
    fun `收款成功判定为收入`() {
        val r = TransactionParser.parse(
            "微信支付 | 收款成功 ¥100.00",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals(TransactionRecord.TYPE_INCOME, r!!.type)
    }

    @Test
    fun `退款判定为收入`() {
        val r = TransactionParser.parse(
            "淘宝 | 退款成功 ¥59.00",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals(TransactionRecord.TYPE_INCOME, r!!.type)
    }

    // ===== 金额提取 =====

    @Test
    fun `千分位金额提取`() {
        val r = TransactionParser.parse(
            "微信支付凭证 | 付款金额 ¥1,234.56",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals(1234.56, r!!.amount, 0.001)
    }

    @Test
    fun `元后缀金额提取`() {
        val r = TransactionParser.parse(
            "银行通知 | 消费人民币156.00元",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNotNull(r)
        assertEquals(156.00, r!!.amount, 0.001)
    }

    @Test
    fun `无金额文本返回null`() {
        val r = TransactionParser.parse(
            "微信 | 朋友发来一条消息",
            TransactionRecord.SOURCE_CAPTURE_NOTIFICATION
        )
        assertNull(r)
    }
}

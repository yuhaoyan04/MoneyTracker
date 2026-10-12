package com.mudasir.smartledger.util

import com.mudasir.smartledger.data.TransactionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定微信/支付宝账单导出格式——解析器必须适配：
 * - 顶部十几行说明文字（其中说明行也含「交易时间：」字样）
 * - 逗号或 Tab 分隔（从表格软件复制/另存会变成 Tab）
 * - 金额带 ¥ 前缀、备注里的「/」占位
 */
class BillImportParserTest {

    @Test
    fun `wechat tab csv with metadata preamble parses correctly`() {
        val header = listOf(
            "交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)",
            "支付方式", "当前状态", "交易单号", "商户单号", "备注"
        ).joinToString("\t")
        val row = listOf(
            "2026-10-11 23:59:23", "高德打车-退款", "高德打车", "高德打车", "收入",
            "¥17.39", "零钱", "已全额退款", "50100908722026101183377088819", "", "/"
        ).joinToString("\t")
        val text = listOf(
            "微信支付账单明细",
            "微信支付账单明细,交易时间：2026-10-01 00:00:00 ~ 2026-10-11 23:59:23",
            "导出类型：全部",
            "共 1 笔记录",
            "------微信支付账单明细列表------",
            header,
            row
        ).joinToString("\n")

        val result = BillImportParser.parse(text.toByteArray())

        assertTrue("应为成功: $result", result is BillImportParser.Result.Success)
        result as BillImportParser.Result.Success
        assertEquals("微信支付", result.sourceChannel)
        assertEquals(1, result.bills.size)
        val bill = result.bills[0]
        assertEquals(TransactionRecord.TYPE_INCOME, bill.type)
        assertEquals(17.39, bill.amount, 0.001)
        assertEquals("高德打车", bill.merchant)
        assertEquals("零钱", bill.paymentMethod)
    }

    @Test
    fun `wechat comma csv expense row parses correctly`() {
        val header = "交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注"
        val row = "2026-10-11 12:30:00,商户消费,肯德基,肯德基宅急送,支出,¥39.90,零钱,支付成功,1001,,/"
        val text = listOf("微信支付账单明细", header, row).joinToString("\n")

        val result = BillImportParser.parse(text.toByteArray())

        assertTrue(result is BillImportParser.Result.Success)
        result as BillImportParser.Result.Success
        assertEquals(1, result.bills.size)
        val bill = result.bills[0]
        assertEquals(TransactionRecord.TYPE_EXPENSE, bill.type)
        assertEquals(39.90, bill.amount, 0.001)
        assertEquals("肯德基", bill.merchant)
    }

    @Test
    fun `alipay csv parses correctly`() {
        val header = "交易号,商家订单号,交易创建时间,付款时间,最近修改时间,交易来源地,类型,交易对方,商品名称,金额（元）,收/支,交易状态,交易分类,备注,"
        val row = "202610110001,,2026-10-11 12:00:00,2026-10-11 12:00:01,2026-10-11 12:00:02,支付宝,即时到账交易,某某商店,商品A,25.00,支出,交易成功,餐饮,"
        val text = listOf(header, row).joinToString("\n")

        val result = BillImportParser.parse(text.toByteArray())

        assertTrue("应为成功: $result", result is BillImportParser.Result.Success)
        result as BillImportParser.Result.Success
        assertEquals("支付宝", result.sourceChannel)
        assertEquals(1, result.bills.size)
        val bill = result.bills[0]
        assertEquals(TransactionRecord.TYPE_EXPENSE, bill.type)
        assertEquals(25.00, bill.amount, 0.001)
        assertEquals("某某商店", bill.merchant)
    }

    @Test
    fun `non-bill file fails with readable reason`() {
        val result = BillImportParser.parse("随便一段文字,没有账单结构".toByteArray())
        assertTrue(result is BillImportParser.Result.Failure)
    }
}

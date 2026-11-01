package com.mudasir.smartledger

import com.mudasir.smartledger.capture.PageAmountExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.37.0 证据测试：页面金额提取（支付宝/银行卡页面抓取主通道）。
 * 重点：千分位逗号 —— 旧正则 [0-9]+ 对「¥1,234.56」只解析出 1.00。
 */
class PageAmountExtractorTest {

    // ===== 符号前缀 =====

    @Test
    fun `普通金额`() {
        assertEquals(25.0, PageAmountExtractor.extract("付款金额 ¥25.00")!!, 0.001)
    }

    @Test
    fun `千分位金额`() {
        assertEquals(1234.56, PageAmountExtractor.extract("¥1,234.56")!!, 0.001)
    }

    @Test
    fun `全角符号千分位`() {
        assertEquals(1000.0, PageAmountExtractor.extract("￥1,000.00")!!, 0.001)
    }

    // ===== 元后缀 =====

    @Test
    fun `元后缀普通`() {
        assertEquals(156.0, PageAmountExtractor.extract("支付金额 156.00元")!!, 0.001)
    }

    @Test
    fun `元后缀千分位`() {
        assertEquals(12345.67, PageAmountExtractor.extract("交易金额 12,345.67元")!!, 0.001)
    }

    // ===== 裸小数兜底（H5 收银台 ¥ 缺失） =====

    @Test
    fun `裸小数取最大`() {
        // 页面同时有优惠小字（3.00）与总额（200.00）→ 取最大
        assertEquals(200.0, PageAmountExtractor.extract("已优惠 3.00  200.00 确认付款")!!, 0.001)
    }

    @Test
    fun `分期页不使用裸小数兜底`() {
        // 分期页有多个等额小数，取最大会错（总额 250，每期 83.33）
        val text = "分期付款 每月 83.33 × 3 期 总金额 250.00"
        assertNull(PageAmountExtractor.extract(text))
    }

    // ===== 优先级 =====

    @Test
    fun `符号金额优先于裸小数`() {
        // 有 ¥ 金额时不用裸兜底（¥88.00 是实付，999.00 是商品原价划线价）
        assertEquals(88.0, PageAmountExtractor.extract("¥88.00 原价 999.00")!!, 0.001)
    }

    @Test
    fun `无金额返回null`() {
        assertNull(PageAmountExtractor.extract("确认付款 请输入密码"))
    }
}

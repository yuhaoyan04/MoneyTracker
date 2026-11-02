package com.mudasir.smartledger

import com.mudasir.smartledger.capture.CaptureWhitelist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.37.0 证据测试：采集白名单收紧（广告噪音源头切断）。
 */
class CaptureWhitelistTest {
    @Test
    fun `page sources require an exact known package`() {
        assertTrue(CaptureWhitelist.isPageWatched("com.icbc"))
        assertFalse(CaptureWhitelist.isPageWatched("com.taobao.taobao"))
        assertFalse(CaptureWhitelist.isPageWatched("com.fake.bank.app"))
    }

    // ===== 白名单内 —— 必须采集 =====

    @Test
    fun `微信在白名单`() {
        assertTrue(CaptureWhitelist.isWatched("com.tencent.mm"))
    }

    @Test
    fun `支付宝及其变体在白名单`() {
        assertTrue(CaptureWhitelist.isWatched("com.eg.android.AlipayGphone"))
        assertTrue(CaptureWhitelist.isWatched("com.eg.android.AlipayGphone.rc"))
        assertTrue(CaptureWhitelist.isWatched("com.eg.android.AlipayGphone.lite"))
    }

    @Test
    fun `云闪付在白名单`() {
        assertTrue(CaptureWhitelist.isWatched("com.unionpay"))
    }

    // ===== 广告来源 App —— 全部排除 =====

    @Test
    fun `淘宝广告源排除`() {
        assertFalse(CaptureWhitelist.isWatched("com.taobao.taobao"))
        assertFalse(CaptureWhitelist.isWatched("com.taobao.idlefish"))
    }

    @Test
    fun `京东广告源排除`() {
        assertFalse(CaptureWhitelist.isWatched("com.jingdong.app.mall"))
        assertFalse(CaptureWhitelist.isWatched("com.jd.jrapp"))
    }

    @Test
    fun `美团广告源排除`() {
        assertFalse(CaptureWhitelist.isWatched("com.sankuai.meituan"))
        assertFalse(CaptureWhitelist.isWatched("com.sankuai.meituan.takeoutnew"))
    }

    @Test
    fun `拼多多与滴滴排除`() {
        assertFalse(CaptureWhitelist.isWatched("com.xunmeng.pinduoduo"))
        assertFalse(CaptureWhitelist.isWatched("com.sdu.didi.psnger"))
    }

    // ===== 渠道归类（页面抓取用） =====

    @Test
    fun `微信渠道归类`() {
        assertEquals("微信支付", CaptureWhitelist.channelOf("com.tencent.mm"))
    }

    @Test
    fun `支付宝渠道归类`() {
        assertEquals("支付宝", CaptureWhitelist.channelOf("com.eg.android.AlipayGphone"))
    }

    @Test
    fun `银行App归类为银行卡`() {
        assertEquals("银行卡", CaptureWhitelist.channelOf("com.icbc"))
        assertEquals("银行卡", CaptureWhitelist.channelOf("cmb.pb"))
        assertEquals("银行卡", CaptureWhitelist.channelOf("com.chinamworld.main"))
    }

    @Test
    fun `未知银行App启发式归类`() {
        assertEquals("银行卡", CaptureWhitelist.channelOf("com.someunknown.bank.app"))
    }

    @Test
    fun `无关App归类其他`() {
        assertEquals("其他", CaptureWhitelist.channelOf("com.example.random"))
    }
}

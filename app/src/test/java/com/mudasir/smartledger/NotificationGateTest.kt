package com.mudasir.smartledger

import com.mudasir.smartledger.capture.NotificationGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.36.0 证据测试：广告金额误判收支的根治验证。
 * 用例全部来自真实世界的通知文案形态。
 */
class NotificationGateTest {

    // ===== 真实支付/收款通知 —— 必须放行 =====

    @Test
    fun `微信支付凭证通知放行`() {
        val text = "微信支付凭证 | 付款金额 ¥25.00 | 付款时间 2026-10-30 12:00"
        assertTrue(NotificationGate.shouldCapture(text))
    }

    @Test
    fun `支付宝付款成功放行`() {
        assertTrue(NotificationGate.shouldCapture("支付宝通知 | 付款成功 ¥89.50"))
    }

    @Test
    fun `微信已支付放行`() {
        assertTrue(NotificationGate.shouldCapture("微信支付 | 已支付 ¥12.00"))
    }

    @Test
    fun `美团订单支付成功放行`() {
        assertTrue(NotificationGate.shouldCapture("美团外卖 | 订单支付成功 ¥36.50"))
    }

    @Test
    fun `收款到账放行`() {
        assertTrue(NotificationGate.shouldCapture("微信支付 | 收款到账 ¥100.00（已存入零钱）"))
    }

    @Test
    fun `自动扣款成功放行`() {
        assertTrue(NotificationGate.shouldCapture("京东金融 | 自动扣款成功 ¥199.00"))
    }

    @Test
    fun `真实退款放行`() {
        assertTrue(NotificationGate.shouldCapture("淘宝 | 退款成功 ¥59.00 已退回"))
    }

    @Test
    fun `含优惠字样的真实支付放行`() {
        // 真实支付通知常带「已优惠3元」——黑名单已移除裸「优惠」，不得误杀
        assertTrue(NotificationGate.shouldCapture("微信支付 | 支付成功 ¥36.00 已优惠3.00元"))
    }

    // ===== 广告 / 营销 / 平台积分 —— 必须拦截 =====

    @Test
    fun `开学季充值广告拦截`() {
        val text = "美团 | 开学季充值满100送20，立享¥100优惠"
        assertFalse(NotificationGate.shouldCapture(text))
    }

    @Test
    fun `新人红包权益广告拦截`() {
        // 关键用例：含「已到账」完成词但属平台权益 —— 黑名单兜底拦截
        val text = "淘宝 | 新人红包已到账，价值¥199会员权益"
        assertFalse(NotificationGate.shouldCapture(text))
    }

    @Test
    fun `购物车降价提醒拦截`() {
        val text = "淘宝 | 你的购物车商品降价啦，现在仅¥299"
        assertFalse(NotificationGate.shouldCapture(text))
    }

    @Test
    fun `邀请好友广告拦截`() {
        assertFalse(NotificationGate.shouldCapture("支付宝 | 邀请好友得现金，最高¥99"))
    }

    @Test
    fun `支付成功标题党拦截`() {
        // 「支付成功」+ 营销话术 → 黑名单兜底
        val text = "拼多多 | 支付成功！限时领取20元优惠券"
        assertFalse(NotificationGate.shouldCapture(text))
    }

    @Test
    fun `金币收益提醒拦截`() {
        // 「到账」单独出现不是完成词（已到账/到账成功才是）
        val text = "淘宝 | 金币收益到账提醒，今日可得¥3.2"
        assertFalse(NotificationGate.shouldCapture(text))
    }

    @Test
    fun `砍价助力广告拦截`() {
        assertFalse(NotificationGate.shouldCapture("拼多多 | 砍价免费拿，助力可得¥20"))
    }

    @Test
    fun `订单物流提醒拦截`() {
        val text = "京东 | 您的订单已发货，运费¥12.00"
        assertFalse(NotificationGate.shouldCapture(text))
    }
}

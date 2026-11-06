package com.mudasir.smartledger.capture

/** Conservative bank SMS filter. Sender and signature are hints, not proof of authenticity. */
object BankSmsGate {
    private val serviceNumber = Regex("^95[0-9]{3,8}$")
    private val providerNumber = Regex("^106[0-9]{5,20}$")
    private val bankSignature = Regex("【[^】]{0,20}(?:银行|信用社|农商行|农信|邮储)[^】]{0,15}】")
    private val account = Regex("(?:尾号|末四位|末4位|末尾)\\s*[:：]?\\s*[0-9]{4}(?![0-9])|(?:[*＊xX]{3,})\\s*[0-9]{4}(?![0-9])")
    private val movement = Regex("消费|支出|支取|扣款|扣费|扣除|支付|刷卡|取现|转出|汇出|存入|转入|收入|入账|到账|收款|退款|代发")
    private val amount = Regex("(?:[¥￥]\\s*[0-9]|[0-9][0-9,]*(?:\\.[0-9]{1,2})?\\s*元)")
    private val nonTransaction = Regex("验证码|校验码|动态密码|申请贷款|贷款额度|预借现金|信用额度|临时额度|提额|最高可|立即申请|点击领取|优惠券|积分兑换|分期优惠|邀请好友|中奖|抽奖|营销活动|尚未|未成功|交易失败|扣款失败|扣费失败|退款失败|支付失败|付款失败|即将|将于|预计|预约")

    fun shouldCapture(sender: String?, body: String): Boolean {
        val number = sender.orEmpty().replace(" ", "").removePrefix("+86")
        val sourceAllowed = serviceNumber.matches(number) ||
            (providerNumber.matches(number) && bankSignature.containsMatchIn(body))
        return sourceAllowed && account.containsMatchIn(body) && movement.containsMatchIn(body) &&
            amount.containsMatchIn(body) && !nonTransaction.containsMatchIn(body)
    }
}

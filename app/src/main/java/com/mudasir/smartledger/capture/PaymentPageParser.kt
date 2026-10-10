package com.mudasir.smartledger.capture

/** 纯文本支付结果页解析，独立于 Android，便于覆盖测试。 */
object PaymentPageParser {
    data class Result(val amount: Double, val isRefund: Boolean)

    private val refundWords = listOf("退款成功", "已退款", "退款到账")
    private val strongResultWords = listOf("支付成功", "付款成功", "收款成功", "退款成功")
    private val resultWords = strongResultWords + listOf("交易成功", "转账成功", "已支付")
    private val resultActions = listOf("完成", "返回商家", "查看账单", "查看详情", "返回首页")
    private val amountPatterns = listOf(
        Regex("[¥￥]\\s*([0-9]+(?:\\.[0-9]{1,2})?)"),
        Regex("([0-9]+(?:\\.[0-9]{1,2})?)\\s*元")
    )

    fun parse(text: String): Result? {
        val normalized = text.replace(',', ' ').replace('，', ' ')
        val isRefund = refundWords.any(normalized::contains)
        if (!isRefund && resultWords.none(normalized::contains)) return null
        if (resultActions.none(normalized::contains) && strongResultWords.none(normalized::contains)) return null
        val amount = amountPatterns.asSequence()
            .mapNotNull { it.find(normalized)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }
            .firstOrNull { it > 0.0 } ?: return null
        return Result(amount, isRefund)
    }
}

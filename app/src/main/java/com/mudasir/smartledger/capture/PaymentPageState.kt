package com.mudasir.smartledger.capture

/** A checkout page is never proof of payment; contradictory outcomes are ignored. */
object PaymentPageState {
    private val success = listOf("支付成功", "付款成功", "交易成功", "扣款成功")
    private val failure = listOf("支付失败", "付款失败", "交易失败", "已取消", "退款成功")

    fun isSuccessful(text: String): Boolean =
        success.any(text::contains) && failure.none(text::contains)

    fun hasOutcome(text: String): Boolean =
        success.any(text::contains) || failure.any(text::contains)
}

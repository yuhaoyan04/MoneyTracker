package com.mudasir.smartledger.capture

/** Activity names are hints, not proof that a payment can be intercepted. */
object PaymentScreenClassifier {
    fun matches(packageName: String, className: String, includeShoppingCheckout: Boolean = false): Boolean {
        val name = className.lowercase()
        return when (packageName) {
            "com.taobao.taobao" -> includeShoppingCheckout &&
                name.substringAfterLast('.') in setOf("cashdeskactivity", "ultrontradehybridactivity")
            "com.tencent.mm" -> name.substringAfterLast('.') == "orderhandlerui" ||
                listOf(".wallet.pay.", "walletpayui", "remittanceui", "offlinepayui").any(name::contains)
            "com.eg.android.AlipayGphone", "com.eg.android.AlipayGphone.rc",
            "com.eg.android.AlipayGphone.lite" ->
                listOf("cashier", "payui", "paymentactivity").any(name::contains)
            "com.unionpay" -> listOf("cashier", "paymentactivity", "payactivity").any(name::contains)
            else -> false
        }
    }
}

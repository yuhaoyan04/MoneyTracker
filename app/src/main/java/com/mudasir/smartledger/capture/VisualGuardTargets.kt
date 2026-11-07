package com.mudasir.smartledger.capture

/** Package allowlists and activity names are hints, never proof of an amount or visible overlay. */
object VisualGuardTargets {
    const val TAOBAO = "com.taobao.taobao"
    const val WECHAT = "com.tencent.mm"
    val shopping = setOf(TAOBAO, "com.jingdong.app.mall", "com.xunmeng.pinduoduo",
        "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.MobileTicket")

    fun sourceAllowed(pkg: String, taobaoEnabled: Boolean, multiEnabled: Boolean): Boolean =
        pkg in shopping && if (pkg == TAOBAO) taobaoEnabled else multiEnabled

    fun isTaobaoSkuDialog(pkg: String, cls: String): Boolean = pkg == TAOBAO &&
        cls.startsWith("com.taobao.android.sku.widget.SkuDialogFragment")

    fun sourceActivity(pkg: String, cls: String): Boolean {
        if (pkg !in shopping || cls.isBlank()) return false
        if (pkg == TAOBAO) return cls.endsWith("UltronTradeHybridActivity") || cls.endsWith("TBOrderListActivity")
        if (isJdVerification(pkg, cls)) return false
        if (isSourcePaymentTransition(cls)) return false
        return '.' in cls && !cls.startsWith("android.") && cls.endsWith("Activity")
    }

    fun isJdVerification(pkg: String, cls: String): Boolean =
        pkg == "com.jingdong.app.mall" && cls.endsWith(".JDRiskHandleActivity")
    fun isJdOrderAfterVerification(pkg: String, cls: String): Boolean =
        pkg == "com.jingdong.app.mall" && cls.endsWith(".SettlementFillOrderTaroActivity")

    /** Suppress only a very recent duplicate event for the same Taobao checkout. */
    fun isFreshDuplicateCheckout(pkg: String, cls: String, previousPkg: String, previousCls: String,
                                 qualifiedAt: Long, now: Long): Boolean =
        pkg == TAOBAO && pkg == previousPkg && cls.isNotBlank() &&
            (cls == previousCls || (previousCls == "taobao_order_list_nodes" && cls.endsWith("TBOrderListActivity"))) &&
            qualifiedAt > 0 && now - qualifiedAt in 0..1_000L

    fun isSourcePaymentTransition(cls: String): Boolean =
        listOf("cashdesk", "cashier", "payactivity", "paymentactivity").any(cls.lowercase()::contains)

    fun isWechatEntry(pkg: String, cls: String) = pkg == WECHAT && cls.endsWith("OrderHandlerUI")
    fun isWechatCashier(pkg: String, cls: String) = pkg == WECHAT && cls.endsWith("WxaLiteAppTransparentLiteUI")

    fun isOtherCashier(pkg: String, cls: String): Boolean {
        // Alipay can apply its own discount after leaving the merchant. The merchant's
        // amount is not proof of the final amount and must not drive its threshold.
        if (pkg !in CaptureWhitelist.bankPackages && pkg != "com.unionpay") return false
        val name = cls.lowercase()
        return listOf("cashier", "cashdesk", "paymentactivity", "payactivity", "mspcontainer", "wallet.pay.")
            .any(name::contains)
    }
}

/** Short-lived candidate belongs to a specific source. Callers supply a monotonic clock. */
class VisualHandoffState {
    private var amount: Double? = null
    private var source = ""
    private var capturedAt = 0L
    private var wechatEntryAt = -1L

    fun remember(sourcePackage: String, value: Double, now: Long) {
        if (sourcePackage !in VisualGuardTargets.shopping || !value.isFinite() || value <= 0) { clear(); return }
        source = sourcePackage; amount = value; capturedAt = now; wechatEntryAt = -1L
    }
    fun clear() { amount = null; source = ""; capturedAt = 0; wechatEntryAt = -1L }
    fun observe(pkg: String, cls: String, now: Long): Double? {
        if (amount == null || now - capturedAt !in 0..20_000L) { clear(); return null }
        if (VisualGuardTargets.isWechatEntry(pkg, cls)) { wechatEntryAt = now; return null }
        val accepted = (wechatEntryAt >= 0 && now - wechatEntryAt in 0..10_000L && VisualGuardTargets.isWechatCashier(pkg, cls)) ||
            VisualGuardTargets.isOtherCashier(pkg, cls)
        if (!accepted || pkg == source) return null
        return amount.also { clear() }
    }
}

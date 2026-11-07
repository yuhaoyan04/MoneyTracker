package com.mudasir.smartledger.capture

import java.math.BigDecimal

/** Rules for the opt-in JD-to-Alipay experiment. Text never leaves memory. */
object AlipayVisualPolicy {
    const val JD = "com.jingdong.app.mall"
    const val ALIPAY = "com.eg.android.AlipayGphone"
    private const val JD_CASHIER = "com.jd.cashier.app.cashier.tn.view.CashierTnPayPopActivity"
    private const val ALIPAY_CASHIER = "com.alipay.android.msp.ui.views.MspContainerActivity"
    fun isJdCashier(pkg: String, cls: String) = pkg == JD && cls == JD_CASHIER
    fun isAlipayCashier(pkg: String, cls: String) = pkg == ALIPAY && cls == ALIPAY_CASHIER
    fun isPayAction(texts: List<String>) = texts.any { it.trim() in setOf("确认付款", "立即付款", "继续付款", "确认支付", "立即支付", "确认并支付", "提交并支付", "去付款", "去支付") }
    fun isPendingCashier(lines: List<VisualText>, height: Int): Boolean {
        if (height <= 0) return false
        val words = lines.map { it.text.replace(Regex("\\s+"), "") }
        val terminal = setOf("支付成功", "付款成功", "支付失败", "付款失败", "订单已取消")
        if (words.any { it in terminal }) return false
        return lines.any { it.top >= height * 2 / 3 && it.text.trim() == "付款" } &&
            words.any { it.contains("使用密码") }
    }
    fun cropAmount(text: String): BigDecimal? {
        val compact = text.replace(Regex("\\s+"), "")
        val amounts = Regex("(?<![0-9.,])(?:[¥￥])?([0-9]{1,6}\\.[0-9]{2})(?![0-9.,])")
            .findAll(compact).mapNotNull { runCatching { BigDecimal(it.groupValues[1]) }.getOrNull() }
            .filter { it > BigDecimal.ZERO && it <= BigDecimal("1000000") }.toSet()
        return amounts.singleOrNull()
    }
}

/** Must observe an explicit action in the known JD cashier just before Alipay opens. */
class JdAlipayEntryTracker {
    private var inCashier = false
    private var cashierAt = -1L
    private var armedAt = -1L
    fun onWindow(pkg: String, cls: String, now: Long): Boolean {
        if (AlipayVisualPolicy.isJdCashier(pkg, cls)) { inCashier = true; cashierAt = now; armedAt = -1L; return false }
        if (AlipayVisualPolicy.isAlipayCashier(pkg, cls)) {
            val accepted = armedAt >= 0 && now - armedAt in 0..15_000L
            inCashier = false; armedAt = -1L
            return accepted
        }
        if (pkg == AlipayVisualPolicy.JD && cls.endsWith("Activity")) { inCashier = false; armedAt = -1L }
        if (pkg.isNotBlank() && pkg != AlipayVisualPolicy.JD && pkg != AlipayVisualPolicy.ALIPAY &&
            pkg != "com.mudasir.smartledger" && !pkg.startsWith("com.android.systemui")) clear()
        return false
    }
    fun onClick(pkg: String, texts: List<String>, now: Long): Boolean {
        val accepted = inCashier && now - cashierAt in 0..60_000L && pkg == AlipayVisualPolicy.JD && AlipayVisualPolicy.isPayAction(texts)
        if (accepted) armedAt = now
        return accepted
    }
    fun clear() { inCashier = false; cashierAt = -1L; armedAt = -1L }
}

/** Experimental handoff for other explicitly enabled shopping apps. A click and the
 * specific Alipay cashier are hints only; the cashier OCR must prove the amount. */
class ShoppingAlipayEntryTracker {
    private var source = ""
    private var sourceAt = -1L
    private var armedAt = -1L

    fun onWindow(pkg: String, cls: String, now: Long): String? {
        if (AlipayVisualPolicy.isAlipayCashier(pkg, cls)) {
            val accepted = source.takeIf { armedAt >= 0 && now - armedAt in 0..15_000L }
            clear()
            return accepted
        }
        if (pkg in VisualGuardTargets.shopping && pkg != AlipayVisualPolicy.JD && cls.endsWith("Activity")) {
            source = pkg
            sourceAt = now
            armedAt = -1L
        } else if (pkg.isNotBlank() && pkg != source && pkg != "com.mudasir.smartledger" &&
            !pkg.startsWith("com.android.systemui") && pkg != AlipayVisualPolicy.ALIPAY) {
            clear()
        }
        return null
    }

    fun onClick(pkg: String, texts: List<String>, now: Long): Boolean {
        val accepted = source.isNotEmpty() && pkg == source && now - sourceAt in 0..60_000L &&
            AlipayVisualPolicy.isPayAction(texts)
        if (accepted) armedAt = now
        return accepted
    }

    fun clear() { source = ""; sourceAt = -1L; armedAt = -1L }
}

class StableAlipayAmount {
    private var previous: BigDecimal? = null
    fun observe(amount: BigDecimal?): BigDecimal? {
        if (amount == null) { previous = null; return null }
        val stable = previous?.compareTo(amount) == 0
        previous = amount
        return if (stable) amount else null
    }
}

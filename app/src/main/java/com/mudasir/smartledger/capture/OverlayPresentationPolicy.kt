package com.mudasir.smartledger.capture

/** Payment windows may suppress ordinary application overlays. */
object OverlayPresentationPolicy {
    enum class Choice { ACCESSIBILITY, APPLICATION, NOTIFICATION }
    fun choose(pkg: String, accessibilityAvailable: Boolean): Choice = when {
        accessibilityAvailable -> Choice.ACCESSIBILITY
        pkg == AlipayVisualPolicy.ALIPAY -> Choice.NOTIFICATION
        else -> Choice.APPLICATION
    }
}

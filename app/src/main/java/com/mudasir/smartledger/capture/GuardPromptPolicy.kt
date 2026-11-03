package com.mudasir.smartledger.capture

/** Rules for closing a stale prompt and handing one approval to WeChat. */
object GuardPromptPolicy {
    fun shouldCloseOnForeground(origin: String?, foreground: String, ownPackage: String): Boolean =
        origin != null && foreground.isNotBlank() && foreground != ownPackage &&
            !foreground.startsWith("com.android.systemui") && foreground != origin

    fun canUseTaobaoHandoff(pkg: String, pending: Boolean, approvedAt: Long, now: Long, timeout: Long): Boolean =
        pkg == "com.tencent.mm" && pending && now - approvedAt in 0..timeout
}

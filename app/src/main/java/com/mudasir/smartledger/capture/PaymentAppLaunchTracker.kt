package com.mudasir.smartledger.capture

/** Detect a recent shopping-app to payment-app transition; this is a hint, not proof of payment. */
class PaymentAppLaunchTracker(private val ownPackage: String) {
    private val shopping = VisualGuardTargets.shopping + "com.sdu.didi.psnger"
    private val payment = setOf("com.tencent.mm", "com.eg.android.AlipayGphone",
        "com.eg.android.AlipayGphone.rc", "com.eg.android.AlipayGphone.lite", "com.unionpay") +
        CaptureWhitelist.bankPackages
    private var previousPackage: String? = null
    private var previousAt = 0L

    fun observe(pkg: String, at: Long): Boolean {
        if (pkg.isBlank() || pkg == ownPackage || pkg.startsWith("com.android.systemui")) return false
        if (at < previousAt) return false
        if (pkg == previousPackage) {
            previousAt = at
            return false
        }
        val hit = previousPackage in shopping && pkg in payment && at - previousAt in 0..20_000L
        previousPackage = pkg
        previousAt = at
        return hit
    }
}

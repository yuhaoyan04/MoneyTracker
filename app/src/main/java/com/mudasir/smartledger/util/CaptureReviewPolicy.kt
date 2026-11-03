package com.mudasir.smartledger.util

/** ?????????????????? */
object CaptureReviewPolicy {
    fun canConfirm(amount: Double): Boolean = amount.isFinite() && amount > 0.0
    fun canAutoConfirm(amount: Double, category: String, score: Float?): Boolean =
        canConfirm(amount) && category.isNotBlank() && score != null && score.isFinite() && score >= 0.5f
}

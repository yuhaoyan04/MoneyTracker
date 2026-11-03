package com.mudasir.smartledger

import com.mudasir.smartledger.util.CaptureReviewPolicy
import org.junit.Assert.*
import org.junit.Test

class CaptureReviewPolicyTest {
    @Test fun confirmationRequiresARealPositiveAmount() {
        assertFalse(CaptureReviewPolicy.canConfirm(0.0))
        assertFalse(CaptureReviewPolicy.canConfirm(-1.0))
        assertFalse(CaptureReviewPolicy.canConfirm(Double.NaN))
        assertFalse(CaptureReviewPolicy.canConfirm(Double.POSITIVE_INFINITY))
        assertTrue(CaptureReviewPolicy.canConfirm(0.01))
    }
    @Test fun automaticConfirmationRequiresCategoryAndUsableSuggestion() {
        assertFalse(CaptureReviewPolicy.canAutoConfirm(20.0, "", 0.9f))
        assertFalse(CaptureReviewPolicy.canAutoConfirm(20.0, "??", null))
        assertFalse(CaptureReviewPolicy.canAutoConfirm(20.0, "??", Float.NaN))
        assertFalse(CaptureReviewPolicy.canAutoConfirm(20.0, "??", 0.49f))
        assertTrue(CaptureReviewPolicy.canAutoConfirm(20.0, "??", 0.5f))
    }
}

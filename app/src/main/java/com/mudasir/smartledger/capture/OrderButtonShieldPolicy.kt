package com.mudasir.smartledger.capture

import java.math.BigDecimal

/** Only a verified high-value button may receive a touch shield. */
object OrderButtonShieldPolicy {
    fun eligible(candidates: List<OrderPayCandidate>, threshold: BigDecimal,
                 approved: OrderPayCandidate?, approvalUntil: Long, now: Long): List<OrderPayCandidate> =
        candidates.filter { candidate ->
            candidate.amount > threshold && !(approved == candidate && now in 0..approvalUntil)
        }
}

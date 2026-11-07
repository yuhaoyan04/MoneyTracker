package com.mudasir.smartledger

import com.mudasir.smartledger.capture.OrderButtonShieldPolicy
import com.mudasir.smartledger.capture.OrderPayCandidate
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class OrderButtonShieldPolicyTest {
    private val low = OrderPayCandidate(0, 0, 100, 50, BigDecimal("3"))
    private val high = OrderPayCandidate(0, 100, 100, 150, BigDecimal("90"))
    @Test fun shieldsOnlyAmountsStrictlyAboveThreshold() {
        assertEquals(listOf(high), OrderButtonShieldPolicy.eligible(listOf(low, high), BigDecimal("3"), null, 0, 10))
        assertTrue(OrderButtonShieldPolicy.eligible(listOf(high), BigDecimal("90"), null, 0, 10).isEmpty())
    }
    @Test fun approvalIsBoundToOneButtonAmountAndExpires() {
        assertTrue(OrderButtonShieldPolicy.eligible(listOf(high), BigDecimal("3"), high, 100, 99).isEmpty())
        assertEquals(listOf(high), OrderButtonShieldPolicy.eligible(listOf(high), BigDecimal("3"), high, 100, 101))
        val moved = high.copy(top = 200, bottom = 250)
        assertEquals(listOf(moved), OrderButtonShieldPolicy.eligible(listOf(moved), BigDecimal("3"), high, 100, 99))
    }
}

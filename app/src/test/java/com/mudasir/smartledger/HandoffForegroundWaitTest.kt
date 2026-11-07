package com.mudasir.smartledger

import com.mudasir.smartledger.capture.HandoffForegroundWait
import org.junit.Assert.assertEquals
import org.junit.Test

class HandoffForegroundWaitTest {
    @Test fun `wait for destination root but never deliver after timeout or disable`() {
        val decide = HandoffForegroundWait::decide
        assertEquals(HandoffForegroundWait.Decision.WAIT, decide("alipay", "jd", 0, true))
        assertEquals(HandoffForegroundWait.Decision.DELIVER, decide("alipay", "alipay", 500, true))
        assertEquals(HandoffForegroundWait.Decision.ABANDON, decide("alipay", "alipay", 1501, true))
        assertEquals(HandoffForegroundWait.Decision.ABANDON, decide("alipay", "alipay", 500, false))
        assertEquals(HandoffForegroundWait.Decision.ABANDON, decide("alipay", null, -1, true))
    }
}

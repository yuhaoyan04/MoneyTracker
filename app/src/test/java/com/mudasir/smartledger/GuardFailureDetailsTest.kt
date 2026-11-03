package com.mudasir.smartledger

import com.mudasir.smartledger.util.GuardFailureDetails
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardFailureDetailsTest {
    @Test fun `reports nested exception type message and stack location`() {
        val details = GuardFailureDetails.format(IllegalStateException("layout failed", IllegalArgumentException("bad theme")))
        assertTrue(details.contains("IllegalStateException"))
        assertTrue(details.contains("IllegalArgumentException"))
        assertTrue(details.contains("bad theme"))
        assertTrue(details.contains("GuardFailureDetailsTest"))
    }
}

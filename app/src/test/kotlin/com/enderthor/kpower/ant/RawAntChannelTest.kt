package com.enderthor.kpower.ant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class RawAntChannelTest {
    @Test
    fun openGateRechecksStateAfterClaimingTheOpeningSlot() {
        var checks = 0
        val opening = AtomicBoolean(false)

        assertFalse(tryBeginChannelOpen(opening) { ++checks == 2 })
        assertFalse(opening.get())
    }

    @Test
    fun openGateClaimsAnIdleChannelOnce() {
        val opening = AtomicBoolean(false)
        assertTrue(tryBeginChannelOpen(opening) { false })
        assertFalse(tryBeginChannelOpen(opening) { false })
    }
}

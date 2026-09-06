package com.enderthor.kpower.ant

import org.junit.Assert.assertEquals
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

    @Test
    fun searchRestNeverBacksOffForAMeterThatHasBeenSeen() {
        // THE regression guard. reopenAfterSearchTimeout is also reached from the 30s silence watchdog,
        // and a meter that sleeps while the rider coasts / waits at a light / stops for coffee looks
        // exactly like an absent one. If the backoff ever applies to a meter that HAS answered, the
        // rider resumes pedalling and waits out up to the cap with a blank power field.
        // Must hold at every rung, including a fully saturated counter.
        for (rests in intArrayOf(0, 1, 5, 6, 40, 1_000)) {
            assertEquals(
                "seen meter must keep the flat base rest at rests=$rests",
                BASE, searchRestMs(rests, meterEverSeen = true, base = BASE, cap = CAP),
            )
        }
    }

    @Test
    fun searchRestDoublesThenSaturatesForAMeterNeverSeen() {
        // The sequence the radio actually sees while a meter that has never answered stays absent.
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L),
            (0..5).map { searchRestMs(it, meterEverSeen = false, base = BASE, cap = CAP) },
        )
        // Saturated from there on — never above the cap, never back down.
        assertEquals(CAP, searchRestMs(6, meterEverSeen = false, base = BASE, cap = CAP))
        assertEquals(CAP, searchRestMs(40, meterEverSeen = false, base = BASE, cap = CAP))
        // No overflow: Long.shl masks the shift count to 6 bits, so an unguarded `base shl 64` would
        // return `base` (5s) and quietly put the radio back to searching nonstop.
        assertEquals(CAP, searchRestMs(62, meterEverSeen = false, base = BASE, cap = CAP))
        assertEquals(CAP, searchRestMs(64, meterEverSeen = false, base = BASE, cap = CAP))
        assertEquals(CAP, searchRestMs(1_000, meterEverSeen = false, base = BASE, cap = CAP))
    }

    private companion object {
        // The production constants, not copies: a change to either must reach this test.
        const val BASE = RawAntChannel.SEARCH_REST_MS
        const val CAP = RawAntChannel.MAX_SEARCH_REST_MS
    }
}

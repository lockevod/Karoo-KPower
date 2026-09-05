package com.enderthor.kpower.ant

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerEventTrackerTest {
    @Test
    fun repeatedEventCoastsAfterBoundWhileCounterRolloverIsFresh() {
        val tracker = PowerEventTracker(coastMs = 3_000)

        assertEquals(PowerEventStatus.NEW, tracker.update(0xFF, 1_000))
        assertEquals(PowerEventStatus.HOLD, tracker.update(0xFF, 3_000))
        assertEquals(PowerEventStatus.COAST, tracker.update(0xFF, 4_001))
        assertEquals(PowerEventStatus.NEW, tracker.update(0x00, 5_000))
    }

    @Test
    fun resetMakesNextEventFresh() {
        val tracker = PowerEventTracker(coastMs = 3_000)
        tracker.update(7, 1_000)
        tracker.reset()

        assertEquals(PowerEventStatus.NEW, tracker.update(7, 10_000))
    }

    /** M4: the 0x10 power event count advances once per CRANK revolution, so a 3 s coast window is
     *  satisfied BETWEEN PEDAL STROKES below ~20 rpm — an MTB technical climb would flicker to 0 W at
     *  its hardest moment. The window used for 0x10 must clear 15 rpm (4 s between strokes).
     *  Reads the production constant so tuning it re-tunes this test too. */
    @Test
    fun `the power-only coast window survives a very low cadence`() {
        val tracker = PowerEventTracker(RawAntPowerMeter.POWER_ONLY_COAST_MS)
        assertEquals(PowerEventStatus.NEW, tracker.update(10, 0L))
        // 15 rpm = one stroke every 4 s; the repeated frames in between must HOLD, not coast to zero.
        assertEquals(PowerEventStatus.HOLD, tracker.update(10, 3_500L))   // 3_500 < the window
        assertEquals(PowerEventStatus.NEW, tracker.update(11, 4_000L))
        // A genuinely stopped rider still coasts.
        assertEquals(PowerEventStatus.COAST,
            tracker.update(11, 4_000L + RawAntPowerMeter.POWER_ONLY_COAST_MS + 1_000L))
    }
}

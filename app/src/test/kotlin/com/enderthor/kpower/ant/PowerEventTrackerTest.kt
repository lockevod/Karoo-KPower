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
}

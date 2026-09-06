package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three re-seed decisions used to live inside the engine's combine collector, which needs a
 * KarooSystemService and a Context to reach — so they shipped untested. Extracted, they are a small
 * state machine and these are the cases that matter.
 */
class GradeHoldTest {

    private val hold = GradeHold(maxHoldMs = 60_000L, gapMs = 3_000L)

    private fun stream(v: Double, t: Long) = hold.update(streaming = true, streamValue = v, nowMs = t)
    private fun gone(t: Long) = hold.update(streaming = false, streamValue = 0.0, nowMs = t)

    @Test
    fun `a continuous stream never asks for a re-seed`() {
        var t = 0L
        assertFalse("the very first sample is not a gap", stream(6.0, t))
        repeat(20) { t += 1_000; assertFalse("tick at ${t}ms", stream(6.0, t)) }
        assertEquals(6.0, hold.slopePercent, 1e-9)
    }

    @Test
    fun `one missed tick is jitter, a real dropout is a step`() {
        var t = 1_000L
        stream(6.0, t)
        // A single skipped 1 Hz tick: 2 s later. Not a dropout.
        t += 2_000
        assertFalse("one missed tick must not re-seed", stream(6.0, t))
        // A real outage: the stream returns 10 s later, jumping from the held value to the real one.
        t += 10_000
        assertTrue("stream back after a gap must re-seed", stream(6.0, t))
    }

    @Test
    fun `the held grade survives a dropout and then falls to flat exactly once`() {
        var t = 1_000L
        stream(8.0, t)
        // Mid-climb outage: the grade must NOT collapse to 0, which would kill the gravity term.
        repeat(30) { t += 1_000; assertFalse(gone(t)) }
        assertEquals("held through the outage", 8.0, hold.slopePercent, 1e-9)

        // Past the bound a stale steep ramp would over-estimate, so fall back to flat — and that drop
        // is a step the compensator must not read as a fast descent.
        t += 31_000
        assertTrue("the hold cliff must re-seed", gone(t))
        assertEquals(0.0, hold.slopePercent, 1e-9)
        // ...and only once: staying dropped out must not re-seed on every tick afterwards.
        repeat(10) { t += 1_000; assertFalse("cliff must fire once, not per tick", gone(t)) }
    }

    @Test
    fun `a source switch re-seeds in both directions and only on the change`() {
        assertFalse("first observation is the baseline, not a switch", hold.noteAltGradeAvailable(false))
        assertTrue("karoo grade -> altitude grade", hold.noteAltGradeAvailable(true))
        assertFalse(hold.noteAltGradeAvailable(true))
        assertTrue("altitude grade -> karoo grade", hold.noteAltGradeAvailable(false))
        assertFalse(hold.noteAltGradeAvailable(false))
    }

    @Test
    fun `reset clears the held grade, the gap clock and the source`() {
        stream(9.0, 1_000L)
        hold.noteAltGradeAvailable(true)
        hold.reset()

        assertEquals(0.0, hold.slopePercent, 1e-9)
        // A fresh first sample far in the future must read as a first sample, not as a gap.
        assertFalse("no gap after reset", stream(4.0, 999_000L))
        assertFalse("source baseline cleared too", hold.noteAltGradeAvailable(false))
    }
}

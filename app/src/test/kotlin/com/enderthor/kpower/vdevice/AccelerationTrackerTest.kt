package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Test

class AccelerationTrackerTest {

    @Test
    fun `first sample returns zero (no reference yet)`() {
        val t = AccelerationTracker()
        assertEquals(0.0, t.update(speedMs = 5.0, nowMs = 1000L), 1e-9)
    }

    @Test
    fun `steady speed yields ~zero acceleration`() {
        val t = AccelerationTracker()
        t.update(5.0, 1000L)
        val a = t.update(5.0, 2000L)
        assertEquals(0.0, a, 1e-9)
    }

    @Test
    fun `acceleration is positive and EMA-smoothed when speeding up`() {
        val t = AccelerationTracker(emaAlpha = 1.0)
        t.update(5.0, 1000L)
        val a = t.update(6.0, 2000L)
        assertEquals(1.0, a, 1e-9)
    }

    @Test
    fun `acceleration is clamped to max`() {
        val t = AccelerationTracker(emaAlpha = 1.0, maxAccel = 2.0)
        t.update(5.0, 1000L)
        val a = t.update(20.0, 2000L)
        assertEquals(2.0, a, 1e-9)
    }

    @Test
    fun `near-zero speed resets and returns zero`() {
        val t = AccelerationTracker(emaAlpha = 1.0)
        t.update(5.0, 1000L)
        val a = t.update(0.0, 2000L)
        assertEquals(0.0, a, 1e-9)
    }

    @Test
    fun `large time gap resets reference and returns zero`() {
        val t = AccelerationTracker(emaAlpha = 1.0, maxDtMs = 5000L)
        t.update(5.0, 1000L)
        val a = t.update(8.0, 20000L)
        assertEquals(0.0, a, 1e-9)
    }

    @Test
    fun `EMA is cleared after a long pause (no carryover)`() {
        val t = AccelerationTracker(emaAlpha = 0.5, maxDtMs = 5000L)
        t.update(5.0, 1000L)
        t.update(8.0, 2000L)    // acumula EMA positivo
        t.update(8.0, 30000L)   // pausa > maxDt → reinicia y limpia el EMA
        val a = t.update(8.0, 31000L) // estable tras la pausa → 0, sin arrastrar EMA viejo
        assertEquals(0.0, a, 1e-9)
    }
}

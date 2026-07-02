package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothersTest {

    // ---- GradeSmoother ----

    @Test
    fun `grade smoother dampens a single-sample grade spike`() {
        val s = GradeSmoother(tauMs = 2_000.0)
        var t = 1_000L
        s.update(5.0, t)
        t += 900L
        val spiked = s.update(10.0, t)
        assert(spiked < 7.5) { "spiked=$spiked should be well below 10" }
    }

    @Test
    fun `grade smoother follows a sustained ramp change`() {
        val s = GradeSmoother(tauMs = 2_000.0)
        var t = 1_000L
        s.update(0.0, t)
        var out = 0.0
        repeat(8) {
            t += 900L
            out = s.update(8.0, t)
        }
        assert(out > 7.5) { "out=$out should have converged near 8" }
    }

    @Test
    fun `grade smoother reseeds after a long gap`() {
        val s = GradeSmoother(maxDtMs = 10_000L)
        s.update(2.0, 1_000L)
        assertEquals(9.0, s.update(9.0, 30_000L), 1e-9)
    }

    // ---- CadenceGate ----

    @Test
    fun `gate has hysteresis around the cutoff`() {
        val g = CadenceGate(onRpm = 25.0, offRpm = 20.0)
        assertFalse(g.update(0.0))     // arranque: sin pedalear
        assertTrue(g.update(30.0))     // supera ON → pedaleando
        assertTrue(g.update(22.0))     // zona muerta → mantiene ON
        assertFalse(g.update(19.0))    // por debajo de OFF → parado
        assertFalse(g.update(23.0))    // zona muerta → mantiene OFF
        assertTrue(g.update(26.0))     // supera ON de nuevo
    }
}

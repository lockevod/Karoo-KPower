package com.enderthor.kpower.vdevice

import org.junit.Assert.*
import org.junit.Test

class PowerSourceMetricsTest {
    @Test fun `3s smooths, np gated by recording`() {
        val m = PowerSourceMetrics()
        repeat(3) { m.tick(100.0, Double.NaN, recording = false) }
        assertEquals(100.0, m.power3sW.value, 1e-9)        // 3s updates even when not recording
        assertTrue(m.npW.value.isNaN())                    // NP gated off until recording
        // Only recording samples (all 200) feed NP -> NP of constant 200 W is 200 (30s window full).
        repeat(40) { m.tick(200.0, Double.NaN, recording = true) }
        assertEquals(200.0, m.npW.value, 1e-6)
    }

    @Test fun `reset clears`() {
        val m = PowerSourceMetrics(); repeat(40) { m.tick(300.0, 52.0, true) }; m.reset()
        assertTrue(m.npW.value.isNaN()); assertTrue(m.power3sW.value.isNaN())
        assertTrue(m.balanceAvgRightPct.value.isNaN())
    }

    @Test fun `NaN sample ignored`() {
        val m = PowerSourceMetrics(); m.tick(Double.NaN, Double.NaN, true); assertTrue(m.power3sW.value.isNaN())
    }

    @Test fun `balance average is POWER-WEIGHTED, gated by recording, skips NaN and zero-power`() {
        val m = PowerSourceMetrics()
        m.tick(100.0, 55.0, recording = false)             // not recording → no avg yet
        assertTrue(m.balanceAvgRightPct.value.isNaN())
        // power-weighted (like a head unit): (200·50 + 100·60) / 300 = 53.333, NOT the plain mean 55
        m.tick(200.0, 50.0, true); m.tick(100.0, 60.0, true)
        assertEquals((200 * 50.0 + 100 * 60.0) / 300.0, m.balanceAvgRightPct.value, 1e-9)
        val expected = (200 * 50.0 + 100 * 60.0) / 300.0
        m.tick(150.0, Double.NaN, true)                    // NaN balance → no weight added
        assertEquals(expected, m.balanceAvgRightPct.value, 1e-9)
        m.tick(0.0, 30.0, true)                            // 0-power second → weight 0, balance is noise there
        assertEquals(expected, m.balanceAvgRightPct.value, 1e-9)
    }
}

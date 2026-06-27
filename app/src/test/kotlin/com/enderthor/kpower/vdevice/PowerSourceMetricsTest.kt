package com.enderthor.kpower.vdevice

import org.junit.Assert.*
import org.junit.Test

class PowerSourceMetricsTest {
    @Test fun `3s smooths, np gated by recording`() {
        val m = PowerSourceMetrics()
        repeat(3) { m.tick(100.0, recording = false) }
        assertEquals(100.0, m.power3sW.value, 1e-9)        // 3s updates even when not recording
        assertTrue(m.npW.value.isNaN())                    // NP gated off until recording
        // Only recording samples (all 200) feed NP -> NP of constant 200 W is 200 (30s window full).
        repeat(40) { m.tick(200.0, recording = true) }
        assertEquals(200.0, m.npW.value, 1e-6)
    }

    @Test fun `reset clears`() {
        val m = PowerSourceMetrics(); repeat(40) { m.tick(300.0, true) }; m.reset()
        assertTrue(m.npW.value.isNaN()); assertTrue(m.power3sW.value.isNaN())
    }

    @Test fun `NaN sample ignored`() {
        val m = PowerSourceMetrics(); m.tick(Double.NaN, true); assertTrue(m.power3sW.value.isNaN())
    }

    @Test fun `torque aggregates while recording`() {
        val m = PowerSourceMetrics()
        repeat(10) { m.tickDynamics(20.0, recording = true) }
        assertEquals(20.0, m.avgTorqueNm.value, 1e-6)      // avg torque accumulates
        assertEquals(20.0, m.maxTorqueNm.value, 1e-6)      // max torque tracked
        m.reset(); assertTrue(m.avgTorqueNm.value.isNaN()) // reset clears torque too
    }
}

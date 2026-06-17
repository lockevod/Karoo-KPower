package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerMetricsTest {

    // ---- MovingAverage ----

    @Test
    fun `moving average over full window`() {
        val ma = MovingAverage(windowSamples = 3)
        assertEquals(100.0, ma.add(100.0), 1e-9)          // [100] -> 100
        assertEquals(150.0, ma.add(200.0), 1e-9)          // [100,200] -> 150
        assertEquals(200.0, ma.add(300.0), 1e-9)          // [100,200,300] -> 200
        assertEquals(300.0, ma.add(400.0), 1e-9)          // [200,300,400] -> 300 (desliza)
    }

    @Test
    fun `moving average reset clears the window`() {
        val ma = MovingAverage(windowSamples = 3)
        ma.add(100.0); ma.add(200.0)
        ma.reset()
        assertEquals(50.0, ma.add(50.0), 1e-9)
    }

    // ---- RunningAverage ----

    @Test
    fun `running average accumulates the whole series`() {
        val avg = RunningAverage()
        avg.add(100.0); avg.add(200.0); avg.add(300.0)
        assertEquals(200.0, avg.value, 1e-9)
    }

    @Test
    fun `running average is NaN before any sample`() {
        assertTrue(RunningAverage().value.isNaN())
    }

    @Test
    fun `running average reset starts over`() {
        val avg = RunningAverage()
        avg.add(100.0); avg.add(300.0)
        avg.reset()
        avg.add(50.0)
        assertEquals(50.0, avg.value, 1e-9)
    }

    // ---- NormalizedPowerCalculator ----

    @Test
    fun `NP is NaN until the 30s window is full`() {
        val np = NormalizedPowerCalculator()
        repeat(29) { np.add(200.0) }
        assertTrue("NP must be NaN before 30 samples", np.value.isNaN())
        np.add(200.0) // sample 30
        assertEquals(200.0, np.value, 1e-6) // potencia constante -> NP == media
    }

    @Test
    fun `NP of constant power equals that power`() {
        val np = NormalizedPowerCalculator()
        repeat(120) { np.add(250.0) }
        assertEquals(250.0, np.value, 1e-6)
    }

    @Test
    fun `NP exceeds average for variable power`() {
        val np = NormalizedPowerCalculator()
        // 60 s a 100 W y 60 s a 300 W: media = 200, NP > 200 por la 4a potencia.
        repeat(60) { np.add(100.0) }
        repeat(60) { np.add(300.0) }
        assertTrue("NP=${np.value} should exceed the 200 W average", np.value > 215.0)
    }

    @Test
    fun `NP reset starts a new ride`() {
        val np = NormalizedPowerCalculator()
        repeat(40) { np.add(400.0) }
        np.reset()
        repeat(29) { np.add(100.0) }
        assertTrue(np.value.isNaN())
    }
}

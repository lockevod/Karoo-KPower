package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.KarooSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldCalibratorTest {

    // Synthetic regressors mimicking the real ones: X1 (rolling) ∝ v, X2 (aero) ∝ v³. y = CdA·X2 + CrrEff·X1.
    private fun x1(v: Double) = 784.0 * v          // ≈ m·g·cosθ·v
    private fun x2(v: Double) = 0.6 * v * v * v    // ≈ ½·ρ·v²·v
    // BIMODAL speed (slow pins Crr where aero≈0, fast pins CdA) → well-conditioned, identifiable —
    // mirrors the real requirement that a calibration ride must span varied speeds.
    private fun speedFor(i: Int) = if (i % 2 == 0) 3.0 + (i % 6) else 16.0 + (i % 5)

    @Test fun `recovers known CdA and effective Crr from clean varied data`() {
        val cal = FieldCalibrator()
        val trueCda = 0.30
        val trueCrr = 0.0042
        // 450 samples spread 2..19 m/s (low speeds pin Crr, high speeds pin CdA → identifiable).
        repeat(450) { i ->
            val v = speedFor(i)
            cal.add(trueCda * x2(v) + trueCrr * x1(v), x1(v), x2(v), KarooSurface.ASPHALT)
        }
        val fit = cal.result()
        assertNotNull(fit); fit!!
        // ~1% tolerance: normal equations square the condition number of the (correlated v / v³)
        // regressors, so a clean fit recovers the ballpark, not machine precision — exactly why the ±
        // std-error/reliability gate exists.
        assertEquals(trueCda, fit.cda, 0.02)
        assertTrue("CdA should be reliable", fit.cdaReliable)
        val asphalt = fit.perSurface.first { it.surface == KarooSurface.ASPHALT }
        assertNotNull(asphalt.crrEff)
        assertEquals(trueCrr, asphalt.crrEff!!, 5e-4)
        assertTrue("asphalt Crr should be reliable", asphalt.reliable)
        // A surface that was never ridden is reported insufficient, not guessed.
        val gravel = fit.perSurface.first { it.surface == KarooSurface.GRAVEL }
        assertTrue(!gravel.sufficient && gravel.crrEff == null)
    }

    @Test fun `recovers a separate effective Crr per surface with a shared CdA`() {
        val cal = FieldCalibrator()
        val trueCda = 0.28
        val crrAsphalt = 0.0040
        val crrGravel = 0.0110
        repeat(300) { i -> val v = speedFor(i); cal.add(trueCda * x2(v) + crrAsphalt * x1(v), x1(v), x2(v), KarooSurface.ASPHALT) }
        repeat(300) { i -> val v = speedFor(i); cal.add(trueCda * x2(v) + crrGravel * x1(v), x1(v), x2(v), KarooSurface.GRAVEL) }
        val fit = cal.result()
        assertNotNull(fit); fit!!
        assertEquals(trueCda, fit.cda, 0.02)
        assertEquals(crrAsphalt, fit.perSurface.first { it.surface == KarooSurface.ASPHALT }.crrEff!!, 5e-4)
        assertEquals(crrGravel, fit.perSurface.first { it.surface == KarooSurface.GRAVEL }.crrEff!!, 5e-4)
    }

    @Test fun `single-speed (collinear) ride is not reliable`() {
        val cal = FieldCalibrator()
        // All samples at the SAME speed → X1 and X2 are a single point → CdA/Crr can't be separated.
        repeat(400) { cal.add(0.3 * x2(8.0) + 0.004 * x1(8.0), x1(8.0), x2(8.0), KarooSurface.ASPHALT) }
        val fit = cal.result()
        // Either unsolvable (null) or solved-but-flagged-unreliable; never a confident garbage fit.
        if (fit != null) {
            val asphalt = fit.perSurface.first { it.surface == KarooSurface.ASPHALT }
            assertTrue("collinear must not be reliable", !fit.cdaReliable || !asphalt.reliable)
        }
    }

    // Deterministic pseudo-noise in ±50 W (reproducible — no Math.random). ≈ the real residual of the
    // estimate vs a meter (wind/CdA/grade error), much larger than instrument noise.
    private fun noise(i: Int) = ((i * 1103515245L + 12345L) % 101 - 50).toDouble()

    @Test fun `noisy near-constant-pace ride is solvable but flagged unreliable`() {
        val cal = FieldCalibrator()
        // Near-constant pace: speed varies by <0.1 m/s (8.00..8.06) → X1 and X2 effectively collinear.
        // With realistic residual noise the fit is numerically solvable but POORLY IDENTIFIED → the ±
        // std-error gate must reject it (its real job; clean data has SE≈0 and wouldn't test it).
        repeat(330) { i ->
            val v = 8.0 + (i % 4) * 0.02
            cal.add(0.3 * x2(v) + 0.004 * x1(v) + noise(i), x1(v), x2(v), KarooSurface.ASPHALT)
        }
        val fit = cal.result()
        if (fit != null) {
            val asphalt = fit.perSurface.first { it.surface == KarooSurface.ASPHALT }
            assertTrue("narrow-band noisy fit must not be reliable", !fit.cdaReliable || !asphalt.reliable)
        }
    }

    @Test fun `noisy wide-speed-band ride stays reliable and recovers coefficients`() {
        val cal = FieldCalibrator()
        // Same noise, but bimodal speed → well-identified → the gate should ACCEPT it and recover ballpark.
        repeat(500) { i ->
            val v = speedFor(i)
            cal.add(0.3 * x2(v) + 0.004 * x1(v) + noise(i), x1(v), x2(v), KarooSurface.ASPHALT)
        }
        val fit = cal.result()
        assertNotNull(fit); fit!!
        assertTrue("wide-band fit should be reliable", fit.cdaReliable)
        assertEquals(0.30, fit.cda, 0.03)
    }

    @Test fun `too few samples yields null`() {
        val cal = FieldCalibrator()
        repeat(50) { i -> val v = speedFor(i); cal.add(0.3 * x2(v) + 0.004 * x1(v), x1(v), x2(v), KarooSurface.ASPHALT) }
        assertNull(cal.result())
    }

    @Test fun `reset clears accumulation`() {
        val cal = FieldCalibrator()
        repeat(400) { i -> val v = speedFor(i); cal.add(0.3 * x2(v) + 0.004 * x1(v), x1(v), x2(v), KarooSurface.ASPHALT) }
        assertNotNull(cal.result())
        cal.reset()
        assertNull(cal.result())
        assertEquals(0L, cal.sampleCount())
    }
}

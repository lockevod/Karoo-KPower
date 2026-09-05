package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * grade% = 100·Δaltitud / Σ(velocidad·dt), sobre una ventana causal corta. Fuente FRESCA: el stream de
 * altitud del Karoo va ~5 s por delante de su `grade` ya suavizado (medido en el FIT). Sin stream nuevo:
 * integra distancia desde la velocidad que ya consume el motor.
 */
class AltitudeGradeDeriverTest {

    /** A steady 5% climb (alt rises 0.05·distance) at constant speed → deriver reads ~5%. */
    @Test
    fun `steady climb yields the true grade`() {
        val d = AltitudeGradeDeriver(windowMs = 4_000L, emaTauMs = 500.0)
        val speed = 8.0            // m/s
        var alt = 100.0
        var t = 1_000L
        var g: Double? = null
        repeat(20) {
            alt += 0.05 * speed * 1.0   // 5% of the 8 m advanced each second
            g = d.update(speed, alt, t)
            t += 1_000L
        }
        assertEquals(5.0, g!!, 0.3)
    }

    /** Flat ground (constant altitude) → ~0% grade. */
    @Test
    fun `flat ground yields zero grade`() {
        val d = AltitudeGradeDeriver(windowMs = 4_000L, emaTauMs = 500.0)
        var t = 1_000L
        var g: Double? = null
        repeat(20) { g = d.update(8.0, 200.0, t); t += 1_000L }
        assertEquals(0.0, g!!, 0.1)
    }

    /** A descent (falling altitude) → negative grade. */
    @Test
    fun `descent yields negative grade`() {
        val d = AltitudeGradeDeriver(windowMs = 4_000L, emaTauMs = 500.0)
        val speed = 8.0
        var alt = 300.0
        var t = 1_000L
        var g: Double? = null
        repeat(20) { alt -= 0.08 * speed * 1.0; g = d.update(speed, alt, t); t += 1_000L }
        assertTrue("grade=${g} should be clearly negative", g!! < -6.0)
    }

    /** Stopped (no travel) → not enough distance to define a slope → null (caller falls back). */
    @Test
    fun `no travel returns null`() {
        val d = AltitudeGradeDeriver(windowMs = 4_000L, minTravelM = 4.0)
        var t = 1_000L
        var g: Double? = 0.0
        repeat(6) { g = d.update(0.0, 200.0, t); t += 1_000L }
        assertNull(g)
    }

    /** A gap longer than maxDtMs (pause / stream loss) resets the window: null until it refills. */
    @Test
    fun `gap resets the window`() {
        val d = AltitudeGradeDeriver(windowMs = 4_000L, maxDtMs = 10_000L)
        var t = 1_000L
        repeat(6) { d.update(8.0, 100.0 + it * 0.4, t); t += 1_000L }
        val afterGap = d.update(8.0, 130.0, t + 20_000L) // 20 s gap
        assertNull(afterGap)
    }

    /** A3: while speed is gone the altitude keeps arriving. Keeping those samples means that on
     *  recovery a real multi-second climb is divided by ONE tick of distance — a 2 % grade reads as
     *  6 % and the gravity term spikes by 100-250 W on every GPS recovery. */
    @Test
    fun `a speed dropout does not turn into a phantom grade spike on recovery`() {
        val deriver = AltitudeGradeDeriver()
        var t = 0L
        var alt = 100.0
        // 10 s at 8 m/s on a steady 2 % climb.
        repeat(10) { t += 1_000; alt += 0.16; deriver.update(8.0, alt, t) }
        t += 1_000; alt += 0.16
        val steady = deriver.update(8.0, alt, t)!!
        assertTrue("baseline should read ~2 %, was $steady", steady in 1.0..3.0)

        // 3 s of speed dropout — the stream reports 0, the barometer keeps climbing.
        repeat(3) { t += 1_000; alt += 0.16; assertNull(deriver.update(0.0, alt, t)) }

        // Speed is back. Until enough real distance is travelled there is no trustworthy grade...
        t += 1_000; alt += 0.16
        assertNull("must not derive a grade from one tick of distance", deriver.update(8.0, alt, t))
        // ...and once there is, it reads the true grade, not a 3x spike.
        var recovered: Double? = null
        repeat(4) { t += 1_000; alt += 0.16; recovered = deriver.update(8.0, alt, t) }
        // Tight: a 1.0..3.0 band still passed with the guard reverted (the decayed spike reaches 2.83 %).
        assertEquals("recovered grade should be the true 2 %", 2.0, recovered!!, 0.15)
    }
}

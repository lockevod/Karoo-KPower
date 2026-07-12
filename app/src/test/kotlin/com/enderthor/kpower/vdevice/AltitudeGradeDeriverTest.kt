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
}

package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeLeadCompensatorTest {

    /** leadSeconds=0 must reduce to the plain EMA (same behaviour as GradeSmoother). */
    @Test
    fun `with zero lead it matches the plain grade smoother`() {
        val comp = GradeLeadCompensator(tauMs = 1_500.0, leadSeconds = 0.0)
        val base = GradeSmoother(tauMs = 1_500.0)
        var t = 1_000L
        val seq = listOf(0.0, 2.0, 2.0, 5.0, 5.0, 5.0, 8.0, 3.0, 3.0)
        for (g in seq) {
            assertEquals(base.update(g, t), comp.update(g, t), 1e-9)
            t += 1_000L
        }
    }

    /** On a rising grade the lead term must ANTICIPATE: output above the no-lead EMA. */
    @Test
    fun `rising grade is anticipated above the plain ema`() {
        val lead = GradeLeadCompensator(tauMs = 1_500.0, leadSeconds = 4.0)
        val noLead = GradeLeadCompensator(tauMs = 1_500.0, leadSeconds = 0.0)
        var t = 1_000L
        var leadOut = 0.0
        var noLeadOut = 0.0
        // steady ramp up 0..10 %
        for (i in 0..10) {
            leadOut = lead.update(i.toDouble(), t)
            noLeadOut = noLead.update(i.toDouble(), t)
            t += 1_000L
        }
        assertTrue("lead=$leadOut should anticipate above noLead=$noLeadOut", leadOut > noLeadOut)
    }

    /** A sustained constant grade must converge to that grade: the lead decays to zero (no offset). */
    @Test
    fun `constant grade converges with no residual lead offset`() {
        val comp = GradeLeadCompensator(tauMs = 1_500.0, leadSeconds = 4.0, derivTauMs = 3_000.0)
        var t = 1_000L
        var out = 0.0
        repeat(60) { out = comp.update(6.0, t); t += 1_000L }
        assertEquals(6.0, out, 0.05)
    }

    /** A gap longer than maxDtMs (pause / stream loss) re-seeds with the raw grade, no lead. */
    @Test
    fun `gap longer than max dt reseeds to raw grade`() {
        val comp = GradeLeadCompensator(tauMs = 1_500.0, leadSeconds = 4.0, maxDtMs = 10_000L)
        comp.update(5.0, 1_000L)
        val out = comp.update(9.0, 1_000L + 20_000L) // 20 s gap
        assertEquals(9.0, out, 1e-9)
    }

    /** Output is clamped to the physical grade range even under an extreme derivative. */
    @Test
    fun `output is clamped to the physical grade range`() {
        val comp = GradeLeadCompensator(tauMs = 1_500.0, leadSeconds = 4.0, maxGradePercent = 25.0)
        var t = 1_000L
        var out = 0.0
        // huge jumps every tick
        for (g in listOf(0.0, 50.0, 100.0, 100.0, 100.0)) { out = comp.update(g, t); t += 1_000L }
        assertTrue("out=$out must be <= 25", out <= 25.0 + 1e-9)
    }

    /**
     * reseed() must kill the lead across a NON-gap discontinuity: the held-grade cliff (G -> 0) and the
     * stream coming back (0 -> G). Reseeding only `prevTs` left the step ramping through the base EMA,
     * so the derivative picked it up from the second tick and ~2/3 of the artefact survived — and the
     * first tick after a stream return came out well BELOW the truth. Both directions asserted.
     */
    @Test
    fun `reseed removes the lead artefact across a step in both directions`() {
        val c = GradeLeadCompensator(tauMs = 1_000.0, leadSeconds = 2.0)
        var t = 0L
        repeat(30) { t += 1_000; c.update(6.0, t) }

        // Hold cliff: 6 % -> 0 %. Without reseeding the base this dipped to about -0.6 % (a phantom
        // descent worth ~-43 W); a real gap would have re-seeded straight to 0.
        c.reseed()
        var worst = 0.0
        repeat(6) { t += 1_000; worst = minOf(worst, c.update(0.0, t)) }
        assertTrue("phantom descent after the hold cliff: $worst %", worst > -0.1)

        // Stream return: 0 % -> 6 %. The first tick must land on the truth, not undershoot it.
        repeat(20) { t += 1_000; c.update(0.0, t) }
        c.reseed()
        t += 1_000
        val firstAfter = c.update(6.0, t)
        assertEquals("first tick after the stream returns", 6.0, firstAfter, 0.05)
        var peak = firstAfter
        repeat(6) { t += 1_000; peak = maxOf(peak, c.update(6.0, t)) }
        assertTrue("overshoot after the stream returns: $peak %", peak < 6.1)
    }
}

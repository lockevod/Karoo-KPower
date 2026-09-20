package com.enderthor.kpower.ant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorqueBaselinesTest {

    private fun page(isCrank: Boolean, event: Int, period: Int, torque: Int) =
        TorqueData(isCrank, eventCount = event, ticks = event, cadenceRpm = 85.0,
            accumPeriod = period, accumTorque = torque)

    /** B3: with ONE shared baseline a meter sending both torque pages compared every frame against the
     *  other type, so torquePower() rejected all of them — null forever, the bounded HOLD expires and
     *  the meter reads 0 W for the whole ride, silently. Per-type baselines keep each stream's own. */
    @Test
    fun `alternating wheel and crank pages each keep their own baseline`() {
        val b = TorqueBaselines()
        assertNull("first wheel page has no baseline", b.advance(page(false, 0, 0, 0)))
        assertNull("first crank page has no baseline", b.advance(page(true, 0, 0, 0)))

        // Second wheel page must see the FIRST WHEEL page, not the crank one in between.
        val prevW = b.advance(page(false, 1, 256, 200))!!
        assertEquals(false, prevW.isCrank)
        assertEquals(0, prevW.accumPeriod)
        // ...and the delta must therefore produce real power instead of being rejected as mixed-type.
        // 128*pi*dTorque/dPeriod = 128*pi*200/256 = 314.16 W
        assertEquals(314.16, CyclingDynamicsParser.torquePower(prevW, page(false, 1, 256, 200))!!
            .powerW, 0.01)

        val prevC = b.advance(page(true, 1, 2048, 640))!!
        assertEquals(true, prevC.isCrank)
        assertEquals(20.0, CyclingDynamicsParser.torquePower(prevC, page(true, 1, 2048, 640))!!
            .torqueNm, 0.01)
    }

    @Test
    fun `reset clears both baselines`() {
        val b = TorqueBaselines()
        b.advance(page(false, 0, 0, 0))
        b.advance(page(true, 0, 0, 0))
        b.reset()
        assertNull(b.advance(page(false, 1, 256, 200)))
        assertNull(b.advance(page(true, 1, 2048, 640)))
    }
}

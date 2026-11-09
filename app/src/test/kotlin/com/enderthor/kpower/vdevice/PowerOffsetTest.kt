package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerOffsetTest {
    @Test fun identity_when_zero_zero() {
        assertEquals(200.0, applyPowerOffset(200.0, 0.0, 0.0), 1e-9)
    }

    @Test fun combined_factor_and_offset() {
        // 200 * (1 + 5/100) + 10 = 220
        assertEquals(220.0, applyPowerOffset(200.0, 5.0, 10.0), 1e-9)
    }

    @Test fun negative_factor_and_offset() {
        // 200 * (1 - 3/100) - 8 = 186
        assertEquals(186.0, applyPowerOffset(200.0, -3.0, -8.0), 1e-9)
    }

    @Test fun floors_to_zero() {
        // 10 * (1 + 0) - 50 = -40 -> 0
        assertEquals(0.0, applyPowerOffset(10.0, 0.0, -50.0), 1e-9)
    }

    @Test fun nan_passes_through() {
        assertTrue(applyPowerOffset(Double.NaN, 5.0, 10.0).isNaN())
    }

    @Test fun zero_in_zero_out_despite_offset() {
        // Field bug: 10% + 1W must NOT turn "not pedalling" into 1 phantom watt.
        assertEquals(0.0, applyPowerOffset(0.0, 10.0, 1.0), 1e-9)
    }
}

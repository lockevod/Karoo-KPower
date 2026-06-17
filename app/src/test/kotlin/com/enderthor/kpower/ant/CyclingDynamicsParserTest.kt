package com.enderthor.kpower.ant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

class CyclingDynamicsParserTest {
    // Real captured payload: page 0x10, event 0x23, balance 0xFF(invalid), cadence 0x00,
    // accumulated F7 67, instantaneous power 00 00 -> 0 W.
    @Test fun powerOnly_realCapture() {
        val d = CyclingDynamicsParser.parsePowerOnly(bytes(0x10, 0x23, 0xFF, 0x00, 0xF7, 0x67, 0x00, 0x00))!!
        assertEquals(0.0, d.powerW!!, 0.0)
        assertEquals(0.0, d.cadenceRpm!!, 0.0)
        assertNull(d.balanceRightPct)            // 0xFF -> invalid
    }

    @Test fun powerOnly_power_isLittleEndian_bytes6_7() {
        // instantaneous power = 0x012C = 300 W (b6=2C, b7=01)
        val d = CyclingDynamicsParser.parsePowerOnly(bytes(0x10, 0x10, 0x80, 0x5A, 0x00, 0x00, 0x2C, 0x01))!!
        assertEquals(300.0, d.powerW!!, 0.0)
        assertEquals(90.0, d.cadenceRpm!!, 0.0)  // b3=0x5A=90
        assertEquals(0.0, d.balanceRightPct!!, 0.0) // b2=0x80: right-indicator bit set, 0% right
    }

    @Test fun powerOnly_cadence255_invalid() {
        val d = CyclingDynamicsParser.parsePowerOnly(bytes(0x10, 0x10, 0xFF, 0xFF, 0x00, 0x00, 0xC8, 0x00))!!
        assertNull(d.cadenceRpm)
    }

    @Test fun teps_scale_and_invalid() {
        // TE left=0x64(100% ->50? scale 0.5) ; spec: raw*0.5 -> 0x64=100 -> 50%? confirm 0.5/LSB
        val d = CyclingDynamicsParser.parseTePs(bytes(0x13, 0x10, 0xC8, 0xC8, 0xC8, 0xFF, 0xFF, 0xFF))!!
        assertEquals(100.0, d.teLeftPct!!, 0.0)  // 0xC8=200 * 0.5 = 100%
        assertEquals(100.0, d.teRightPct!!, 0.0)
        assertEquals(100.0, d.psLeftPct!!, 0.0)
        assertNull(d.psRightPct)                 // 0xFF -> invalid
    }

    @Test fun forceAngle_brads_to_degrees() {
        // start=64 brad=90deg, end=192 brad=270deg, peaks 0 and 128, torque 0x0100/32=8Nm
        val d = CyclingDynamicsParser.parseForceAngle(bytes(0xE0, 0x10, 64, 192.toInt() and 0xFF, 0, 128, 0x00, 0x01), isLeft = false)!!
        assertEquals(90.0, d.startAngleDeg!!, 0.01)
        assertEquals(270.0, d.endAngleDeg!!, 0.01)
        assertEquals(8.0, d.torqueNm!!, 0.001)
        assertEquals(180.0, d.arcDeg!!, 0.01)    // (270-90)
    }

    @Test fun forceAngle_0xC0_invalid_sentinel() {
        // start=0xC0 & end=0xC0 -> both invalid (per spec pairing)
        val d = CyclingDynamicsParser.parseForceAngle(bytes(0xE1, 0x10, 0xC0, 0xC0, 0xC0, 0xC0, 0x00, 0x00), isLeft = true)!!
        assertNull(d.startAngleDeg)
        assertNull(d.endAngleDeg)
        assertNull(d.arcDeg)
    }

    @Test fun pedalPosition_riderPosition_pco_signed_and_invalid() {
        // b2=0x80 -> bits6:7 = 0b10 = STANDING ; cadence b3=0x5A=90 ;
        // rightPCO b4=0x80(-128)=invalid ; leftPCO b5=0xF6 = -10 mm
        val d = CyclingDynamicsParser.parsePedalPosition(bytes(0xE2, 0x10, 0x80, 0x5A, 0x80, 0xF6, 0xFF, 0xFF))!!
        assertEquals(RiderPosition.STANDING, d.riderPosition)
        assertEquals(90.0, d.cadenceRpm!!, 0.0)
        assertNull(d.rightPcoMm)                 // -128 invalid
        assertEquals(-10, d.leftPcoMm)
    }

    @Test fun torqueBarycenter_formula() {
        // spec worked example: raw 230 -> 145 deg (must mask &0xFF, 230>127)
        val d = CyclingDynamicsParser.parseTorqueBarycenter(bytes(0x14, 230, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))!!
        assertEquals(145.0, d.angleDeg!!, 0.01)
    }

    @Test fun wrongPage_returnsNull() {
        assertNull(CyclingDynamicsParser.parseForceAngle(bytes(0x10, 0, 0, 0, 0, 0, 0, 0), isLeft = false))
    }
}

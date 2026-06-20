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

    @Test fun powerOnly_balance_null_when_rightPedalBitClear() {
        // b2 = 0x32 (50) with bit7 CLEAR -> balance undetermined -> null (not 50% right).
        val d = CyclingDynamicsParser.parsePowerOnly(bytes(0x10, 0x10, 0x32, 0x5A, 0x00, 0x00, 0xC8, 0x00))!!
        assertNull(d.balanceRightPct)
    }

    @Test fun powerOnly_balance_value_when_rightPedalBitSet() {
        // b2 = 0xAA -> bit7 set, bits0-6 = 0x2A = 42 -> 42% right.
        val d = CyclingDynamicsParser.parsePowerOnly(bytes(0x10, 0x10, 0xAA, 0x5A, 0x00, 0x00, 0xC8, 0x00))!!
        assertEquals(42.0, d.balanceRightPct!!, 0.0)
    }

    @Test fun powerOnly_cadence255_invalid() {
        val d = CyclingDynamicsParser.parsePowerOnly(bytes(0x10, 0x10, 0xFF, 0xFF, 0x00, 0x00, 0xC8, 0x00))!!
        assertNull(d.cadenceRpm)
    }

    @Test fun teps_scale_and_invalid() {
        // Scale is 0.5 %/LSB: 0xC8 = 200 -> 100 %. b5 = 0xFF -> right PS invalid.
        val d = CyclingDynamicsParser.parseTePs(bytes(0x13, 0x10, 0xC8, 0xC8, 0xC8, 0xFF, 0xFF, 0xFF))!!
        assertEquals(100.0, d.teLeftPct!!, 0.0)  // 0xC8=200 * 0.5 = 100%
        assertEquals(100.0, d.teRightPct!!, 0.0)
        assertEquals(100.0, d.psLeftPct!!, 0.0)
        assertNull(d.psRightPct)                 // 0xFF -> invalid
        assertTrue(!d.psCombined)
    }

    @Test fun teps_psCombined_0xFE() {
        // b5 = 0xFE -> right PS field carries no value; the combined PS is in psLeft (b4).
        val d = CyclingDynamicsParser.parseTePs(bytes(0x13, 0x10, 0xFF, 0xFF, 0x96, 0xFE, 0xFF, 0xFF))!!
        assertTrue(d.psCombined)
        assertNull(d.psRightPct)
        assertEquals(75.0, d.psLeftPct!!, 0.0)   // 0x96=150 * 0.5 = 75%
    }

    @Test fun forceAngle_loneC0_isValid270() {
        // Only END is 0xC0 (not a pair) -> a legitimate 270deg, NOT the invalid sentinel.
        val d = CyclingDynamicsParser.parseForceAngle(bytes(0xE0, 0x10, 64, 0xC0, 0, 0, 0x00, 0x00), isLeft = false)!!
        assertEquals(90.0, d.startAngleDeg!!, 0.01)
        assertEquals(270.0, d.endAngleDeg!!, 0.01)
        assertEquals(180.0, d.arcDeg!!, 0.01)
    }

    @Test fun shortPayload_returnsNull() {
        assertNull(CyclingDynamicsParser.parsePowerOnly(bytes(0x10, 0x10, 0x00, 0x00)))
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

    // ── Crank/wheel torque (0x11/0x12) — how Garmin Rally/Vector broadcast power ────────────────
    @Test fun parseTorque_crank_realCapture() {
        // dev 6593: 12 1E 1E 29 FB 77 49 1D -> event 0x1E, cadence 0x29=41, period 0x77FB, torque 0x1D49
        val d = CyclingDynamicsParser.parseTorque(bytes(0x12, 0x1E, 0x1E, 0x29, 0xFB, 0x77, 0x49, 0x1D))!!
        assertTrue(d.isCrank)
        assertEquals(41.0, d.cadenceRpm!!, 0.0)
        assertEquals(0x77FB, d.accumPeriod)
        assertEquals(0x1D49, d.accumTorque)
    }

    @Test fun parseTorque_wrongPage_null() {
        assertNull(CyclingDynamicsParser.parseTorque(bytes(0x10, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(CyclingDynamicsParser.parseTorque(bytes(0x12, 0, 0)))   // too short
    }

    @Test fun torquePower_90rpm_200W() {
        // 90 rpm, 200 W over one crank event: period = 2048/1.5 = 1365 raw; torque = 200*1365/(128π) ≈ 679 raw.
        val prev = TorqueData(true, eventCount = 10, ticks = 10, cadenceRpm = null, accumPeriod = 0, accumTorque = 0)
        val curr = TorqueData(true, eventCount = 11, ticks = 11, cadenceRpm = null, accumPeriod = 1365, accumTorque = 679)
        val tp = CyclingDynamicsParser.torquePower(prev, curr)!!
        assertEquals(200.0, tp.powerW, 1.0)
        assertEquals(90.0, tp.cadenceRpm, 0.5)
        assertEquals(21.2, tp.torqueNm, 0.2)
    }

    @Test fun torquePower_handlesRollover() {
        // 8-bit event and 16-bit period/torque accumulators wrap to a normal positive delta, with
        // REALISTIC per-event values (90 rpm / 200 W) so the result passes the plausibility clamp.
        // dEvent=1, dPeriod=1365 (=2048/1.5), dTorque=679 -> ~200 W, ~90 rpm.
        val prev = TorqueData(true, eventCount = 0xFF, ticks = 0, cadenceRpm = null, accumPeriod = 65035, accumTorque = 65000)
        val curr = TorqueData(true, eventCount = 0x00, ticks = 1, cadenceRpm = null, accumPeriod = 864, accumTorque = 143)
        val tp = CyclingDynamicsParser.torquePower(prev, curr)!!
        assertEquals(200.0, tp.powerW, 2.0)
        assertEquals(90.0, tp.cadenceRpm, 1.0)
    }

    @Test fun torquePower_noNewEvent_returnsNull() {
        // Repeated frame (Δevent == 0) -> caller holds/coasts, parser returns null.
        val d = TorqueData(true, eventCount = 5, ticks = 5, cadenceRpm = 40.0, accumPeriod = 100, accumTorque = 200)
        assertNull(CyclingDynamicsParser.torquePower(d, d))
    }

    @Test fun torquePower_rejectsImplausibleSpike() {
        // Reacquire artifact: dEvent=1 but a tiny dPeriod with a big dTorque -> absurd watts.
        // 128π·600/3 ≈ 80k W -> must be rejected (null), so a spike never reaches the FIT.
        val prev = TorqueData(true, eventCount = 0, ticks = 0, cadenceRpm = null, accumPeriod = 0, accumTorque = 0)
        val curr = TorqueData(true, eventCount = 1, ticks = 1, cadenceRpm = null, accumPeriod = 3, accumTorque = 600)
        assertNull(CyclingDynamicsParser.torquePower(prev, curr))
    }

    @Test fun parseManufacturer_garmin_realCapture() {
        // dev 6593: 50 FF FF 44 01 00 FA 0D -> manufacturer LE bytes4-5 = 0x0001 = Garmin.
        val d = CyclingDynamicsParser.parseManufacturer(bytes(0x50, 0xFF, 0xFF, 0x44, 0x01, 0x00, 0xFA, 0x0D))!!
        assertEquals(1, d.manufacturerId)
        assertEquals("Garmin", AntManufacturers.name(d.manufacturerId))
    }

    @Test fun parseBatteryStatus_readsByte6Bits() {
        // byte6 bits4-6 = status. 0x20 -> (0x20>>4)&7 = 2 = Good. 0x40 -> 4 = Low. 0x50 -> 5 = Critical.
        assertEquals(2, CyclingDynamicsParser.parseBatteryStatus(bytes(0x52, 0xFF, 0, 0, 0, 0, 0x20, 0xFF)))
        assertEquals(4, CyclingDynamicsParser.parseBatteryStatus(bytes(0x52, 0xFF, 0, 0, 0, 0, 0x40, 0xFF)))
        assertNull(CyclingDynamicsParser.parseBatteryStatus(bytes(0x52, 0xFF, 0, 0, 0, 0, 0x00, 0xFF))) // 0 invalid
        assertNull(CyclingDynamicsParser.parseBatteryStatus(bytes(0x10, 0, 0, 0, 0, 0, 0, 0)))           // wrong page
    }

    @Test fun parseManufacturer_unknownId_fallsBack() {
        // dev 47436: manufacturer 0x0043 = 67 (not in the table) -> "ANT #67".
        val d = CyclingDynamicsParser.parseManufacturer(bytes(0x50, 0xFF, 0xFF, 0xFF, 0x43, 0x00, 0x25, 0x00))!!
        assertEquals(67, d.manufacturerId)
        assertEquals("ANT #67", AntManufacturers.name(d.manufacturerId))
    }
}

package com.enderthor.kpower.data

import com.enderthor.kpower.ant.SavedMeter
import com.enderthor.kpower.extension.toDoubleLocale
import com.enderthor.kpower.extension.toStringLocale
import com.enderthor.kpower.vdevice.applyPowerOffset
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PowerOffsetModelTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun legacy_config_json_without_offset_decodes_to_defaults() {
        // A config JSON written before the offset fields existed.
        val legacy = """{"id":0,"name":"Old","isActive":true,"bikeMass":"14.0",
            "rollingResistanceCoefficient":"0.008","dragCoefficient":"0.85","frontalArea":"0.42",
            "powerLoss":"2.5","headwindconf":"0.0","ftp":"257"}""".trimIndent()
        val c = json.decodeFromString<ConfigData>(legacy)
        assertEquals("0.0", c.estPowerFactorPct)
        assertEquals("0.0", c.estPowerOffsetW)
    }

    @Test fun config_offset_round_trips() {
        val c = ConfigData(0, "B", true, "14.0", "0.008", "0.85", "0.42", "2.5", "0.0", "257")
            .copy(estPowerFactorPct = "3.5", estPowerOffsetW = "-6.0")
        val back = json.decodeFromString<ConfigData>(json.encodeToString(c))
        assertEquals("3.5", back.estPowerFactorPct)
        assertEquals("-6.0", back.estPowerOffsetW)
    }

    @Test fun legacy_meter_json_without_offset_decodes_to_defaults() {
        val legacy = """{"deviceNumber":6593,"label":"Rally","slot":0}"""
        val m = json.decodeFromString<SavedMeter>(legacy)
        assertEquals(0.0, m.powerFactorPct, 1e-9)
        assertEquals(0.0, m.powerOffsetW, 1e-9)
    }

    @Test fun meter_offset_round_trips() {
        val m = SavedMeter(6593, "Rally", 0).copy(powerFactorPct = 2.0, powerOffsetW = 5.0)
        val back = json.decodeFromString<SavedMeter>(json.encodeToString(m))
        assertEquals(2.0, back.powerFactorPct, 1e-9)
        assertEquals(5.0, back.powerOffsetW, 1e-9)
    }

    // End-to-end simulation of the ESTIMATED path (PowerEstimationEngine:343): the config stores
    // offset as STRINGS, parsed with toDoubleLocale at ride time. A comma-decimal (ES) user typing
    // "3,5" / "-6,0" must produce the same correction as a dot-locale "3.5" / "-6.0".
    @Test fun estimated_offset_simulation_comma_and_dot_locale() {
        val c = ConfigData(0, "B", true, "14.0", "0.008", "0.85", "0.42", "2.5", "0.0", "257")
        // 200 W raw estimate, +3.5% slope, -6 W zero-bias  ->  200*1.035 - 6 = 201.0
        val comma = c.copy(estPowerFactorPct = "3,5", estPowerOffsetW = "-6,0")
        val dot   = c.copy(estPowerFactorPct = "3.5", estPowerOffsetW = "-6.0")
        val expected = 201.0
        assertEquals(expected, applyPowerOffset(200.0,
            comma.estPowerFactorPct.toDoubleLocale(), comma.estPowerOffsetW.toDoubleLocale()), 1e-9)
        assertEquals(expected, applyPowerOffset(200.0,
            dot.estPowerFactorPct.toDoubleLocale(), dot.estPowerOffsetW.toDoubleLocale()), 1e-9)
    }

    // The saved-meter panel displays the stored Double via toStringLocale and re-parses it via
    // toDoubleLocale on edit. That round-trip must be lossless regardless of the JVM locale's
    // decimal separator (a comma-locale user must not see their -6.0 turn into 0 or -60).
    @Test fun meter_offset_display_reparse_round_trips() {
        for (v in listOf(2.0, -6.0, 3.5, -0.25, 100.0)) {
            assertEquals(v, v.toStringLocale().toDoubleLocale(), 1e-9)
        }
    }
}

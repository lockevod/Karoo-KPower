package com.enderthor.kpower.data

import com.enderthor.kpower.ant.SavedMeter
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
}

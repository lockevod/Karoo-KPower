package com.enderthor.kpower.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogReporterTest {
    @Test
    fun uploadRedactionCoversActualSurfaceAndAntLogFormats() {
        val input = """
            SURFACE classifyAt(40,41678,-3,70379) -> ASPHALT
            ANTLOG UNKNOWN dev=6593 PAGE 0x51 payload=51 FF FF 01 02 03 04 05
            KAROODEV saved id=bike-uuid conn=ANT-6593 name=Home Rally serial=6593 batt=Good
            ===== CALIBRATION START #6593 (My Secret Meter) =====
            raw calibrate #6593 result=Success(zeroOffset=812)
        """.trimIndent()

        val redacted = LogReporter.redactForUpload(input)

        listOf("40,41678", "-3,70379", "6593", "bike-uuid", "ANT-6593", "Home Rally", "My Secret Meter", "51 FF FF 01 02 03 04 05")
            .forEach { assertFalse("leaked: $it", redacted.contains(it)) }
        listOf("classifyAt(•,•)", "dev=A", "PAGE 0x51 payload=•", "name=•", "serial=•", "#A")
            .forEach { assertTrue("missing redaction: $it", redacted.contains(it)) }
    }

    @Test
    fun deviceAliasesStayDistinctPerMeterAndStableWithinTheFile() {
        val redacted = LogReporter.redactForUpload(
            """
            ANTLOG dev=6593 power=210
            ANTLOG dev=41207 power=185
            ANTLOG dev=6593 power=214
            raw calibrate #41207 result=Success(zeroOffset=812)
            """.trimIndent()
        )

        listOf("6593", "41207").forEach { assertFalse("leaked: $it", redacted.contains(it)) }
        // Same meter → same alias on every line; the second meter → a different one, so a dual-meter
        // log can still be read per device.
        assertEquals(2, Regex("dev=A ").findAll(redacted).count())
        assertTrue(redacted.contains("dev=B "))
        assertTrue("calibration must be attributable to meter B", redacted.contains("#B"))
    }
}

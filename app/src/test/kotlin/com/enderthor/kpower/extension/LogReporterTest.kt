package com.enderthor.kpower.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aliases are assigned in order of first appearance and the map lives for the whole process, so the
 * concrete letter a device gets depends on what other tests ran first. Every assertion here is
 * therefore RELATIONAL (same number → same alias, different numbers → different aliases) — asserting
 * "dev=A" would pass or fail on JUnit's method order, not on the redactor.
 */
class LogReporterTest {

    private val alias = Regex("dev=([A-Z]+)")

    @Test
    fun uploadRedactionCoversActualSurfaceAndAntLogFormats() {
        val input = """
            SURFACE classifyAt(40,41678,-3,70379) -> ASPHALT
            ANTLOG UNKNOWN dev=6593 PAGE 0x51 payload=51 FF FF 01 02 03 04 05
            KAROODEV saved id=bike-uuid conn=ANT_PLUS name=Home Rally serial=6593 batt=Good
            ===== CALIBRATION START #6593 (My Secret Meter) =====
            raw calibrate #6593 result=Success(zeroOffset=812)
        """.trimIndent()

        val redacted = LogReporter.redactForUpload(input)

        listOf("40,41678", "-3,70379", "6593", "bike-uuid", "Home Rally", "My Secret Meter",
            "51 FF FF 01 02 03 04 05")
            .forEach { assertFalse("leaked: $it", redacted.contains(it)) }
        listOf("classifyAt(•,•)", "PAGE 0x51 payload=•", "name=•", "serial=•", "id=•")
            .forEach { assertTrue("missing redaction: $it", redacted.contains(it)) }
        assertTrue("device number should be aliased", alias.containsMatchIn(redacted))
        // conn= is a connection TYPE, not an identifier: blanking it threw away the only ANT-vs-BLE
        // signal in the log for no privacy gain.
        assertTrue("connection type must survive", redacted.contains("conn=ANT_PLUS"))
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
        val aliases = alias.findAll(redacted).map { it.groupValues[1] }.toList()
        assertEquals("three dev= lines", 3, aliases.size)
        assertEquals("same meter → same alias", aliases[0], aliases[2])
        assertNotEquals("different meters → different aliases", aliases[0], aliases[1])
        // The calibration line must be attributable to the SECOND meter, not a fresh letter.
        assertTrue("calibration attributable", redacted.contains("raw calibrate #${aliases[1]}"))
    }

    /** M11: a ride's log is uploaded in ~30 chunks, each redacted separately. A per-call alias map
     *  made the same meter `A` in one chunk and `B` in the next — defeating the whole point. */
    @Test
    fun `aliases are stable across separately redacted chunks`() {
        val first = LogReporter.redactForUpload("ANTLOG dev=6593 power=210")
        val second = LogReporter.redactForUpload("ANTLOG dev=41207 power=1\nANTLOG dev=6593 power=9")

        val a = alias.find(first)!!.groupValues[1]
        val inSecond = alias.findAll(second).map { it.groupValues[1] }.toList()
        assertEquals("same meter keeps its alias in a later chunk", a, inSecond[1])
        assertNotEquals(a, inSecond[0])
    }

    /** M10: the retry counter is not a device number. A bare `#\d+` made it unreadable on exactly the
     *  failure path the log exists to diagnose, and collided with small device numbers. */
    @Test
    fun `only device numbers are aliased, not other hash-prefixed counters`() {
        val redacted = LogReporter.redactForUpload(
            "RawAntChannel #6593 reopen in 2000ms (failure #3: death)"
        )
        assertTrue("device number aliased", Regex("RawAntChannel #[A-Z]+").containsMatchIn(redacted))
        assertTrue("retry counter kept", redacted.contains("failure #3"))
        assertFalse(redacted.contains("6593"))
    }

    /** M12: the name redaction must not depend on ` serial=` following it — reordering the format
     *  string would otherwise ship the rider's sensor name to Telegram with nothing failing. */
    @Test
    fun `the device name is redacted whatever follows it`() {
        listOf(
            "KAROODEV saved name=Home Rally serial=6593 batt=Good",
            "KAROODEV saved name=Home Rally batt=Good serial=6593",
            "KAROODEV saved id=x name=Home Rally",
        ).forEach {
            val redacted = LogReporter.redactForUpload(it)
            assertFalse("leaked name in: $it -> $redacted", redacted.contains("Home Rally"))
        }
    }
}

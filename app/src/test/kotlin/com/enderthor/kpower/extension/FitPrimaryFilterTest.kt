package com.enderthor.kpower.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPrimaryFilterTest {
    @Test fun `estimate fields written unless estimate is primary`() {
        assertFalse(writeEstimateFields("ESTIMATE", false))
        assertFalse(writeEstimateFields("REAL", true))
        assertTrue(writeEstimateFields("REAL", false))
        assertTrue(writeEstimateFields("EXTERNAL", false))
    }
    @Test fun `real meter fields written unless it is the primary device`() {
        assertFalse(writeMeterFields(7, "REAL", 7))
        assertTrue(writeMeterFields(7, "REAL", 9))
        assertTrue(writeMeterFields(7, "ESTIMATE", null))
    }
}

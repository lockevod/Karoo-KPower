package com.enderthor.kpower.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPrimaryFilterTest {
    @Test fun `estimate fields written unless it is the bound primary`() {
        assertTrue(writeEstimateFields(false))
        assertFalse(writeEstimateFields(true))
    }
    @Test fun `real meter fields written unless it is the primary device`() {
        assertFalse(writeMeterFields(7, "REAL", 7))
        assertTrue(writeMeterFields(7, "REAL", 9))
        assertTrue(writeMeterFields(7, "ESTIMATE", null))
    }
}

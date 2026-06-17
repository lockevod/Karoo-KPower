package com.enderthor.kpower.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPrimaryFilterTest {
    @Test fun `estimate omitted only when bound AND declared primary`() {
        assertFalse(writeEstimateFields("ESTIMATE", true))   // bound + declared → omit (it's the standard field)
        assertTrue(writeEstimateFields("ESTIMATE", false))   // declared but not bound → write (secondary)
        assertTrue(writeEstimateFields("REAL", true))        // bound but profile's primary is a real meter → write (secondary)
        assertTrue(writeEstimateFields("REAL", false))
        assertTrue(writeEstimateFields("EXTERNAL", true))
    }
    @Test fun `real meter fields written unless it is the primary device`() {
        assertFalse(writeMeterFields(7, "REAL", 7))
        assertTrue(writeMeterFields(7, "REAL", 9))
        assertTrue(writeMeterFields(7, "ESTIMATE", null))
    }
}

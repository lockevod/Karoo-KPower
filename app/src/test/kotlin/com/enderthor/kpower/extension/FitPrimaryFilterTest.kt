package com.enderthor.kpower.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPrimaryFilterTest {
    @Test fun `estimate omitted only when virtual device is the bound primary`() {
        assertFalse(writeEstimateFields(true))   // bound as Karoo power source → already standard field → omit est_*
        assertTrue(writeEstimateFields(false))   // not bound → write est_* (it's not in the standard stream)
    }
}

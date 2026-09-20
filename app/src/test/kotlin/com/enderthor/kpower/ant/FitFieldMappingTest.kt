package com.enderthor.kpower.ant

import org.junit.Assert.assertEquals
import org.junit.Test

class FitFieldMappingTest {
    @Test fun `slot maps to base after the estimate fields`() {
        assertEquals(4, fitFieldBase(0))
        assertEquals(8, fitFieldBase(1))
        assertEquals(12, fitFieldBase(2))
    }
    @Test fun `field offsets within a meter`() {
        assertEquals(4, powerFieldNum(0)); assertEquals(5, cadenceFieldNum(0))
        assertEquals(6, balanceFieldNum(0)); assertEquals(7, torqueFieldNum(0))
        assertEquals(8, powerFieldNum(1)); assertEquals(11, torqueFieldNum(1))
    }
}

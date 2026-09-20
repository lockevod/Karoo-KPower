package com.enderthor.kpower.surface

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoUtilsTest {
    @Test
    fun `distance to a segment 0_001 degrees north is about 111 meters`() {
        // Punto en (0,0); segmento horizontal a lat=0.001 entre lon 0 y lon 0.002.
        // El punto más cercano es (0.001, 0): ~111.2 m al norte.
        val d = GeoUtils.distancePointToSegmentMeters(
            0.0, 0.0,
            0.001, 0.0,
            0.001, 0.002
        )
        assertEquals(111.2, d, 1.0)
    }

    @Test
    fun `distance is zero when point lies on the segment`() {
        val d = GeoUtils.distancePointToSegmentMeters(
            0.0, 0.001,
            0.0, 0.0,
            0.0, 0.002
        )
        assertEquals(0.0, d, 0.5)
    }
}

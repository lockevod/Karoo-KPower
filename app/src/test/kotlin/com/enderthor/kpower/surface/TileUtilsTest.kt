package com.enderthor.kpower.surface

import org.junit.Assert.assertEquals
import org.junit.Test

class TileUtilsTest {
    @Test
    fun `location to tile at zoom 1 maps origin to tile 1,1`() {
        val (x, y) = TileUtils.locationToTileXY(0.0, 0.0, 1)
        assertEquals(1, x)
        assertEquals(1, y)
    }
}

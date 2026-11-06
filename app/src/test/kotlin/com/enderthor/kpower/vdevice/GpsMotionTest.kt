package com.enderthor.kpower.vdevice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsMotionTest {
    // ~1 m de latitud
    private val m = 1.0 / 111_320.0

    @Test
    fun `no fix yet is not motion`() {
        assertFalse(GpsMotion().isMoving(1_000L))
    }

    @Test
    fun `advancing more than the jitter counts as moving for the window`() {
        val g = GpsMotion(minMoveM = 3.0, windowMs = 5_000L)
        g.update(41.0, 2.0, 1_000L)
        g.update(41.0 + 4 * m, 2.0, 2_000L)
        assertTrue(g.isMoving(6_000L))
        assertFalse(g.isMoving(7_001L))
    }

    @Test
    fun `stationary jitter below the threshold is not motion`() {
        val g = GpsMotion(minMoveM = 3.0, windowMs = 5_000L)
        g.update(41.0, 2.0, 1_000L)
        repeat(10) { g.update(41.0 + (if (it % 2 == 0) 1 else -1) * m, 2.0, 2_000L + it * 1_000L) }
        assertFalse(g.isMoving(11_000L))
    }

    @Test
    fun `slow walking pace accumulates past the threshold`() {
        val g = GpsMotion(minMoveM = 3.0, windowMs = 5_000L)
        repeat(5) { g.update(41.0 + it * 1.0 * m, 2.0, 1_000L + it * 1_000L) }   // 1 m/s, 4 m
        assertTrue(g.isMoving(5_000L))
    }
}

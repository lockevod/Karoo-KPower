package com.enderthor.kpower.surface

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** Conversiones de tile Web Mercator (slippy map). Portado de routegraph. */
object TileUtils {
    fun locationToTileXY(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        // Clamp to the valid slippy-tile index range [0, n-1]: lon == 180 maps to x == n, and extreme
        // latitudes can push y out of range — either would break downstream tile reads.
        val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = lat * PI / 180.0
        val y = ((1.0 - (ln(tan(latRad) + 1.0 / cos(latRad)) / PI)) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return Pair(x, y)
    }
}

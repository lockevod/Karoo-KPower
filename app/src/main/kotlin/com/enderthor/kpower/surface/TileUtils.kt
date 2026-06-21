package com.enderthor.kpower.surface

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/** Conversiones de tile Web Mercator (slippy map). Portado de routegraph. */
object TileUtils {
    fun locationToTileXY(lat: Double, lon: Double, z: Int): Pair<Int, Int> {
        val n = 1 shl z
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        val latRad = lat * PI / 180.0
        val y = ((1.0 - (ln(tan(latRad) + 1.0 / cos(latRad)) / PI)) / 2.0 * n).toInt()
        return Pair(x, y)
    }
}

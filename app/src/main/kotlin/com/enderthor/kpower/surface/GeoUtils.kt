package com.enderthor.kpower.surface

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Geometría ligera para "¿a qué distancia está este punto del way más cercano?".
 * Usa proyección equirectangular local (centrada en el punto); válida para las
 * distancias cortas que nos interesan (decenas de metros).
 */
object GeoUtils {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distancia en metros del punto P al segmento A-B (todo en grados lat/lon). */
    fun distancePointToSegmentMeters(
        plat: Double, plon: Double,
        alat: Double, alon: Double,
        blat: Double, blon: Double,
    ): Double {
        val mPerDegLat = EARTH_RADIUS_M * PI / 180.0
        val mPerDegLon = mPerDegLat * cos(plat * PI / 180.0)

        // Proyectamos con P en el origen.
        val ax = (alon - plon) * mPerDegLon; val ay = (alat - plat) * mPerDegLat
        val bx = (blon - plon) * mPerDegLon; val by = (blat - plat) * mPerDegLat

        val dx = bx - ax; val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((-ax) * dx + (-ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt(cx * cx + cy * cy)
    }
}

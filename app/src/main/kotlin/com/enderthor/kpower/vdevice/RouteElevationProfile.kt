package com.enderthor.kpower.vdevice

/**
 * Perfil de elevación de la ruta navegada, decodificado de `NavigatingRoute.routeElevationPolyline`.
 *
 * Formato (verificado en karoo-ext 1.1.9 y en los decoders de RouteGraph / maverick-hud): **Google
 * Encoded Polyline estándar, PRECISIÓN 1**, con **(distancia_m, elevación_m)** en los slots (lat, lng).
 * Los deltas se acumulan; la escala es 10⁻¹ (resolución 0,1 m).
 *
 * Da la pendiente en la posición del ciclista SIN retardo (es geometría precomputada, no el grade
 * barométrico del Karoo). Es la fuente definitiva de la Fase 2 — cuando hay curso cargado y on-route.
 *
 * [distM] es no decreciente. Estructura inmutable → segura para leer desde el tick del motor.
 */
class RouteElevationProfile private constructor(
    private val distM: DoubleArray,
    private val eleM: DoubleArray,
) {
    val size: Int get() = distM.size

    /** Span de distancia del perfil (m). Debe ≈ routeDistance si el eje es metros absolutos de la ruta. */
    val totalDistanceM: Double get() = distM.last()

    fun distanceAt(i: Int): Double = distM[i]
    fun elevationAtIndex(i: Int): Double = eleM[i]

    /** Elevación interpolada linealmente a la distancia [d] (clampada al rango del perfil). */
    private fun elevationAt(d: Double): Double {
        if (d <= distM.first()) return eleM.first()
        if (d >= distM.last()) return eleM.last()
        // lower-bound: primer índice con distM[i] >= d
        var lo = 0; var hi = distM.size - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (distM[mid] < d) lo = mid + 1 else hi = mid }
        val i1 = lo; val i0 = (lo - 1).coerceAtLeast(0)
        val span = distM[i1] - distM[i0]
        if (span <= 0.0) return eleM[i1]
        val t = (d - distM[i0]) / span
        return eleM[i0] + t * (eleM[i1] - eleM[i0])
    }

    /**
     * Pendiente (%) local en la distancia [distanceM], sobre una ventana centrada de ancho [windowM]
     * (recortada a los extremos del perfil). `null` si el perfil no cubre esa zona con span útil.
     */
    fun gradeAt(distanceM: Double, windowM: Double = 30.0): Double? {
        if (distM.size < 2) return null
        val half = windowM / 2.0
        val lo = (distanceM - half).coerceAtLeast(distM.first())
        val hi = (distanceM + half).coerceAtMost(distM.last())
        val span = hi - lo
        if (span < 1.0) return null
        return 100.0 * (elevationAt(hi) - elevationAt(lo)) / span
    }

    companion object {
        /** Decodifica el polyline (precisión 1, (dist,ele) en (lat,lng)). null si vacío / < 2 puntos. */
        fun fromPolyline(encoded: String?): RouteElevationProfile? {
            if (encoded.isNullOrEmpty()) return null
            val dist = ArrayList<Double>()
            val ele = ArrayList<Double>()
            var index = 0; var lat = 0L; var lng = 0L
            val factor = 10.0   // precisión 1
            try {
                while (index < encoded.length) {
                    var shift = 0; var value = 0L; var b: Int
                    do { b = encoded[index++].code - 63; value = value or ((b.toLong() and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                    lat += if (value and 1L != 0L) (value shr 1).inv() else (value shr 1)
                    shift = 0; value = 0
                    do { b = encoded[index++].code - 63; value = value or ((b.toLong() and 0x1f) shl shift); shift += 5 } while (b >= 0x20)
                    lng += if (value and 1L != 0L) (value shr 1).inv() else (value shr 1)
                    dist.add(lat / factor)   // slot lat = distancia (m)
                    ele.add(lng / factor)    // slot lng = elevación (m)
                }
            } catch (e: IndexOutOfBoundsException) {
                return null   // polyline truncado/corrupto
            }
            if (dist.size < 2) return null
            return RouteElevationProfile(dist.toDoubleArray(), ele.toDoubleArray())
        }
    }
}

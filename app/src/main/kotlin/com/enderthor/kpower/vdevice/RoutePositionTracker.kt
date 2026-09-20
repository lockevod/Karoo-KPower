package com.enderthor.kpower.vdevice

/**
 * Posición autoritativa a lo largo de la ruta navegada (patrón RouteGraph/KGhost):
 *
 *     distanceAlongRoute = routeDistance − DISTANCE_TO_DESTINATION
 *
 * Devuelve `null` (→ el llamante usa la pendiente de altitud/barométrica) cuando el dato NO es fiable:
 *  - `onRoute` = false → el ciclista está fuera de la ruta.
 *  - `rejoinDistanceM` != null → mid-rejoin: el "restante" es relativo al punto de reenganche, no a la
 *    ruta plana, así que la distancia recorrida quedaría mal.
 *  - fuera de rango [0, routeDistance] → dato incoherente.
 *
 * Sin estado (la posición es una función de las señales del instante).
 */
object RoutePositionTracker {
    fun distanceAlong(
        routeDistanceM: Double,
        distanceToDestinationM: Double,
        onRoute: Boolean,
        rejoinDistanceM: Double?,
    ): Double? {
        if (!onRoute || rejoinDistanceM != null) return null
        val d = routeDistanceM - distanceToDestinationM
        if (!d.isFinite() || d < 0.0 || d > routeDistanceM) return null
        return d
    }
}

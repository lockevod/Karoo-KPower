package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * distanceAlongRoute = routeDistance − DISTANCE_TO_DESTINATION, con las guardas del patrón
 * "authoritative route position" (RouteGraph/KGhost): off-route o mid-rejoin → null (el llamante cae a
 * la pendiente barométrica/altitud), porque entonces el "restante" no es relativo a la ruta plana.
 */
class RoutePositionTrackerTest {

    @Test
    fun `on route gives distance travelled along the route`() {
        // ruta 1000 m, faltan 400 → llevas 600
        assertEquals(600.0, RoutePositionTracker.distanceAlong(1000.0, 400.0, onRoute = true, rejoinDistanceM = null)!!, 1e-9)
    }

    @Test
    fun `off route yields null`() {
        assertNull(RoutePositionTracker.distanceAlong(1000.0, 400.0, onRoute = false, rejoinDistanceM = null))
    }

    @Test
    fun `mid rejoin yields null`() {
        assertNull(RoutePositionTracker.distanceAlong(1000.0, 400.0, onRoute = true, rejoinDistanceM = 50.0))
    }

    @Test
    fun `nonsense remaining greater than route length yields null`() {
        assertNull(RoutePositionTracker.distanceAlong(1000.0, 1500.0, onRoute = true, rejoinDistanceM = null))
    }

    @Test
    fun `negative remaining yields null`() {
        assertNull(RoutePositionTracker.distanceAlong(1000.0, -5.0, onRoute = true, rejoinDistanceM = null))
    }
}

package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * routeElevationPolyline = Google Encoded Polyline estándar, PRECISIÓN 1, con (distancia_m, elevación_m)
 * en los slots (lat, lng). Fixture de referencia generado con la librería `polyline` de Python:
 *   ?o}@o}@gEw|AwQw|AnK  ->  (0,100) (100,110) (250,140) (400,120)  [dist_m, elev_m]
 * Pendientes: seg1=10%, seg2=20%, seg3=-13,33%.
 */
class RouteElevationProfileTest {

    private val fixture = "?o}@o}@gEw|AwQw|AnK"

    @Test
    fun `decodes distance and elevation at precision 1`() {
        val p = RouteElevationProfile.fromPolyline(fixture)
        assertNotNull(p)
        p!!
        assertEquals(4, p.size)
        assertEquals(0.0, p.distanceAt(0), 0.01);   assertEquals(100.0, p.elevationAtIndex(0), 0.01)
        assertEquals(100.0, p.distanceAt(1), 0.01);  assertEquals(110.0, p.elevationAtIndex(1), 0.01)
        assertEquals(250.0, p.distanceAt(2), 0.01);  assertEquals(140.0, p.elevationAtIndex(2), 0.01)
        assertEquals(400.0, p.distanceAt(3), 0.01);  assertEquals(120.0, p.elevationAtIndex(3), 0.01)
    }

    @Test
    fun `grade on the first segment is ten percent`() {
        val p = RouteElevationProfile.fromPolyline(fixture)!!
        assertEquals(10.0, p.gradeAt(50.0, windowM = 20.0)!!, 0.1)
    }

    @Test
    fun `grade on the second segment is twenty percent`() {
        val p = RouteElevationProfile.fromPolyline(fixture)!!
        assertEquals(20.0, p.gradeAt(175.0, windowM = 20.0)!!, 0.1)
    }

    @Test
    fun `grade on the descending third segment is negative`() {
        val p = RouteElevationProfile.fromPolyline(fixture)!!
        assertEquals(-13.33, p.gradeAt(325.0, windowM = 20.0)!!, 0.2)
    }

    @Test
    fun `distance beyond the route end clamps, no crash`() {
        val p = RouteElevationProfile.fromPolyline(fixture)!!
        // Near/after the end still returns a finite grade (clamped window), not null/NaN.
        val g = p.gradeAt(399.0, windowM = 20.0)
        assertNotNull(g)
        assertTrue(g!!.isFinite())
    }

    @Test
    fun `empty or blank polyline yields null`() {
        assertNull(RouteElevationProfile.fromPolyline(""))
        assertNull(RouteElevationProfile.fromPolyline(null))
    }

    /** totalDistanceM exposes the profile's own distance span — used to sanity-check that the polyline's
     *  distance axis really is absolute meters matching the route's routeDistance (else it's dropped). */
    @Test
    fun `total distance is the last cumulative distance`() {
        val p = RouteElevationProfile.fromPolyline(fixture)!!
        assertEquals(400.0, p.totalDistanceM, 0.01)
    }
}

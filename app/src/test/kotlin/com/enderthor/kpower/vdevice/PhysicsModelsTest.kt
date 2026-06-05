package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Test

class PhysicsModelsTest {

    @Test
    fun `air density at 15C and sea-level pressure is ~1_225`() {
        // ρ = 101325 / (287.05 * 288.15) = 1.2250 kg/m3
        val rho = airDensity(pressurePa = 101325.0, tempC = 15.0, elevation = 0.0)
        assertEquals(1.225, rho, 0.005)
    }

    @Test
    fun `cold air is denser than warm air at same pressure`() {
        val cold = airDensity(pressurePa = 101325.0, tempC = 0.0, elevation = 0.0)
        val warm = airDensity(pressurePa = 101325.0, tempC = 30.0, elevation = 0.0)
        assert(cold > warm) { "cold=$cold should be > warm=$warm" }
    }

    @Test
    fun `null pressure falls back to barometric formula from elevation`() {
        val rho = airDensity(pressurePa = null, tempC = null, elevation = 1000.0)
        assertEquals(1.09, rho, 0.03)
    }

    @Test
    fun `null temperature uses 15C default`() {
        val withDefault = airDensity(pressurePa = 101325.0, tempC = null, elevation = 0.0)
        val explicit15 = airDensity(pressurePa = 101325.0, tempC = 15.0, elevation = 0.0)
        assertEquals(explicit15, withDefault, 1e-9)
    }

    @Test
    fun `frontal area for 175cm 70kg in hoods is in realistic band`() {
        // BSA = 0.007184 * 175^0.725 * 70^0.425 ≈ 1.847 m2 ; area = 0.24 * 1.847 ≈ 0.44 m2
        val area = estimateFrontalArea(heightCm = 175.0, weightKg = 70.0, position = com.enderthor.kpower.data.BikePosition.ROAD_HOODS)
        assertEquals(0.44, area, 0.05)
    }

    @Test
    fun `aero position gives smaller frontal area than MTB`() {
        val aero = estimateFrontalArea(175.0, 70.0, com.enderthor.kpower.data.BikePosition.TT)
        val mtb = estimateFrontalArea(175.0, 70.0, com.enderthor.kpower.data.BikePosition.MTB)
        assert(aero < mtb) { "aero=$aero should be < mtb=$mtb" }
    }

    @Test
    fun `non-positive height returns zero (caller keeps stored value)`() {
        assertEquals(0.0, estimateFrontalArea(0.0, 70.0, com.enderthor.kpower.data.BikePosition.ROAD_HOODS), 1e-9)
    }
}

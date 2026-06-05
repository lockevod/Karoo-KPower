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
        // Bassett (1999): A_base = 0.0293*1.75*70^0.425 + 0.0604 ≈ 0.372 m2 ; hoods ×1.09 ≈ 0.405
        val area = estimateFrontalArea(heightCm = 175.0, weightKg = 70.0, position = com.enderthor.kpower.data.BikePosition.ROAD_HOODS)
        assertEquals(0.405, area, 0.02)
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

    @Test
    fun `knobby tyre has higher Crr than slick`() {
        val slick = estimateCrr(28.0, 5.0, com.enderthor.kpower.data.TreadType.SLICK)
        val knobby = estimateCrr(54.0, 2.0, com.enderthor.kpower.data.TreadType.KNOBBY)
        assert(knobby > slick) { "knobby=$knobby should be > slick=$slick" }
    }

    @Test
    fun `slick road tyre Crr is in realistic band`() {
        val crr = estimateCrr(28.0, 5.0, com.enderthor.kpower.data.TreadType.SLICK)
        assertEquals(0.005, crr, 0.0015)
    }

    @Test
    fun `very low pressure increases Crr versus recommended pressure`() {
        val low = estimateCrr(28.0, 2.0, com.enderthor.kpower.data.TreadType.SLICK)
        val normal = estimateCrr(28.0, 6.0, com.enderthor.kpower.data.TreadType.SLICK)
        assert(low > normal) { "low=$low should be > normal=$normal" }
    }
}

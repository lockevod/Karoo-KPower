package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Test

class CyclingWattageEstimatorTest {

    private fun estimator(
        slope: Double = 0.0,
        speed: Double = 8.33,
        acceleration: Double = 0.0,
        temperatureC: Double? = 15.0,
        pressurePa: Double? = 101325.0,
        cadence: Double = 90.0,
        isforce: Boolean = true,
    ) = CyclingWattageEstimator(
        slope = slope,
        totalMass = 80.0,
        rollingResistanceCoefficient = 0.005,
        dragCoefficient = 0.88,
        frontalArea = 0.44,
        speed = speed,
        windSpeed = 0.0,
        powerLoss = 0.03,
        elevation = 0.0,
        ftp = 250.0,
        cadence = cadence,
        surface = 0.75,
        isforcepower = isforce,
        temperatureC = temperatureC,
        pressurePa = pressurePa,
        acceleration = acceleration,
    )

    @Test
    fun `flat steady 30kmh is in a sane band`() {
        val p = estimator().calculateCyclingWattage()
        assert(p in 120.0..260.0) { "power=$p out of expected flat band" }
    }

    @Test
    fun `accelerating needs more power than steady`() {
        val steady = estimator(acceleration = 0.0).calculateCyclingWattage()
        val accel = estimator(acceleration = 1.0).calculateCyclingWattage()
        assert(accel > steady) { "accel=$accel should exceed steady=$steady" }
    }

    @Test
    fun `cold air needs more power than warm air`() {
        val cold = estimator(temperatureC = 0.0).calculateCyclingWattage()
        val warm = estimator(temperatureC = 35.0).calculateCyclingWattage()
        assert(cold > warm) { "cold=$cold should exceed warm=$warm" }
    }

    @Test
    fun `hard braking never yields negative power (floor at 0W)`() {
        val p = estimator(slope = 0.0, acceleration = -2.0, speed = 5.0).calculateCyclingWattage()
        assert(p >= 0.0) { "power=$p must be >= 0" }
    }

    @Test
    fun `low cadence without force returns zero`() {
        val p = estimator(cadence = 10.0, isforce = false).calculateCyclingWattage()
        assertEquals(0.0, p, 1e-9)
    }
}

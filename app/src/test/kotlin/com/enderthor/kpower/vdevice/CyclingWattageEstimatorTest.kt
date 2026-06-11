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
        windSpeed: Double = 0.0,
        isPedaling: Boolean = true,
        isforce: Boolean = true,
        ftp: Double = 250.0,
    ) = CyclingWattageEstimator(
        slope = slope,
        totalMass = 80.0,
        rollingResistanceCoefficient = 0.005,
        dragCoefficient = 0.88,
        frontalArea = 0.44,
        speed = speed,
        windSpeed = windSpeed,
        powerLoss = 0.03,
        elevation = 0.0,
        ftp = ftp,
        isPedaling = isPedaling,
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
    fun `cap saturates at the affine FTP ceiling`() {
        // Pendiente fuerte → cálculo bruto >1000 W. Con FTP 200 el tope asintótico es
        // 1.2*200 + 130 = 370 W; el tanh debe estar saturado a ese nivel de exceso.
        val p = estimator(ftp = 200.0, slope = 0.20, speed = 8.33).calculateCyclingWattage()
        assertEquals(370.0, p, 2.0)
    }

    @Test
    fun `affine cap gives low-FTP riders proportionally more headroom`() {
        // FTP 100 → tope 250 W (2.5×); FTP 250 → tope 430 W (~1.7×), nunca 600.
        val low = estimator(ftp = 100.0, slope = 0.20, speed = 8.33).calculateCyclingWattage()
        val mid = estimator(ftp = 250.0, slope = 0.20, speed = 8.33).calculateCyclingWattage()
        assertEquals(250.0, low, 2.0)
        assertEquals(430.0, mid, 2.0)
    }

    @Test
    fun `cap is monotonic non-decreasing`() {
        // El tope escalonado antiguo bajaba la salida al cruzar umbrales (400 W → 340 W
        // con FTP 200). El cap suave debe ser monótono en todo el rango.
        val e = estimator(ftp = 200.0)
        var prev = Double.NEGATIVE_INFINITY
        var input = 0.0
        while (input <= 1500.0) {
            val out = e.applyPowerCap(input)
            assert(out >= prev - 1e-9) { "cap not monotonic at input=$input: $out < $prev" }
            prev = out
            input += 5.0
        }
    }

    @Test
    fun `power below the knee passes through uncapped`() {
        // FTP 250 → cap 430 W, rodilla en 0.8*430 = 344 W: a 300 W no debe comprimir nada.
        val e = estimator(ftp = 250.0)
        assertEquals(300.0, e.applyPowerCap(300.0), 1e-9)
    }

    @Test
    fun `invalid FTP (zero) does not clamp power to zero`() {
        // Con FTP 0 (campo vacío) el tope debe caer al techo absoluto, no clavar todo a 0.
        val p = estimator(ftp = 0.0, slope = 0.20, speed = 8.33).calculateCyclingWattage()
        assertEquals(600.0, p, 2.0)
    }

    @Test
    fun `strong tailwind reduces power versus still air`() {
        // v_rel negativo (cola > velocidad) debe EMPUJAR, no sumar drag como hacía (v_rel)².
        val still = estimator().calculateCyclingWattage()
        val tail = estimator(windSpeed = -15.0).calculateCyclingWattage()
        assert(tail < still) { "tailwind=$tail should be < still=$still" }
    }

    @Test
    fun `headwind increases power versus still air`() {
        val still = estimator().calculateCyclingWattage()
        val head = estimator(windSpeed = 5.0).calculateCyclingWattage()
        assert(head > still) { "headwind=$head should be > still=$still" }
    }

    @Test
    fun `not pedaling without force returns zero`() {
        val p = estimator(isPedaling = false, isforce = false).calculateCyclingWattage()
        assertEquals(0.0, p, 1e-9)
    }
}

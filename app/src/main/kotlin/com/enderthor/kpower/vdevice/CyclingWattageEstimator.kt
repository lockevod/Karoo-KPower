package com.enderthor.kpower.vdevice

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.tanh

// Techo absoluto de potencia (W): backstop de cordura para un estimador sin medidor.
// El tope real escala con el FTP (CAP_FTP_FACTOR·FTP); esto solo limita FTPs muy altos
// o el caso de FTP inválido. Subir/bajar aquí si se quiere otro límite.
private const val MAX_POWER_CAP_W = 600.0

// Tope asintótico AFÍN al FTP: cap = CAP_FTP_SLOPE·FTP + CAP_BASE_W. El margen de pico
// sobre FTP no escala linealmente (FTP 100 puede picar ~2.5×, FTP 250 rara vez pasa de
// ~1.7×), así que un múltiplo fijo quedaba corto abajo y generoso arriba. Anclas:
// FTP 100 → 250 W, FTP 250 → 430 W, FTP ~390+ → techo absoluto de 600 W.
// La compresión suave empieza en KNEE_FRACTION·cap.
private const val CAP_FTP_SLOPE = 1.2
private const val CAP_BASE_W = 130.0
private const val KNEE_FRACTION = 0.8

// Min ground speed (m/s ≈ 5.4 km/h) for a sample to count toward field calibration: only excludes
// near-standstill noise. LOW-speed samples are kept on purpose — they're where rolling dominates aero,
// which is exactly what separates (identifies) Crr from CdA in the regression.
private const val CALIB_MIN_SPEED_MS = 1.5

class CyclingWattageEstimator(
    private val gravity: Double = 9.80665,
    private val slope:  Double,
    private val totalMass: Double,
    private val rollingResistanceCoefficient: Double,
    private val dragCoefficient: Double,
    private val frontalArea: Double,
    private val speed:  Double,
    private val windSpeed: Double,
    private val powerLoss: Double,
    private val elevation: Double,
    private val ftp: Double,
    private val isPedaling: Boolean,
    private val surface: Double,
    private val isforcepower: Boolean,
    private val temperatureC: Double? = null,
    private val pressurePa: Double? = null,
    private val acceleration: Double = 0.0
) {

    /**
     * Tope monotónico con rodilla suave: por debajo de la rodilla la potencia pasa tal
     * cual; por encima se comprime con tanh hacia el tope asintótico. Sustituye al tope
     * escalonado (2.8/2.5/2.2/1.7·FTP), que no era monotónico: al cruzar un umbral la
     * potencia mostrada podía BAJAR al pedalear más fuerte (400 W → 340 W con FTP 200).
     * Si el FTP no es válido (0 o vacío), se cae al techo absoluto en vez de clavar
     * toda la potencia a 0.
     */
    fun applyPowerCap(estimatedPower: Double): Double {
        if (!isforcepower && !isPedaling) return 0.0

        val cap = if (ftp > 0.0) minOf(CAP_FTP_SLOPE * ftp + CAP_BASE_W, MAX_POWER_CAP_W) else MAX_POWER_CAP_W
        val knee = KNEE_FRACTION * cap
        if (estimatedPower <= knee) return estimatedPower

        val range = cap - knee
        return knee + range * tanh((estimatedPower - knee) / range)
    }

    /**
     * Returns the instantaneous power as the cap applied to the SIGNED total (road load + signed inertial
     * power `m·a·v`). The value can be NEGATIVE (hard decel): the caller is responsible for the floor.
     *
     * Why return the SIGNED total rather than flooring here: the cap must bound the WHOLE total. If road
     * load were floored/capped first and inertia added on top, a big acceleration spike could blow past
     * the cap. Capping `roadLoad + inertia` together keeps a single sane ceiling. The CALLER (the engine)
     * then floors per sample with `max(0, …)`, because a real power meter never reports negative power —
     * so for the estimate's instant/3s/NP/avg to be comparable to a real meter's they must floor per
     * sample too (NP especially: it 4th-powers samples, and (−x)⁴ = (+x)⁴ would rectify negatives into a
     * positive bias). GPS-derived acceleration noise is smoothed upstream by AccelerationTracker's EMA,
     * not by letting power go negative.
     */
    fun calculateCyclingWattage(): Double {
        // Not pedaling (and not forced) → no pedal power, regardless of inertia/gravity.
        if (!isforcepower && !isPedaling) return 0.0

        val slopeAngle = atan(slope)

        val gravityForce = calculateGravityForce(slopeAngle)
        val rollingResistanceForce = calculateRollingResistanceForce(slopeAngle)
        val aerodynamicDragForce = calculateAerodynamicDragForce()
        val inertiaForce = totalMass * acceleration
        // Drivetrain loss: P_wheel / (1 - loss). The field is free text: clamp to [0, 0.5] and treat
        // non-finite input (NaN/Infinity from a pasted non-number) as 0, so a bad entry can't drive
        // the power to NaN/Infinity (coerceIn alone leaves NaN unchanged).
        val safeLoss = if (powerLoss.isFinite()) powerLoss.coerceIn(0.0, 0.5) else 0.0
        val lossFactor = 1.0 - safeLoss
        val roadLoadPower = (gravityForce + rollingResistanceForce + aerodynamicDragForce +
                calculateDynamicRollingResistanceForce(slopeAngle)) * speed / lossFactor
        val inertiaPower = inertiaForce * speed / lossFactor    // SIGNED — caller floors per sample

        // Cap the SIGNED total: applyPowerCap passes everything below the knee unchanged (negatives too)
        // and only compresses high positives, so a big acceleration spike can't yield an absurd value.
        return applyPowerCap(roadLoadPower + inertiaPower)
    }

    /**
     * Field-calibration regressors for ONE sample: returns (Y, X1, X2) such that `Y = Crr_eff·X1 +
     * CdA·X2` (Martin power balance, see [FieldCalibrator]), computed from the REAL crank power and the
     * SAME inputs the estimate uses. X1's coefficient is the EFFECTIVE Crr for the sample's surface
     * (base Crr × surface factor — the surface factor is NOT folded into X1, so the per-surface fit
     * yields each surface's effective Crr) and X2's is CdA = dragCoefficient·frontalArea. Returns null
     * for samples that shouldn't be fitted: not moving fast enough (tiny/aliased aero signal), or bad
     * real power.
     */
    fun calibrationRegressors(realPowerW: Double): Triple<Double, Double, Double>? {
        if (!realPowerW.isFinite() || realPowerW <= 0.0 || speed < CALIB_MIN_SPEED_MS) return null
        val slopeAngle = atan(slope)
        val safeLoss = if (powerLoss.isFinite()) powerLoss.coerceIn(0.0, 0.5) else 0.0
        val wheelPower = realPowerW * (1.0 - safeLoss)                 // real power delivered to the wheel
        val gravityPower = gravity * sin(slopeAngle) * totalMass * speed
        val dynRollPower = calculateDynamicRollingResistanceForce(slopeAngle) * speed
        val inertiaPower = totalMass * acceleration * speed
        val y = wheelPower - gravityPower - dynRollPower - inertiaPower
        val x1 = gravity * cos(slopeAngle) * totalMass * speed                    // coeff = effective Crr (per surface)
        val vRel = speed + windSpeed
        val x2 = 0.5 * airDensity(pressurePa, temperatureC, elevation) * vRel * abs(vRel) * speed   // coeff = CdA
        if (!y.isFinite() || !x1.isFinite() || !x2.isFinite()) return null
        return Triple(y, x1, x2)
    }

    // Pequeño término de rodadura dinámica que el usuario añadió a propósito y quiere mantener
    // (validado en campo, sin cita formal). NO eliminar.
    private fun calculateDynamicRollingResistanceForce(slopeAngle: Double): Double {
        return 0.1 * cos(slopeAngle)
    }

    private fun calculateGravityForce(slopeAngle: Double): Double {
        return gravity * sin(slopeAngle) * totalMass
    }

    private fun calculateRollingResistanceForce(slopeAngle: Double): Double {
        return gravity * cos(slopeAngle) * totalMass * rollingResistanceCoefficient * surface
    }

    private fun calculateAerodynamicDragForce(): Double {
        val airDensity = airDensity(pressurePa, temperatureC, elevation)
        // v_rel con signo: con viento de cola mayor que la velocidad el aire empuja
        // (fuerza negativa); (v_rel)² perdía el signo y sumaba resistencia.
        val vRel = speed + windSpeed
        return 0.5 * dragCoefficient * frontalArea * airDensity * vRel * abs(vRel)
    }
}

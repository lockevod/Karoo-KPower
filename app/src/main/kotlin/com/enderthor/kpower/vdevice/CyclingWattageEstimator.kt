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

    fun calculateCyclingWattage(): Double {
        val slopeAngle = atan(slope)

        val gravityForce = calculateGravityForce(slopeAngle)
        val rollingResistanceForce = calculateRollingResistanceForce(slopeAngle)
        val aerodynamicDragForce = calculateAerodynamicDragForce()
        val inertiaForce = totalMass * acceleration
        // Drivetrain loss: P_wheel / (1 - loss). Clamp loss to [0, 0.5): the field is free text,
        // and loss >= 1 would yield Infinity/negative power (a user typing "100" meant 100%).
        val lossFactor = 1.0 - powerLoss.coerceIn(0.0, 0.5)
        val estimatedPower = (gravityForce + rollingResistanceForce + aerodynamicDragForce +
                calculateDynamicRollingResistanceForce(slopeAngle) + inertiaForce) * speed / lossFactor

        return maxOf(0.0, applyPowerCap(estimatedPower))
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

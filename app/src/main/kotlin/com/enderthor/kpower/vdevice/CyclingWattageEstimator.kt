package com.enderthor.kpower.vdevice

import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.pow

// Techo absoluto de potencia (W): backstop de cordura para un estimador sin medidor.
// El tope real escala con el FTP (factor·FTP); esto solo limita FTPs muy altos o el
// caso de FTP inválido. Subir/bajar aquí si se quiere otro límite.
private const val MAX_POWER_CAP_W = 600.0

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
    private val cadence: Double,
    private val surface: Double,
    private val isforcepower: Boolean,
    private val temperatureC: Double? = null,
    private val pressurePa: Double? = null,
    private val acceleration: Double = 0.0
) {

    fun smoothPower(estimatedPower: Double): Double {
   // Timber.d("Is force power: $isforcepower")
        if (!isforcepower && cadence < 22) return 0.0

        val factor = when {
            estimatedPower < 210 -> 2.8
            estimatedPower <= 300 -> 2.5
            estimatedPower <= 400 -> 2.2
            else -> 1.7
        }
       // Timber.d("Estimated power is $estimatedPower")
        // Si el FTP no es válido (0 o vacío), no se puede escalar el tope con él:
        // se cae al techo absoluto en vez de clavar toda la potencia a 0.
        val cap = if (ftp > 0.0) minOf(factor * ftp, MAX_POWER_CAP_W) else MAX_POWER_CAP_W
        return minOf(estimatedPower, cap)
    }

    fun calculateCyclingWattage(): Double {
        val slopeAngle = atan(slope)

        val gravityForce = calculateGravityForce(slopeAngle)
        val rollingResistanceForce = calculateRollingResistanceForce(slopeAngle)
        val aerodynamicDragForce = calculateAerodynamicDragForce()
        val inertiaForce = totalMass * acceleration
        val estimatedPower = ((gravityForce + rollingResistanceForce + aerodynamicDragForce +
                calculateDynamicRollingResistanceForce(slopeAngle) + inertiaForce) * speed * (1 - powerLoss).pow(-1))

        return maxOf(0.0, smoothPower(estimatedPower))
    }

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
        return (0.5 * dragCoefficient * frontalArea * airDensity * (speed + windSpeed).pow(2))
    }
}
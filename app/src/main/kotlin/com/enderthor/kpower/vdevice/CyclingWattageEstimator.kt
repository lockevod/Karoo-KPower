package com.enderthor.kpower.vdevice

import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.pow


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
        return minOf(estimatedPower, maxOf(factor*ftp,790.0))
    }

    fun calculateCyclingWattage(): Double {
        val slopeAngle = atan(slope)

        val gravityForce = calculateGravityForce(slopeAngle)
        val rollingResistanceForce = calculateRollingResistanceForce(slopeAngle)
        val aerodynamicDragForce = calculateAerodynamicDragForce()
        val inertiaForce = totalMass * acceleration
        val estimatedPower = ((gravityForce + rollingResistanceForce + aerodynamicDragForce +
                inertiaForce) * speed * (1 - powerLoss).pow(-1))

        return maxOf(0.0, smoothPower(estimatedPower))
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
package com.enderthor.kpower.vdevice

import timber.log.Timber
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.pow

import kotlin.math.exp


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
    private val isforcepower: Boolean
) {

    fun smoothPower(estimatedPower: Double): Double {
    Timber.d("Is force power: $isforcepower")
       if(!isforcepower) {
           if (cadence < 22) {
               return 0.0
           }
       }
        val factor = when {
            estimatedPower < 210 -> 2.8
            estimatedPower <= 300 -> 2.5
            estimatedPower <= 400 -> 2.2
            else -> 1.7
        }

        return minOf(estimatedPower, maxOf(factor*ftp,790.0))
    }

    fun calculateCyclingWattage(): Double {

        val gravityForce = calculateGravityForce()
        val rollingResistanceForce = calculateRollingResistanceForce()
        val aerodynamicDragForce = calculateAerodynamicDragForce()
        val estimatedPower = ((gravityForce + rollingResistanceForce + aerodynamicDragForce + calculateDynamicRollingResistanceForce()) * speed * (1 - powerLoss).pow(-1))

        Timber.d("Force cycling calculation: gravityForce is $gravityForce, rollingResistance is $rollingResistanceForce,aerodynamicDrag is $aerodynamicDragForce")


        return smoothPower(estimatedPower)
    }

    private fun calculateDynamicRollingResistanceForce(): Double {
        return 0.1 * cos(atan(slope))
    }
    private fun calculateGravityForce(): Double {
        return gravity * sin(atan(slope)) * totalMass
    }

    private fun calculateRollingResistanceForce(): Double {
        return gravity * cos(atan(slope)) * totalMass * rollingResistanceCoefficient * surface
    }

    private fun calculateAerodynamicDragForce(): Double {
        val airDensity = 1.225 * exp(-0.00011856 * elevation)

        return (0.5 * dragCoefficient * frontalArea * airDensity * (speed + windSpeed).pow(2))   // windspeed is positive because it is a headwind
    }
}
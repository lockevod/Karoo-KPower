package com.enderthor.kpower.vdevice

import kotlin.math.exp
import kotlin.math.pow

/** Constante específica del aire seco, J/(kg·K). */
private const val R_AIR = 287.05

/** Temperatura por defecto cuando no hay dato real, en °C. */
private const val DEFAULT_TEMP_C = 15.0

/**
 * Densidad del aire (kg/m³) por la ley de gases ideales `ρ = P / (R·T)`.
 *
 * - [pressurePa]: presión en Pa; si es null se deriva de la altitud con la
 *   fórmula barométrica (equivalente al comportamiento anterior).
 * - [tempC]: temperatura en °C; si es null se asume [DEFAULT_TEMP_C].
 * - [elevation]: altitud en metros (solo se usa para el fallback de presión).
 */
fun airDensity(pressurePa: Double?, tempC: Double?, elevation: Double): Double {
    val pressure = pressurePa ?: (101325.0 * exp(-0.00011856 * elevation))
    val tempK = (tempC ?: DEFAULT_TEMP_C) + 273.15
    return pressure / (R_AIR * tempK)
}

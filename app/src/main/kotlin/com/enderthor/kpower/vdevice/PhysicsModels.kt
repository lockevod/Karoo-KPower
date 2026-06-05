package com.enderthor.kpower.vdevice

import com.enderthor.kpower.data.BikePosition
import com.enderthor.kpower.data.TreadType
import kotlin.math.abs
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

/**
 * Área frontal (m²) estimada desde antropometría y posición.
 * BSA por DuBois; el área frontal es una fracción de la BSA según la posición.
 * Devuelve 0.0 si la altura no es válida (el llamante conserva el valor guardado).
 */
fun estimateFrontalArea(heightCm: Double, weightKg: Double, position: BikePosition): Double {
    if (heightCm <= 0.0 || weightKg <= 0.0) return 0.0
    val bsa = 0.007184 * heightCm.pow(0.725) * weightKg.pow(0.425)
    return position.areaFactor * bsa
}

/**
 * Crr aproximado a partir del neumático, sobre firme de referencia.
 * - base por dibujo del neumático.
 * - penalización suave por desviarse de una presión "de referencia" que
 *   escala con el ancho (anchos mayores → menor presión óptima).
 * El factor de superficie del perfil escala este Crr en el modelo físico.
 */
fun estimateCrr(widthMm: Double, pressureBar: Double, treadType: TreadType): Double {
    val base = treadType.baseCrr
    val w = widthMm.coerceIn(18.0, 70.0)
    val refPressure = (6.5 - (w - 25.0) * 0.07).coerceIn(1.5, 7.0)
    val p = pressureBar.coerceIn(1.0, 9.0)
    val penalty = 1.0 + 0.06 * abs(p - refPressure)
    return base * penalty
}

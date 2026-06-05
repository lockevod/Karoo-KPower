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
 * Área frontal (m²) estimada con la regresión de Bassett et al. (1999) para
 * ciclistas de carretera: A = 0.0293·altura(m)·masa(kg)^0.425 + 0.0604, escalada
 * por la posición. Devuelve 0.0 si la entrada no es válida.
 */
fun estimateFrontalArea(heightCm: Double, weightKg: Double, position: BikePosition): Double {
    if (heightCm <= 0.0 || weightKg <= 0.0) return 0.0
    val heightM = heightCm / 100.0
    val aBase = 0.0293 * heightM * weightKg.pow(0.425) + 0.0604
    return aBase * position.areaScale
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

/**
 * Calcula la aceleración (m/s²) a partir de la velocidad muestreada.
 * - EMA para filtrar ruido del GPS.
 * - Clamp a ±[maxAccel] (fuera de rango = ruido).
 * - Velocidad ~0 (parado o GPS stale, que reemite 0.0) → reinicia y devuelve 0,
 *   evitando un pico de frenada falso.
 * - dt no positivo o mayor que [maxDtMs] (pausa) → reinicia y devuelve 0.
 * No usa reloj propio: el llamante pasa [nowMs] (testeable).
 */
class AccelerationTracker(
    private val emaAlpha: Double = 0.3,
    private val maxAccel: Double = 2.0,
    private val maxDtMs: Long = 5_000L,
    private val minSpeedMs: Double = 0.5,
) {
    private var prevSpeed = 0.0
    private var prevTs = 0L
    private var ema = 0.0

    fun update(speedMs: Double, nowMs: Long): Double {
        if (speedMs <= minSpeedMs) {
            prevTs = 0L
            ema = 0.0
            return 0.0
        }
        if (prevTs == 0L) {
            prevTs = nowMs
            prevSpeed = speedMs
            return 0.0
        }
        val dtMs = nowMs - prevTs
        if (dtMs <= 0L || dtMs > maxDtMs) {
            prevTs = nowMs
            prevSpeed = speedMs
            ema = 0.0
            return 0.0
        }
        val raw = ((speedMs - prevSpeed) / (dtMs / 1000.0)).coerceIn(-maxAccel, maxAccel)
        ema = emaAlpha * raw + (1 - emaAlpha) * ema
        prevTs = nowMs
        prevSpeed = speedMs
        return ema
    }
}

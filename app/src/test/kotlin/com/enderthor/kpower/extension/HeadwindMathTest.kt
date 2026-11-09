package com.enderthor.kpower.extension

import kotlin.math.cos
import kotlin.math.round
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fija la aritmética de [effectiveHeadwindMs] contra la implementación ANTERIOR al refactor que
 * fusionó las dos lecturas de meteo de `headwindFlow` en una sola.
 *
 * [legacyHeadwind] reproduce la cadena vieja literalmente: `getRelativeHeadingFlow` producía un
 * `HeadingResponse.Value(diff)` (o NoGps), y `headwindFlow` leía la meteo por SEGUNDA vez y hacía
 * `(x as? Value)?.diff ?: 0.0`. Si el refactor hubiera movido un signo o un `+180`, estas
 * comparaciones fallan.
 */
class HeadwindMathTest {

    /** La cadena de dos etapas original, con su lectura duplicada de la meteo. */
    private fun legacyHeadwind(bearingDeg: Double?, windDirectionDeg: Double, windSpeedMs: Double): Double {
        // Etapa 1: getRelativeHeadingFlow — Value(diff) con rumbo, NoGps sin él.
        val relative: Double? = bearingDeg?.let { signedAngleDifference(it, windDirectionDeg + 180) }
        // Etapa 2: headwindFlow — segunda lectura de la meteo + el fallback a 0.0 para NoGps.
        val windDirection = relative ?: 0.0
        return round(cos((windDirection + 180) * Math.PI / 180.0) * windSpeedMs * 10.0) / 10.0
    }

    @Test
    fun `coincide con la cadena de dos etapas en toda la rosa de los vientos`() {
        var checked = 0
        for (bearing in 0..350 step 10) {
            for (windDir in 0..350 step 10) {
                for (speed in listOf(0.0, 1.4, 5.5, 12.0)) {
                    val b = bearing.toDouble()
                    val w = windDir.toDouble()
                    assertEquals(
                        "bearing=$b windDir=$w speed=$speed",
                        legacyHeadwind(b, w, speed),
                        effectiveHeadwindMs(b, w, speed),
                        0.0,
                    )
                    checked++
                }
            }
        }
        assertEquals(36 * 36 * 4, checked)
    }

    @Test
    fun `sin rumbo GPS conserva el fallback de cola completa`() {
        // Comportamiento heredado, no deseable pero deliberadamente preservado: sin rumbo el
        // ángulo relativo cae a 0 y cos(180) = -1, o sea viento de cola con toda la magnitud.
        assertEquals(-8.0, effectiveHeadwindMs(null, 90.0, 8.0), 0.0)
        assertEquals(legacyHeadwind(null, 217.0, 3.3), effectiveHeadwindMs(null, 217.0, 3.3), 0.0)
    }

    @Test
    fun `de frente es positivo y de cola negativo`() {
        // Convención meteorológica: windDirection es de DÓNDE VIENE el viento. Rodando hacia el
        // norte (rumbo 0) con viento del norte (0) se va contra él -> viento de cara, positivo.
        // Con viento del sur (180) el mismo rumbo lo lleva a favor -> negativo.
        val head = effectiveHeadwindMs(bearingDeg = 0.0, windDirectionDeg = 0.0, windSpeedMs = 10.0)
        val tail = effectiveHeadwindMs(bearingDeg = 0.0, windDirectionDeg = 180.0, windSpeedMs = 10.0)
        assertEquals(10.0, head, 0.0)
        assertEquals(-10.0, tail, 0.0)
        // Viento cruzado (del este, rumbo norte): componente longitudinal nula.
        assertEquals(0.0, effectiveHeadwindMs(0.0, 90.0, 10.0), 0.0)
    }

    @Test
    fun `viento en calma es cero venga de donde venga`() {
        for (windDir in 0..350 step 30) {
            assertEquals(0.0, effectiveHeadwindMs(45.0, windDir.toDouble(), 0.0), 0.0)
        }
    }
}

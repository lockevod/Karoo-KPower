package com.enderthor.kpower.vdevice

import kotlin.math.pow

/**
 * Media móvil de ventana fija sobre muestras a 1 Hz (ring buffer).
 * Usada para la potencia "3 s" (ventana 3) y como ventana interna de 30 s en la NP.
 * `sum` se mantiene de forma incremental (añade la nueva muestra y resta la que sale) y se
 * pone a cero en cada reset de ruta, de modo que la deriva de punto flotante queda acotada
 * dentro de una sola actividad.
 */
class MovingAverage(private val windowSamples: Int) {
    private val buffer = DoubleArray(windowSamples)
    private var count = 0
    private var head = 0
    private var sum = 0.0

    /** Añade una muestra y devuelve la media de la ventana actual. */
    fun add(sample: Double): Double {
        if (count == windowSamples) {
            sum -= buffer[head]
        } else {
            count++
        }
        buffer[head] = sample
        sum += sample
        head = (head + 1) % windowSamples
        return sum / count
    }

    /** ¿La ventana está completamente llena? */
    val isFull: Boolean get() = count == windowSamples

    fun reset() {
        count = 0; head = 0; sum = 0.0
    }
}

/**
 * Media aritmética acumulada de toda la actividad. [value] es NaN hasta la 1a muestra.
 */
class RunningAverage {
    private var sum = 0.0
    private var count = 0L

    fun add(sample: Double) {
        sum += sample; count++
    }

    val value: Double get() = if (count == 0L) Double.NaN else sum / count

    fun reset() {
        sum = 0.0; count = 0L
    }
}

/**
 * Normalized Power clásico (Coggan): media móvil de 30 s de la potencia a 1 Hz,
 * elevada a la 4a potencia, promediada sobre la actividad, y raíz 4a del resultado.
 * [value] es NaN hasta tener la ventana de 30 s llena.
 */
class NormalizedPowerCalculator(windowSamples: Int = 30) {
    private val window = MovingAverage(windowSamples)
    private var sumOfFourthPowers = 0.0
    private var count = 0L

    fun add(powerW: Double) {
        val rolled = window.add(powerW)
        if (window.isFull) {
            sumOfFourthPowers += rolled.pow(4)
            count++
        }
    }

    val value: Double
        get() = if (count == 0L) Double.NaN else (sumOfFourthPowers / count).pow(0.25)

    fun reset() {
        window.reset(); sumOfFourthPowers = 0.0; count = 0L
    }
}

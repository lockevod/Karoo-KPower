package com.enderthor.kpower.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Salida 2026-10-25: el sensor de rueda ANT da la velocidad en escalones de ~0,05 m/s, así que
 * subiendo a ritmo constante repetía EXACTAMENTE el mismo valor >5 s; el detector de GPS-stale lo
 * ponía a 0 y la estimación caía a 0 W el 13 % del tiempo pedaleando.
 */
class SpeedStalenessTest {
    private fun speed(v: Double) =
        StreamState.Streaming(DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to v)))

    /** Mismo valor cada segundo durante 8 s; devuelve las velocidades emitidas. */
    private fun run(moving: Boolean): List<Double> = runBlocking {
        var now = 0L
        flow { repeat(9) { now = 1_000L + it * 1_000L; emit(speed(3.704)) } }
            .withSpeedStaleness(staleThresholdMs = 5_000L, isMoving = { moving }, nowMs = { now })
            .toList()
            .map { it.getValueOrDefault() }
    }

    @Test
    fun `repeated speed while GPS shows motion is a real constant speed, not stale`() {
        assertEquals(List(9) { 3.704 }, run(moving = true))
    }

    @Test
    fun `repeated speed without independent motion is still treated as stale GPS`() {
        val out = run(moving = false)
        assertEquals(3.704, out[5], 0.0)   // 5 s exactos: aún no es stale (> umbral)
        assertEquals(0.0, out[6], 0.0)
        assertEquals(0.0, out[8], 0.0)
    }
}

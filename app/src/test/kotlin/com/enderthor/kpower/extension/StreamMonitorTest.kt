package com.enderthor.kpower.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMonitorTest {
    @Test
    fun `equal arrivals keep stream live then silence becomes unavailable and reconnects`() = runBlocking {
        val first = streaming(12.0)
        val recovered = streaming(13.0)
        var subscriptions = 0
        var repeatedBatchCompleted = false

        val states = withTimeout(2_000) {
            monitorStreamData(
                streamFactory = {
                    when (subscriptions++) {
                        0 -> flow {
                            repeat(8) {
                                emit(first)
                                delay(40)
                            }
                            repeatedBatchCompleted = true
                            awaitCancellation()
                        }
                        else -> flow {
                            emit(recovered)
                            awaitCancellation()
                        }
                    }
                },
                applyDistinct = true,
                timeoutMs = 250,
                shortDelayMs = 1,
                mediumDelayMs = 1,
                longDelayMs = 1,
            ).take(4).toList()
        }

        assertTrue("duplicate arrivals must reset timeout before silence", repeatedBatchCompleted)
        assertTrue(states[0] is StreamState.NotAvailable)
        assertEquals(first, states[1])
        assertTrue(states[2] is StreamState.NotAvailable)
        assertEquals(recovered, states[3])
    }

    /**
     * Un stream MUERTO que reproduce su ultimo valor al re-suscribir no debe reiniciar el
     * back-off. Antes lo hacia: cada reconexion entregaba un valor viejo al instante, eso ponia
     * retryAttempt a 0 y la espera se quedaba en el primer escalon indefinidamente, asi que
     * WAIT_STREAMS_LONG nunca entraba en juego. En la salida del 2026-09-12 eso fueron 101
     * reconexiones cada ~22 s durante los 19 min que el proceso sobrevivio al final de la ruta.
     *
     * Con el arreglo, tras `retryLimit` timeouts seguidos se pasa a longDelayMs y las
     * reconexiones se detienen; sin el, siguen llegando cada ciclo.
     */
    @Test
    fun `a replayed value from a dead stream must not reset the backoff`() = runBlocking {
        val stale = streaming(21.0)
        var subscriptions = 0

        val collector = launch {
            monitorStreamData(
                streamFactory = {
                    subscriptions++
                    flow {
                        emit(stale)          // el host reproduce el ultimo estado al suscribir
                        awaitCancellation()  // y despues, silencio -> timeout
                    }
                },
                applyDistinct = true,
                timeoutMs = 30,
                shortDelayMs = 1,
                mediumDelayMs = 5,
                longDelayMs = 10_000,        // distintivo: si se alcanza, las reconexiones paran
            ).collect { }
        }
        delay(500)
        collector.cancel()

        // retryLimit por defecto = 4, luego longDelayMs. Ciclo ~35 ms, asi que en 500 ms sin
        // arreglo caben ~14 reconexiones; con arreglo se para en ~6.
        assertTrue(
            "el back-off no escalo: $subscriptions reconexiones en 500 ms",
            subscriptions <= 7,
        )
        assertTrue("debe haber reintentado al menos una vez", subscriptions >= 2)
    }

    private fun streaming(value: Double) = StreamState.Streaming(
        DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to value))
    )
}

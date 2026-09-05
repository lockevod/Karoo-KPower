package com.enderthor.kpower.extension

import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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

    private fun streaming(value: Double) = StreamState.Streaming(
        DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to value))
    )
}

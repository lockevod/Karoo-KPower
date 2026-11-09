package com.enderthor.kpower.ant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AntPowerManagerTest {
    @Test
    fun offsetChangeRepublishesUnchangedRawPower() = runBlocking {
        val raw = MutableStateFlow(200.0)
        val offsets = MutableStateFlow<Map<Int, Pair<Double, Double>>>(emptyMap())
        val values = mutableListOf<Double>()
        val firstValue = CompletableDeferred<Unit>()
        val collection = launch {
            correctedPowerFlow(42, raw, offsets).take(2).collect {
                values += it
                if (values.size == 1) firstValue.complete(Unit)
            }
        }

        withTimeout(1_000) { firstValue.await() }
        offsets.value = mapOf(42 to (0.0 to 10.0))
        withTimeout(1_000) { collection.join() }

        assertEquals(listOf(200.0, 210.0), values)
    }

    @Test
    fun oldIdentifyCleanupCannotRemoveNewSession() {
        val oldJob = Job()
        val newJob = Job()
        val jobs = java.util.concurrent.ConcurrentHashMap<Int, Job>()
        jobs[42] = newJob

        assertFalse(removeIdentifyJobIfOwned(jobs, 42, oldJob))
        assertEquals(newJob, jobs[42])
    }
}

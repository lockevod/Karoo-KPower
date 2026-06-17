package com.enderthor.kpower.vdevice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3s / NP / average power metrics for ONE power source, fed one sample per second.
 * Same math as the estimate engine, reusable per real meter. NOT thread-safe: call
 * tick()/reset() from a single coroutine (the per-source 1Hz loop).
 */
class PowerSourceMetrics {
    private val ma3s = MovingAverage(windowSamples = 3)
    private val np = NormalizedPowerCalculator()
    private val avg = RunningAverage()

    private val _power3sW = MutableStateFlow(Double.NaN)
    private val _npW = MutableStateFlow(Double.NaN)
    private val _avgW = MutableStateFlow(Double.NaN)
    val power3sW: StateFlow<Double> = _power3sW.asStateFlow()
    val npW: StateFlow<Double> = _npW.asStateFlow()
    val avgW: StateFlow<Double> = _avgW.asStateFlow()

    /** One sample/second. [recording] gates NP/avg accumulation (3s smooths always). */
    fun tick(instantW: Double, recording: Boolean) {
        if (instantW.isNaN()) return
        _power3sW.value = ma3s.add(instantW)
        if (recording) {
            np.add(instantW); avg.add(instantW)
            _npW.value = np.value; _avgW.value = avg.value
        }
    }

    /** New activity / fresh start. */
    fun reset() {
        ma3s.reset(); np.reset(); avg.reset()
        _power3sW.value = Double.NaN; _npW.value = Double.NaN; _avgW.value = Double.NaN
    }
}

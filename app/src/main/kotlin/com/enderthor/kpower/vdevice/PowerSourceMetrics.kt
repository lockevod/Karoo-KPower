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
    private val ma10s = MovingAverage(windowSamples = 10)
    private val np = NormalizedPowerCalculator()
    private val avg = RunningAverage()

    private val _power3sW = MutableStateFlow(Double.NaN)
    private val _power10sW = MutableStateFlow(Double.NaN)
    private val _npW = MutableStateFlow(Double.NaN)
    private val _avgW = MutableStateFlow(Double.NaN)
    private val _maxW = MutableStateFlow(Double.NaN)
    val power3sW: StateFlow<Double> = _power3sW.asStateFlow()
    val power10sW: StateFlow<Double> = _power10sW.asStateFlow()
    val npW: StateFlow<Double> = _npW.asStateFlow()
    val avgW: StateFlow<Double> = _avgW.asStateFlow()
    val maxW: StateFlow<Double> = _maxW.asStateFlow()

    /** One sample/second. [recording] gates NP/avg accumulation (3s smooths always).
     *  NaN samples (sensor dropout) are skipped: they don't advance the NP 30s window or the
     *  average, so NP/avg reflect pedaling/valid seconds. Every source (estimate and real meters)
     *  treats dropouts the same way, so the comparison stays apples-to-apples — though absolute NP
     *  may differ slightly from a head unit that counts elapsed seconds across a dropout. */
    fun tick(instantW: Double, recording: Boolean) {
        if (instantW.isNaN()) return
        _power3sW.value = ma3s.add(instantW)      // live smoothing — always
        _power10sW.value = ma10s.add(instantW)
        if (recording) {                          // session aggregates — only while recording
            np.add(instantW); avg.add(instantW)
            _npW.value = np.value; _avgW.value = avg.value
            if (_maxW.value.isNaN() || instantW > _maxW.value) _maxW.value = instantW
        }
    }

    /** New activity / fresh start. */
    fun reset() {
        ma3s.reset(); ma10s.reset(); np.reset(); avg.reset()
        _power3sW.value = Double.NaN; _power10sW.value = Double.NaN
        _npW.value = Double.NaN; _avgW.value = Double.NaN; _maxW.value = Double.NaN
    }
}

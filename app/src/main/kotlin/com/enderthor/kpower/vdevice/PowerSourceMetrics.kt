package com.enderthor.kpower.vdevice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3s / NP power metrics for ONE real meter, fed one sample per second. NOT thread-safe: call
 * tick()/reset() from a single coroutine (the per-source 1Hz loop). Avg/max/10s power, avg balance and
 * torque aggregates are intentionally NOT computed here — KPower has no on-screen field for them (the
 * Karoo shows torque/avg/max torque natively; avg/max power are derivable from the FIT).
 */
class PowerSourceMetrics {
    private val ma3s = MovingAverage(windowSamples = 3)
    private val np = NormalizedPowerCalculator()

    private val _power3sW = MutableStateFlow(Double.NaN)
    private val _npW = MutableStateFlow(Double.NaN)
    val power3sW: StateFlow<Double> = _power3sW.asStateFlow()
    val npW: StateFlow<Double> = _npW.asStateFlow()

    /** One sample/second. [recording] gates NP accumulation (3s smooths always).
     *  NaN samples (sensor dropout) are skipped: they don't advance the NP 30s window, so NP reflects
     *  pedaling/valid seconds. Every source treats dropouts the same way, so a comparison stays
     *  apples-to-apples — though absolute NP may differ slightly from a head unit that counts elapsed
     *  seconds across a dropout. */
    fun tick(instantW: Double, recording: Boolean) {
        if (instantW.isNaN()) return
        _power3sW.value = ma3s.add(instantW)      // live smoothing — always
        if (recording) {                          // session aggregate — only while recording
            np.add(instantW)
            _npW.value = np.value
        }
    }

    /** New activity / fresh start. */
    fun reset() {
        ma3s.reset(); np.reset()
        _power3sW.value = Double.NaN; _npW.value = Double.NaN
    }
}

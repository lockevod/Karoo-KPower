package com.enderthor.kpower.vdevice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3s / NP power + session torque metrics for ONE real meter, fed one sample per second. NOT thread-safe:
 * call tick()/tickDynamics()/reset() from a single coroutine (the per-source 1Hz loop). Avg/max/10s power
 * and avg balance are intentionally NOT computed here — they were dropped along with their data fields
 * (avg/max derivable post-ride from the FIT; balance is shown natively by the Karoo).
 */
private const val MAX_PLAUSIBLE_TORQUE_NM = 300.0   // beyond any real crank torque → a low-cadence artifact

class PowerSourceMetrics {
    private val ma3s = MovingAverage(windowSamples = 3)
    private val np = NormalizedPowerCalculator()

    private val _power3sW = MutableStateFlow(Double.NaN)
    private val _npW = MutableStateFlow(Double.NaN)
    val power3sW: StateFlow<Double> = _power3sW.asStateFlow()
    val npW: StateFlow<Double> = _npW.asStateFlow()

    // Session torque aggregates, mirroring the Karoo's native average/max torque fields.
    private val avgTorque = RunningAverage()
    private val _avgTorqueNm = MutableStateFlow(Double.NaN)
    private val _maxTorqueNm = MutableStateFlow(Double.NaN)
    val avgTorqueNm: StateFlow<Double> = _avgTorqueNm.asStateFlow()
    val maxTorqueNm: StateFlow<Double> = _maxTorqueNm.asStateFlow()

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

    /** Session torque aggregates, one sample/second, only while [recording]. NaN samples (coasting /
     *  no torque) are skipped so the averages reflect pedalled seconds. */
    fun tickDynamics(torqueNm: Double, recording: Boolean) {
        if (!recording) return
        // Guard against an implausible torque artifact: a power-only meter computes torque as
        // P/(2π·rpm/60), which blows up at a momentary near-zero cadence reading. One such sample would
        // otherwise permanently inflate MAX torque (and skew the average), so skip anything above any real
        // cycling torque. Real crank torque tops out well under this even in a max sprint.
        if (!torqueNm.isNaN() && torqueNm in 0.0..MAX_PLAUSIBLE_TORQUE_NM) {
            avgTorque.add(torqueNm); _avgTorqueNm.value = avgTorque.value
            if (_maxTorqueNm.value.isNaN() || torqueNm > _maxTorqueNm.value) _maxTorqueNm.value = torqueNm
        }
    }

    /** New activity / fresh start. */
    fun reset() {
        ma3s.reset(); np.reset()
        _power3sW.value = Double.NaN; _npW.value = Double.NaN
        avgTorque.reset()
        _avgTorqueNm.value = Double.NaN; _maxTorqueNm.value = Double.NaN
    }
}

package com.enderthor.kpower.vdevice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3s / NP / average power metrics for ONE power source, fed one sample per second.
 * Same math as the estimate engine, reusable per real meter. NOT thread-safe: call
 * tick()/reset() from a single coroutine (the per-source 1Hz loop).
 */
private const val MAX_PLAUSIBLE_TORQUE_NM = 300.0   // beyond any real crank torque → a low-cadence artifact

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

    // Session aggregates for torque + L/R balance, to mirror the Karoo's native average/max torque and
    // average power balance fields. avg via RunningAverage; balance is carried as the right-pedal %.
    private val avgTorque = RunningAverage()
    private val avgBalance = RunningAverage()
    private val _avgTorqueNm = MutableStateFlow(Double.NaN)
    private val _maxTorqueNm = MutableStateFlow(Double.NaN)
    private val _avgBalanceRightPct = MutableStateFlow(Double.NaN)
    val avgTorqueNm: StateFlow<Double> = _avgTorqueNm.asStateFlow()
    val maxTorqueNm: StateFlow<Double> = _maxTorqueNm.asStateFlow()
    val avgBalanceRightPct: StateFlow<Double> = _avgBalanceRightPct.asStateFlow()

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

    /** Session aggregates for torque + L/R balance, one sample/second, only while [recording]. NaN
     *  samples (coasting / no dynamics page) are skipped so the averages reflect pedalled seconds. */
    fun tickDynamics(torqueNm: Double, balanceRightPct: Double, recording: Boolean) {
        if (!recording) return
        // Guard against an implausible torque artifact: a power-only meter computes torque as
        // P/(2π·rpm/60), which blows up at a momentary near-zero cadence reading. One such sample would
        // otherwise permanently inflate MAX torque (and skew the average), so skip anything above any real
        // cycling torque. Real crank torque tops out well under this even in a max sprint.
        if (!torqueNm.isNaN() && torqueNm in 0.0..MAX_PLAUSIBLE_TORQUE_NM) {
            avgTorque.add(torqueNm); _avgTorqueNm.value = avgTorque.value
            if (_maxTorqueNm.value.isNaN() || torqueNm > _maxTorqueNm.value) _maxTorqueNm.value = torqueNm
        }
        if (!balanceRightPct.isNaN()) {
            avgBalance.add(balanceRightPct); _avgBalanceRightPct.value = avgBalance.value
        }
    }

    /** New activity / fresh start. */
    fun reset() {
        ma3s.reset(); ma10s.reset(); np.reset(); avg.reset()
        _power3sW.value = Double.NaN; _power10sW.value = Double.NaN
        _npW.value = Double.NaN; _avgW.value = Double.NaN; _maxW.value = Double.NaN
        avgTorque.reset(); avgBalance.reset()
        _avgTorqueNm.value = Double.NaN; _maxTorqueNm.value = Double.NaN; _avgBalanceRightPct.value = Double.NaN
    }
}

package com.enderthor.kpower.vdevice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 3s / NP power metrics + average L/R balance for ONE real meter, fed one sample per second. NOT
 * thread-safe: call tick()/reset() from a single coroutine (the per-source 1Hz loop). Avg/max/10s power
 * and torque aggregates are intentionally NOT computed here — the Karoo shows torque/avg/max torque
 * natively and avg/max power are derivable from the FIT. Average balance IS computed: with a KPW-virtual
 * (offset) source the Karoo shows no native dynamics, so KPower's own field is the only live view.
 */
class PowerSourceMetrics {
    private val ma3s = MovingAverage(windowSamples = 3)
    private val np = NormalizedPowerCalculator()
    // POWER-WEIGHTED average balance (Σ right%·P / Σ P), to match what head units report — a plain time
    // mean is skewed by low-power seconds where balance is noisy. Weight 0 at 0/NaN power excludes them.
    private var sumBalWeighted = 0.0
    private var sumBalWeight = 0.0

    private val _power3sW = MutableStateFlow(Double.NaN)
    private val _npW = MutableStateFlow(Double.NaN)
    private val _balanceAvgRightPct = MutableStateFlow(Double.NaN)
    val power3sW: StateFlow<Double> = _power3sW.asStateFlow()
    val npW: StateFlow<Double> = _npW.asStateFlow()
    /** Session-average right-side balance (%), gated on recording; NaN until the first valid sample. */
    val balanceAvgRightPct: StateFlow<Double> = _balanceAvgRightPct.asStateFlow()

    /** One sample/second. [recording] gates NP + avg-balance accumulation (3s smooths always).
     *  A NaN power sample (sensor dropout) skips the whole tick. The balance average is power-weighted,
     *  so a NaN [balanceRightPct] (coasting / no dynamics page) or a 0/NaN power second simply adds no
     *  weight — it can't skew the average toward soft-pedaling balance. */
    fun tick(instantW: Double, balanceRightPct: Double, recording: Boolean) {
        if (instantW.isNaN()) return
        _power3sW.value = ma3s.add(instantW)      // live smoothing — always
        if (recording) {                          // session aggregates — only while recording
            np.add(instantW)
            _npW.value = np.value
            if (!balanceRightPct.isNaN() && instantW > 0.0) {
                sumBalWeighted += balanceRightPct * instantW
                sumBalWeight += instantW
                _balanceAvgRightPct.value = sumBalWeighted / sumBalWeight
            }
        }
    }

    /** New activity / fresh start. */
    fun reset() {
        ma3s.reset(); np.reset()
        sumBalWeighted = 0.0; sumBalWeight = 0.0
        _power3sW.value = Double.NaN; _npW.value = Double.NaN; _balanceAvgRightPct.value = Double.NaN
    }
}

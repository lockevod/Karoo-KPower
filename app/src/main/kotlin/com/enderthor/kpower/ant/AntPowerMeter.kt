package com.enderthor.kpower.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Reads ONE ANT+ bike power meter by device number. ANT+ is broadcast, so this coexists with
 * the Karoo's native sensor. Latest values are exposed as StateFlows (NaN until first sample).
 */
class AntPowerMeter(
    private val context: Context,
    val deviceNumber: Int,
) {
    private val _power = MutableStateFlow(Double.NaN)
    private val _cadence = MutableStateFlow(Double.NaN)
    private val _balanceRightPct = MutableStateFlow(Double.NaN)
    private val _torque = MutableStateFlow(Double.NaN)
    val power: StateFlow<Double> = _power.asStateFlow()
    val cadence: StateFlow<Double> = _cadence.asStateFlow()
    val balanceRightPct: StateFlow<Double> = _balanceRightPct.asStateFlow()
    val torque: StateFlow<Double> = _torque.asStateFlow()

    @Volatile private var pcc: AntPlusBikePowerPcc? = null
    @Volatile private var releaseHandle: PccReleaseHandle<AntPlusBikePowerPcc>? = null

    /** Timestamp (ms) of the most recent ANT event; 0 until the first sample arrives. */
    @Volatile private var lastEventMs: Long = 0L

    /** Bounded retry counter for transient access failures (radio contention). */
    @Volatile private var attempts = 0

    fun connect() {
        releaseHandle = AntPlusBikePowerPcc.requestAccess(
            context,
            deviceNumber,
            0,
            { result, code, _ ->
                if (code == RequestAccessResult.SUCCESS && result != null) {
                    attempts = 0
                    pcc = result
                    subscribe(result)
                } else {
                    Timber.w("ANT power #%d access: %s", deviceNumber, code)
                    maybeRetry(code)
                }
            },
            { state -> if (state == DeviceState.DEAD) reset() },
        )
    }

    /**
     * Retry on transient access failures (CHANNEL_NOT_AVAILABLE / SEARCH timeouts caused by radio
     * contention). Bounded to 3 attempts. Do NOT retry on terminal results
     * (DEPENDENCY_NOT_INSTALLED / USER_CANCELLED / DEVICE_ALREADY_IN_USE). The ANT plugin callback
     * fires on its own thread; an immediate re-requestAccess from here is acceptable and leak-free.
     */
    private fun maybeRetry(code: RequestAccessResult?) {
        val transient = code == RequestAccessResult.CHANNEL_NOT_AVAILABLE ||
            code == RequestAccessResult.SEARCH_TIMEOUT ||
            code == RequestAccessResult.OTHER_FAILURE
        if (transient && attempts < 3) {
            attempts++
            Timber.d("ANT power #%d retry %d after %s", deviceNumber, attempts, code)
            connect()
        }
    }

    private fun subscribe(p: AntPlusBikePowerPcc) {
        p.subscribeCalculatedPowerEvent { _, _, _, calculatedPower ->
            _power.value = calculatedPower.toDouble()
            lastEventMs = System.currentTimeMillis()
        }
        p.subscribeCalculatedTorqueEvent { _, _, _, calculatedTorque ->
            _torque.value = calculatedTorque.toDouble()
            lastEventMs = System.currentTimeMillis()
        }
        p.subscribeCalculatedCrankCadenceEvent { _, _, _, calculatedCadence ->
            _cadence.value = calculatedCadence.toDouble()
            lastEventMs = System.currentTimeMillis()
        }
        p.subscribePedalPowerBalanceEvent { _, _, rightPedalIndicator, pedalPowerPercentage ->
            _balanceRightPct.value = if (rightPedalIndicator) pedalPowerPercentage.toDouble() else Double.NaN
            lastEventMs = System.currentTimeMillis()
        }
    }

    /** Reset values to NaN if no ANT event arrived within [staleMs] (silent dropout w/o DEAD). */
    fun expireIfStale(nowMs: Long, staleMs: Long = 5_000L) {
        if (lastEventMs != 0L && nowMs - lastEventMs > staleMs) reset()
    }

    private fun reset() {
        _power.value = Double.NaN; _cadence.value = Double.NaN
        _balanceRightPct.value = Double.NaN; _torque.value = Double.NaN
    }

    fun disconnect() {
        runCatching { releaseHandle?.close() }
        releaseHandle = null; pcc = null; reset()
    }
}

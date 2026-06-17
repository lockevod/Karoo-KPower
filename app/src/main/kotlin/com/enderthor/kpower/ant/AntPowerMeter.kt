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

    fun connect() {
        releaseHandle = AntPlusBikePowerPcc.requestAccess(
            context,
            deviceNumber,
            0,
            { result, code, _ ->
                if (code == RequestAccessResult.SUCCESS && result != null) {
                    pcc = result
                    subscribe(result)
                } else {
                    Timber.w("ANT power #%d access: %s", deviceNumber, code)
                }
            },
            { state -> if (state == DeviceState.DEAD) reset() },
        )
    }

    private fun subscribe(p: AntPlusBikePowerPcc) {
        p.subscribeCalculatedPowerEvent { _, _, _, calculatedPower ->
            _power.value = calculatedPower.toDouble()
        }
        p.subscribeCalculatedTorqueEvent { _, _, _, calculatedTorque ->
            _torque.value = calculatedTorque.toDouble()
        }
        p.subscribeCalculatedCrankCadenceEvent { _, _, _, calculatedCadence ->
            _cadence.value = calculatedCadence.toDouble()
        }
        p.subscribePedalPowerBalanceEvent { _, _, rightPedalIndicator, pedalPowerPercentage ->
            _balanceRightPct.value = if (rightPedalIndicator) pedalPowerPercentage.toDouble() else Double.NaN
        }
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

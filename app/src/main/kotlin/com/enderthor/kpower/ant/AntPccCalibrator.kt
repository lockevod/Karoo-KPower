package com.enderthor.kpower.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pcc.defines.RequestStatus
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.AntPlusCommonPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Outcome of a manual zero-offset calibration over the ANT+ PCC. */
sealed interface CalibrationResult {
    /** The meter reported success; [zeroOffset] is its calibration value if it sent one. */
    data class Success(val zeroOffset: Int?) : CalibrationResult
    /** The meter reported a calibration failure. */
    data object Failed : CalibrationResult
    /** Couldn't reach/command the meter (asleep, in use, no permission). */
    data class Unreachable(val reason: String) : CalibrationResult
    /** No response within the time budget. */
    data object Timeout : CalibrationResult
}

/**
 * Run a manual (zero-offset) calibration on an ANT+ power meter via antpluginlib's BikePower PCC —
 * the standard path (same library the Karoo uses). The rider should keep the cranks still (not
 * pedalling) during calibration. Opens a brief PCC connection, sends the request, waits for the
 * meter's calibration message, and always releases. Do NOT call while recording (the raw channel is
 * reading the same meter).
 */
suspend fun calibrateMeterViaPcc(context: Context, deviceNumber: Int): CalibrationResult {
    var handle: PccReleaseHandle<AntPlusBikePowerPcc>? = null
    return try {
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<CalibrationResult> { cont ->
                // Single-shot: several antpluginlib callbacks (request-finished, calibration message,
                // access failure) can race on different binder threads; resume EXACTLY once.
                val finished = AtomicBoolean(false)
                fun finish(r: CalibrationResult) { if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(r) }
                try {
                handle = AntPlusBikePowerPcc.requestAccess(
                    context.applicationContext,
                    deviceNumber,
                    0,
                    object : AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikePowerPcc> {
                        override fun onResultReceived(
                            pcc: AntPlusBikePowerPcc?,
                            result: RequestAccessResult?,
                            initialDeviceState: DeviceState?,
                        ) {
                            if (pcc == null || result != RequestAccessResult.SUCCESS) {
                                Timber.d("PCC calibrate #%d access=%s", deviceNumber, result)
                                finish(CalibrationResult.Unreachable(result?.toString() ?: "no device"))
                                return
                            }
                            // The meter's calibration RESPONSE can arrive via the GENERAL calibration
                            // event rather than the per-request receiver of the 3-arg overload (observed:
                            // request status=SUCCESS but the 3-arg receiver never fired → Timeout). So we
                            // subscribe to BOTH and finish on whichever delivers it. Log every message.
                            val onCalMsg: (AntPlusBikePowerPcc.CalibrationMessage) -> Unit = { msg ->
                                Timber.d("PCC calibrate #%d msg id=%s data=%s ctf=%s", deviceNumber, msg.calibrationId, msg.calibrationData, msg.ctfOffset)
                                when (msg.calibrationId) {
                                    AntPlusBikePowerPcc.CalibrationId.GENERAL_CALIBRATION_SUCCESS ->
                                        finish(CalibrationResult.Success(msg.calibrationData))
                                    AntPlusBikePowerPcc.CalibrationId.CTF_ZERO_OFFSET ->
                                        finish(CalibrationResult.Success(msg.ctfOffset ?: msg.calibrationData))
                                    AntPlusBikePowerPcc.CalibrationId.GENERAL_CALIBRATION_FAIL ->
                                        finish(CalibrationResult.Failed)
                                    else -> { /* CAPABILITIES / custom / other — keep waiting */ }
                                }
                            }
                            pcc.subscribeCalibrationMessageEvent { _, _, msg -> onCalMsg(msg) }
                            // Use the 3-arg overload so the response is ALSO routed to our per-request
                            // receiver. The request-finished callback only reports whether the command
                            // could be SENT.
                            val started = pcc.requestManualCalibration(
                                AntPlusCommonPcc.IRequestFinishedReceiver { status ->
                                    Timber.d("PCC calibrate #%d request status=%s", deviceNumber, status)
                                    if (status != RequestStatus.SUCCESS)
                                        finish(CalibrationResult.Unreachable(status?.toString() ?: "request failed"))
                                },
                                AntPlusBikePowerPcc.ICalibrationMessageReceiver { _, _, msg -> onCalMsg(msg) },
                                AntPlusBikePowerPcc.IMeasurementOutputDataReceiver { _, _, _, _, _, _ -> },
                            )
                            Timber.d("PCC calibrate #%d requestManualCalibration started=%b", deviceNumber, started)
                            if (!started) finish(CalibrationResult.Unreachable("request not started"))
                        }
                    },
                    object : AntPluginPcc.IDeviceStateChangeReceiver {
                        override fun onDeviceStateChange(newDeviceState: DeviceState?) {}
                    },
                )
                } catch (e: Throwable) {
                    // requestAccess can throw synchronously if the ANT+ Plugins Service is missing.
                    Timber.w(e, "PCC calibrate #%d requestAccess failed", deviceNumber)
                    finish(CalibrationResult.Unreachable(e.message ?: "error"))
                }
                cont.invokeOnCancellation { runCatching { handle?.close() } }
            }
        } ?: CalibrationResult.Timeout
    } finally {
        runCatching { handle?.close() }
    }
}

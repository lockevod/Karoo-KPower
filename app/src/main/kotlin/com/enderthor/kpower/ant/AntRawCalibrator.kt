package com.enderthor.kpower.ant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Outcome of a manual zero-offset calibration over a raw ANT+ channel. */
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

// ANT+ Bicycle Power calibration page (0x01) response ids (byte 1), confirmed against the Karoo's own
// rxantplus PowerCalibrationResponses: 172=success, 175=fail, 16=CTF, 18=auto-zero.
private const val CAL_SUCCESS = 172   // 0xAC
private const val CAL_FAIL = 175      // 0xAF
private const val CAL_CTF = 16        // 0x10
private const val CAL_AUTOZERO = 18   // 0x12

/**
 * Run a manual (zero-offset) calibration on an ANT+ power meter over a RAW bidirectional channel —
 * the same way the Karoo does it (page 0x01, id 0xAA), with NO antpluginlib. The PCC path needed the
 * ANT+ Plugins Service to grab the radio while our own raw channel (and the Karoo's sensors) were
 * holding it, which is why calibration used to be flaky ("a veces va, a veces no"). Doing it on a raw
 * channel of our own removes that cross-stack contention entirely.
 *
 * The rider should keep the cranks still during calibration. Opens a short-lived channel bound to the
 * device, re-sends the manual-calibration request every [RETRY_MS] until the meter answers on page
 * 0x01 (or the budget runs out), and always tears the channel down.
 *
 * Do NOT call while the same meter is being recorded (its raw channel is already reading it); the
 * caller stops the scan / frees the radio first.
 */
suspend fun calibrateMeterRaw(context: Context, deviceNumber: Int): CalibrationResult {
    val helperScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var channel: RawAntChannel? = null
    return try {
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<CalibrationResult> { cont ->
                val finished = AtomicBoolean(false)
                fun finish(r: CalibrationResult) {
                    if (finished.compareAndSet(false, true) && cont.isActive) cont.resume(r)
                }

                val ch = RawAntChannel(
                    context = context,
                    deviceNumber = deviceNumber,
                    onPayload = { p ->
                        if (p.size >= 8 && (p[0].toInt() and 0xFF) == 0x01) {
                            when (p[1].toInt() and 0xFF) {
                                CAL_SUCCESS, CAL_CTF -> finish(CalibrationResult.Success(signedOffset(p)))
                                CAL_AUTOZERO -> finish(CalibrationResult.Success(signedOffset(p)))
                                CAL_FAIL -> finish(CalibrationResult.Failed)
                            }
                        }
                    },
                    // No identity requests during calibration — we only want the calibration exchange.
                    identityPages = emptyList(),
                    // Once the channel is actually tracking the meter, send the first request.
                    onFirstPage = {
                        Timber.d("raw calibrate #%d: channel tracking, sending manual-cal request", deviceNumber)
                    },
                )
                channel = ch
                ch.start()

                // Re-send the request until the meter answers (a single acknowledged frame can be missed).
                helperScope.launch {
                    while (isActive && !finished.get()) {
                        ch.sendAcknowledged(AntPlusRequests.manualCalibration())
                        delay(RETRY_MS)
                    }
                }
                cont.invokeOnCancellation { runCatching { ch.stop() } }
            }
        } ?: CalibrationResult.Timeout
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e   // honour cooperative cancellation — don't report a cancelled calibration as "unreachable"
    } catch (e: Throwable) {
        Timber.w(e, "raw calibrate #%d failed", deviceNumber)
        CalibrationResult.Unreachable(e.message ?: "error")
    } finally {
        runCatching { channel?.stop() }
        helperScope.cancel()
    }
}

/** Signed 16-bit calibration value: byte 6 (MSB) · byte 7 (LSB), per the ANT+ Bike Power spec. */
private fun signedOffset(p: ByteArray): Int =
    (((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)).toShort().toInt()

private const val TIMEOUT_MS = 20_000L
private const val RETRY_MS = 1_500L

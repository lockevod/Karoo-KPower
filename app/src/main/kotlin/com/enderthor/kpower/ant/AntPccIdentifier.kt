package com.enderthor.kpower.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.AntPlusBikePowerPcc
import com.dsi.ant.plugins.antplus.pcc.defines.BatteryStatus
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceState
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.AntPluginPcc
import com.dsi.ant.plugins.antplus.pccbase.AntPlusCommonPcc
import com.dsi.ant.plugins.antplus.pccbase.PccReleaseHandle
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume

/** antpluginlib PCC BatteryStatus -> our 1..5 code (matches the ANT+ 0x52 levels; INVALID/unknown -> null). */
private fun pccBatteryCode(s: BatteryStatus?): Int? = when (s) {
    BatteryStatus.NEW -> 1
    BatteryStatus.GOOD -> 2
    BatteryStatus.OK -> 3
    BatteryStatus.LOW -> 4
    BatteryStatus.CRITICAL -> 5
    else -> null
}

/**
 * Identify an ANT+ power meter the way the Karoo does: via antpluginlib's BikePower PCC — a bidirectional
 * plugin connection through the ANT+ Plugins Service that surfaces the manufacturer/model and battery
 * shortly after connecting. This is the SAME standard library the Karoo uses, and it's why the Karoo can
 * show the name quickly: it does NOT wait for the slow background 0x50 page that a passive
 * SLAVE_RECEIVE_ONLY raw channel only hears every ~20-30 s.
 *
 * RETURNS the compact name (model→brand) as soon as the manufacturer page arrives, or null on access
 * failure / no decodable name. Resuming on the name (not also battery) keeps naming fast and releases
 * the PCC promptly — important because several of these run concurrently under a Semaphore. [onBattery]
 * is BEST-EFFORT: we request the battery page too and seed it if it lands before we resume; a meter
 * that doesn't send battery quickly simply won't seed here (battery is also read in-ride + from
 * SavedDevices). The PCC multiplexes on the ANT+ Plugins Service alongside a running MultiDeviceSearch
 * (this is how the Karoo resolves names while it keeps scanning). Always wrap in withTimeoutOrNull; the
 * PCC is always released.
 */
suspend fun identifyMeterViaPcc(
    context: Context,
    deviceNumber: Int,
    onBattery: (Int?) -> Unit,
): String? {
    var handle: PccReleaseHandle<AntPlusBikePowerPcc>? = null
    return try {
        suspendCancellableCoroutine<String?> { cont ->
            // Resume with the name (or null) exactly once. isActive guards the idempotent callbacks.
            fun resume(name: String?) { if (cont.isActive) cont.resume(name) }
            try {
                handle = AntPlusBikePowerPcc.requestAccess(
                    context.applicationContext,
                    deviceNumber,
                    0, // proximity search threshold: 0 = none
                    object : AntPluginPcc.IPluginAccessResultReceiver<AntPlusBikePowerPcc> {
                        override fun onResultReceived(
                            pcc: AntPlusBikePowerPcc?,
                            result: RequestAccessResult?,
                            initialDeviceState: DeviceState?,
                        ) {
                            if (pcc == null || result != RequestAccessResult.SUCCESS) {
                                Timber.d("PCC identify #%d access=%s", deviceNumber, result)
                                resume(null)
                                return
                            }
                            // Battery first (best-effort seed) so it has a chance to land before the
                            // manufacturer page resumes us.
                            pcc.subscribeBatteryStatusEvent { _, _, _, _, status, _, _, _ ->
                                onBattery(pccBatteryCode(status))
                            }
                            pcc.subscribeProductInformationEvent { _, _, swRevision, _, serialNumber ->
                                Timber.d("PCC identify #%d sw=%d serial=%d", deviceNumber, swRevision, serialNumber)
                            }
                            // Common-page events (inherited from AntPlusCommonPcc) arrive SEPARATELY from
                            // power/cadence. Params: (estTimestamp, eventFlags, hardwareRevision,
                            // manufacturerID, modelNumber) — the brand+model are the LAST two, not the 3rd.
                            pcc.subscribeManufacturerIdentificationEvent { _, _, _, manufacturerID, modelNumber ->
                                Timber.d("PCC identify #%d mfg=%d model=%d", deviceNumber, manufacturerID, modelNumber)
                                // COMPACT name (model, else brand). null (unknown brand) → caller shows #id.
                                resume(antDeviceShortName(manufacturerID, modelNumber))
                            }
                            // ACTIVELY request the common pages so the meter sends them within ~1 s instead
                            // of waiting ~20-30 s for the periodic broadcast. Battery requested first.
                            runCatching {
                                pcc.requestCommonDataPage(AntPlusCommonPcc.CommonDataPage.BATTERY_STATUS) { }
                                pcc.requestCommonDataPage(AntPlusCommonPcc.CommonDataPage.MANUFACTURER_IDENTIFICATION) { rc ->
                                    Timber.d("PCC identify #%d req MANUFACTURER=%s", deviceNumber, rc)
                                }
                                pcc.requestCommonDataPage(AntPlusCommonPcc.CommonDataPage.PRODUCT_INFORMATION) { rc ->
                                    Timber.d("PCC identify #%d req PRODUCT=%s", deviceNumber, rc)
                                }
                            }
                        }
                    },
                    object : AntPluginPcc.IDeviceStateChangeReceiver {
                        override fun onDeviceStateChange(newDeviceState: DeviceState?) {}
                    },
                )
            } catch (e: Throwable) {
                // requestAccess can throw synchronously if the ANT+ Plugins Service is missing/unbindable.
                Timber.w(e, "PCC identify #%d requestAccess failed", deviceNumber)
                resume(null)
            }
            cont.invokeOnCancellation { runCatching { handle?.close() } }
        }
    } finally {
        runCatching { handle?.close() }
    }
}

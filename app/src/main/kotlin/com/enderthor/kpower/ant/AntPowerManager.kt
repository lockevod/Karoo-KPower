package com.enderthor.kpower.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.MultiDeviceSearch
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceType
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.MultiDeviceSearch.MultiDeviceSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.EnumSet

/** Owns the ANT+ power-meter scan and the connected per-device readers. */
class AntPowerManager(private val context: Context) {

    private val _detectedDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val detectedDevices: StateFlow<List<AntDeviceInfo>> = _detectedDevices.asStateFlow()

    private var search: MultiDeviceSearch? = null
    private val meters = LinkedHashMap<Int, AntPowerMeter>()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val powerFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val bridges = HashMap<Int, kotlinx.coroutines.Job>()

    /** Stable power flow for a device number (survives connect/disconnect; NaN when not streaming). */
    fun powerFlow(deviceNumber: Int): kotlinx.coroutines.flow.StateFlow<Double> =
        powerFlows.getOrPut(deviceNumber) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

    /** Live reader for a device number, or null if not connected. */
    fun meter(deviceNumber: Int): AntPowerMeter? = synchronized(meters) { meters[deviceNumber] }

    @Synchronized
    fun startScan() {
        stopScan()
        _detectedDevices.value = emptyList()
        search = MultiDeviceSearch(
            context,
            EnumSet.of(DeviceType.BIKE_POWER),
            object : MultiDeviceSearch.SearchCallbacks {
                override fun onSearchStarted(rssiSupport: MultiDeviceSearch.RssiSupport?) {}
                override fun onDeviceFound(result: MultiDeviceSearchResult?) {
                    result ?: return
                    val info = AntDeviceInfo(
                        name = result.deviceDisplayName ?: "Power #${result.antDeviceNumber}",
                        deviceNumber = result.antDeviceNumber,
                    )
                    if (_detectedDevices.value.none { it.deviceNumber == info.deviceNumber }) {
                        _detectedDevices.value = _detectedDevices.value + info
                    }
                }
                override fun onSearchStopped(reason: RequestAccessResult?) {
                    Timber.d("ANT scan stopped: %s", reason)
                }
            },
        )
    }

    @Synchronized
    fun stopScan() {
        runCatching { search?.close() }
        search = null
    }

    /** Connect readers for exactly these device numbers; disconnect any others. */
    @Synchronized
    fun connectMeters(deviceNumbers: List<Int>) {
        synchronized(meters) {
            (meters.keys - deviceNumbers.toSet()).toList().forEach { dn ->
                bridges.remove(dn)?.cancel()
                powerFlows[dn]?.value = Double.NaN
                meters.remove(dn)?.disconnect()
            }
            deviceNumbers.forEach { dn ->
                if (!meters.containsKey(dn)) {
                    val m = AntPowerMeter(context, dn).also { it.connect() }
                    meters[dn] = m
                    val sink = powerFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
                    bridges[dn] = scope.launch {
                        // mirror power into the stable sink
                        launch { m.power.collect { sink.value = it } }
                        // watchdog: expire stale values (no event for >5s) so the FIT records a
                        // gap, not frozen watts. Both child launches live under this one
                        // bridges[dn] job, so cancelling it stops the mirror and the watchdog.
                        while (isActive) {
                            kotlinx.coroutines.delay(1_000)
                            m.expireIfStale(System.currentTimeMillis())
                            sink.value = m.power.value
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun disconnectAll() {
        stopScan()
        synchronized(meters) {
            bridges.values.forEach { it.cancel() }
            bridges.clear()
            powerFlows.values.forEach { it.value = Double.NaN }
            meters.values.forEach { it.disconnect() }
            meters.clear()
        }
    }
}

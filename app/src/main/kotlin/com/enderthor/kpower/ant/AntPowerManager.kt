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
    private val power3sFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val npFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val avgFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val bridges = HashMap<Int, kotlinx.coroutines.Job>()

    // Ride state mirrored from the extension. `recording` gates NP/avg accumulation per meter;
    // `pendingReset` requests are pushed to each meter (consumed on its single-threaded loop).
    @Volatile private var recording = false

    /**
     * Mirror the Karoo RideState so each meter's metrics track NP/avg only while recording and
     * reset on the Idle->Recording transition. Reset is requested per-meter (requestMetricsReset)
     * and consumed on the per-meter 1Hz loop, so metrics.reset()/tick() stay on one thread.
     */
    @Synchronized
    fun onRideState(state: io.hammerhead.karooext.models.RideState) {
        when (state) {
            is io.hammerhead.karooext.models.RideState.Recording -> {
                if (!recording) synchronized(meters) { meters.values.forEach { it.requestMetricsReset() } }
                recording = true
            }
            else -> recording = false
        }
    }

    /** Stable power flow for a device number (survives connect/disconnect; NaN when not streaming). */
    fun powerFlow(deviceNumber: Int): kotlinx.coroutines.flow.StateFlow<Double> =
        powerFlows.getOrPut(deviceNumber) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

    /** Stable 3s/NP/avg flows for a device number (survive reconnect, like powerFlow). */
    fun power3sFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = power3sFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun npFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = npFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun avgFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = avgFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

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
                power3sFlows[dn]?.value = Double.NaN
                npFlows[dn]?.value = Double.NaN
                avgFlows[dn]?.value = Double.NaN
                meters.remove(dn)?.disconnect()
            }
            deviceNumbers.forEach { dn ->
                if (!meters.containsKey(dn)) {
                    val m = AntPowerMeter(context, dn).also { it.connect() }
                    // A meter added mid-recording starts its metrics fresh on the first loop tick.
                    m.requestMetricsReset()
                    meters[dn] = m
                    val sink = powerFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
                    val p3sSink = power3sFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
                    val npSink = npFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
                    val avgSink = avgFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
                    bridges[dn] = scope.launch {
                        // mirror power into the stable sink
                        launch { m.power.collect { sink.value = it } }
                        // watchdog: expire stale values (no event for >5s) so the FIT records a
                        // gap, not frozen watts. Both child launches live under this one
                        // bridges[dn] job, so cancelling it stops the mirror and the watchdog.
                        // This single loop also drives the per-meter metrics: reset + tick run
                        // here only, so PowerSourceMetrics stays single-threaded.
                        while (isActive) {
                            kotlinx.coroutines.delay(1_000)
                            m.expireIfStale(System.currentTimeMillis())
                            if (m.consumePendingReset()) m.metrics.reset()
                            m.metrics.tick(m.power.value, recording)
                            // 3s is a live rolling average: blank it when power is stale (NaN) so the
                            // field goes `---` on a dropout instead of freezing. NP/avg are session
                            // aggregates and hold their last accumulated value.
                            p3sSink.value = if (m.power.value.isNaN()) Double.NaN else m.metrics.power3sW.value
                            npSink.value = m.metrics.npW.value
                            avgSink.value = m.metrics.avgW.value
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
            power3sFlows.values.forEach { it.value = Double.NaN }
            npFlows.values.forEach { it.value = Double.NaN }
            avgFlows.values.forEach { it.value = Double.NaN }
            meters.values.forEach { it.disconnect() }
            meters.clear()
        }
    }
}

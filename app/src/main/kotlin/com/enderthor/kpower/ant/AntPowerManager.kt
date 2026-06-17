package com.enderthor.kpower.ant

import android.content.Context
import com.dsi.ant.plugins.antplus.pcc.MultiDeviceSearch
import com.dsi.ant.plugins.antplus.pcc.defines.DeviceType
import com.dsi.ant.plugins.antplus.pcc.defines.RequestAccessResult
import com.dsi.ant.plugins.antplus.pccbase.MultiDeviceSearch.MultiDeviceSearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.EnumSet

/** Owns the ANT+ power-meter scan and the connected per-device readers. */
class AntPowerManager(private val context: Context) {

    private val _detectedDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val detectedDevices: StateFlow<List<AntDeviceInfo>> = _detectedDevices.asStateFlow()

    private var search: MultiDeviceSearch? = null
    private val meters = LinkedHashMap<Int, AntPowerMeter>()

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
            (meters.keys - deviceNumbers.toSet()).toList().forEach { meters.remove(it)?.disconnect() }
            deviceNumbers.forEach { dn ->
                if (!meters.containsKey(dn)) {
                    meters[dn] = AntPowerMeter(context, dn).also { it.connect() }
                }
            }
        }
    }

    @Synchronized
    fun disconnectAll() {
        stopScan()
        synchronized(meters) { meters.values.forEach { it.disconnect() }; meters.clear() }
    }
}

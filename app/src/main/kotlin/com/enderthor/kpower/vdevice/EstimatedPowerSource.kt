package com.enderthor.kpower.vdevice

import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import timber.log.Timber
import com.enderthor.kpower.BuildConfig

class EstimatedPowerSource(
    extension: String,
    private val hr: Int,
    private val engine: PowerEstimationEngine,
) {
    val source by lazy {
        Device(extension, "estimated-power-$hr", listOf(DataType.Source.POWER), "KPW Estim.")
    }

    @Volatile private var activeScope: CoroutineScope? = null

    fun connect(emitter: Emitter<DeviceEvent>, extension: String) {
        Timber.d("Init Connect Power Estimator")
        activeScope?.cancel()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        activeScope = scope
        val token = this
        engine.acquire(token)
        engine.setVirtualDeviceConnected(token, true)

        scope.launch {
            try {
                emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                delay(2000)
                emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                delay(1000)
                emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
                delay(1000)
                // Readable identity in the Karoo pairing screen (was a meaningless "1234 / POWER-EXT-1").
                emitter.onNext(OnManufacturerInfo(ManufacturerInfo("KPower", "Estimate", "KPW-EST")))

                engine.powerEmaW
                    .filter { !it.isNaN() }
                    .collect { powerValue ->
                        emitter.onNext(
                            OnDataPoint(
                                DataPoint(
                                    source.dataTypes.first(),
                                    values = mapOf(DataType.Field.POWER to powerValue),
                                    sourceId = source.uid,
                                )
                            )
                        )
                    }
            } catch (e: CancellationException) {
                if (BuildConfig.DEBUG) Timber.w("Connect coroutine was cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Error in connect function")
                emitter.onError(e)
            }
        }

        emitter.setCancellable {
            if (BuildConfig.DEBUG) Timber.w("Stopping connect coroutine")
            engine.release(token)
            engine.setVirtualDeviceConnected(token, false)
            scope.cancel()
            if (activeScope === scope) activeScope = null
        }
    }

    companion object {
        fun buildDevice(extension: String, hr: Int, engine: PowerEstimationEngine): EstimatedPowerSource =
            EstimatedPowerSource(extension, hr, engine)

        fun fromUid(extension: String, uid: String, engine: PowerEstimationEngine): EstimatedPowerSource? =
            uid.substringAfterLast("-").toIntOrNull()?.let {
                EstimatedPowerSource(extension, it, engine)
            }
    }
}

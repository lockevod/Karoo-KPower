package com.enderthor.kpower.vdevice

import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*
import timber.log.Timber
import com.enderthor.kpower.BuildConfig

class RealPowerSource(
    extension: String,
    private val deviceNumber: Int,
    private val label: String,
    private val antManager: com.enderthor.kpower.ant.AntPowerManager,
) {
    val source by lazy {
        Device(
            extension,
            "real-power-$deviceNumber",
            listOf(DataType.Source.POWER, DataType.Source.CADENCE),
            label,
        )
    }

    @Volatile private var activeScope: CoroutineScope? = null

    fun connect(emitter: Emitter<DeviceEvent>, extension: String) {
        Timber.d("Init Connect Real Power Source #%d", deviceNumber)
        activeScope?.cancel()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        activeScope = scope
        val token = this
        antManager.acquire(deviceNumber, token)

        scope.launch {
            try {
                emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                delay(2000)
                emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                delay(1000)
                emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
                delay(1000)
                emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Enderthor", "1234", "POWER-REAL-$deviceNumber")))

                // Power is the primary signal: it governs the CONNECTED/SEARCHING state.
                // When the meter drops out the watchdog blanks the value to NaN; we must
                // tell the Karoo the sensor is SEARCHING so it blanks the field instead of
                // holding the last watts. Connection-status transitions are driven ONLY from
                // this single power collector (cadence never writes `connected`) so there is
                // no shared-state race.
                var connected = true
                launch {
                    antManager.powerFlow(deviceNumber)
                        .collect { powerValue ->
                            if (powerValue.isNaN()) {
                                if (connected) {
                                    connected = false
                                    emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                                }
                            } else {
                                if (!connected) {
                                    connected = true
                                    emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                                }
                                emitter.onNext(
                                    OnDataPoint(
                                        DataPoint(
                                            DataType.Source.POWER,
                                            values = mapOf(DataType.Field.POWER to powerValue),
                                            sourceId = source.uid,
                                        )
                                    )
                                )
                            }
                        }
                }

                antManager.cadenceFlow(deviceNumber)
                    .collect { cadenceValue ->
                        if (!cadenceValue.isNaN()) {
                            emitter.onNext(
                                OnDataPoint(
                                    DataPoint(
                                        DataType.Source.CADENCE,
                                        values = mapOf(DataType.Field.CADENCE to cadenceValue),
                                        sourceId = source.uid,
                                    )
                                )
                            )
                        }
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
            antManager.release(deviceNumber, token)
            scope.cancel()
            if (activeScope === scope) activeScope = null
        }
    }

    companion object {
        fun buildDevice(
            extension: String,
            deviceNumber: Int,
            label: String,
            antManager: com.enderthor.kpower.ant.AntPowerManager,
        ): RealPowerSource =
            RealPowerSource(extension, deviceNumber, label, antManager)

        fun fromUid(
            extension: String,
            uid: String,
            antManager: com.enderthor.kpower.ant.AntPowerManager,
        ): RealPowerSource? =
            uid.substringAfterLast("-").toIntOrNull()?.let {
                RealPowerSource(extension, it, "Real Power #$it", antManager)
            }
    }
}

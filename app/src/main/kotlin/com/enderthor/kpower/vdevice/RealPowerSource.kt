package com.enderthor.kpower.vdevice

import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import timber.log.Timber
import com.enderthor.kpower.BuildConfig
import com.enderthor.kpower.ant.BatteryLevel
import com.enderthor.kpower.ant.batteryLevelOf

private sealed interface Sample {
    @JvmInline value class Power(val value: Double) : Sample
    @JvmInline value class Cadence(val value: Double) : Sample
    @JvmInline value class Battery(val code: Int?) : Sample
}

/** ANT+ battery status code (1=New..5=Critical) -> karoo-ext BatteryStatus, reusing [batteryLevelOf]
 *  so the LOW/CRITICAL thresholds live in ONE place. Unknown -> GOOD (don't alarm on the sensor card). */
private fun batteryStatusOf(code: Int?): BatteryStatus = when (batteryLevelOf(code)) {
    BatteryLevel.LOW -> BatteryStatus.LOW
    BatteryLevel.CRITICAL -> BatteryStatus.CRITICAL
    BatteryLevel.OK -> if (code == 1) BatteryStatus.NEW else BatteryStatus.GOOD
    BatteryLevel.UNKNOWN -> BatteryStatus.GOOD
}

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
        // NOTE: we do NOT acquire the raw ANT channel here. The channel's lifecycle is owned solely
        // by the ride-state gate in KpowerExtension (open only while the meter is ENABLED and the ride
        // is RECORDING). This source just reads the stable power/cadence sinks. Acquiring here would
        // (a) keep the radio + a channel open off-ride (battery) and (b) survive a meter delete until
        // the host unpairs, starving the ANT+ scan — both seen on hardware ("scan, add, delete, then
        // scan finds nothing"). Off-ride the sinks are NaN, so this source reports SEARCHING.

        scope.launch {
            try {
                emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                delay(2000)
                emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                delay(1000)
                // Initial battery from the last-known 0x52 code (real value once the meter has sent
                // one; GOOD until then). Live updates come through the merged collector below.
                emitter.onNext(OnBatteryStatus(batteryStatusOf(antManager.batteryFlow(deviceNumber).value)))
                delay(1000)
                // manufacturer=Enderthor, serial=the ANT device id, model="KPOWER".
                emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Enderthor", deviceNumber.toString(), "KPOWER")))

                // Power is the primary signal: it governs the CONNECTED/SEARCHING state.
                // When the meter drops out the watchdog blanks the value to NaN; we must
                // tell the Karoo the sensor is SEARCHING so it blanks the field instead of
                // holding the last watts. Power and cadence are merged into a SINGLE flow so
                // exactly one coroutine calls emitter.onNext (concurrent onNext on one
                // karoo-ext Emitter/AIDL handler is not guaranteed safe). Connection-status
                // transitions are driven ONLY by power within this single collector (cadence
                // never writes `connected`) so there is no shared-state race.
                var connected = true
                var lastBattery: Int? = antManager.batteryFlow(deviceNumber).value
                merge(
                    antManager.powerFlow(deviceNumber).map { Sample.Power(it) },
                    antManager.cadenceFlow(deviceNumber).map { Sample.Cadence(it) },
                    antManager.batteryFlow(deviceNumber).map { Sample.Battery(it) },
                ).collect { sample ->
                    when (sample) {
                        is Sample.Power -> {
                            val powerValue = sample.value
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
                        is Sample.Cadence -> {
                            val cadenceValue = sample.value
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
                        is Sample.Battery -> {
                            // Real battery from page 0x52; emit only on change (it's slow).
                            if (sample.code != null && sample.code != lastBattery) {
                                lastBattery = sample.code
                                emitter.onNext(OnBatteryStatus(batteryStatusOf(sample.code)))
                            }
                        }
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
    }
}

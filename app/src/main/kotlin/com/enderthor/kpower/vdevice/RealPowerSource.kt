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
        // NOTE: we do NOT acquire the raw ANT channel here. The channel's lifecycle is owned solely by
        // the gate in KpowerExtension: open whenever a saved meter is ENABLED and the meter-management
        // screen isn't using the radio (NOT gated on ride state — like the Karoo, the meter is live in
        // the native Sensors screen off-ride too). This source just reads the stable power/cadence sinks.
        // Acquiring here (tying the channel to the Karoo's SUBSCRIPTION, the way the Karoo itself does)
        // was tried and reverted: the host's subscription to our virtual device SURVIVES a meter delete,
        // so the channel stayed open until the host unpaired, starving the ANT+ scan ("scan, add, delete,
        // then scan finds nothing"). The enabled-gate closes the channel on disable/delete instead.
        // Trade-off vs the Karoo: the channel can stay open off-ride with no subscriber (battery), bounded
        // by the 25 s search-timeout duty-cycle in RawAntChannel.

        scope.launch {
            try {
                // Start SEARCHING and STAY there until a REAL power sample arrives. Do NOT emit a
                // premature CONNECTED here: with no meter (or one out of range) the channel never opens,
                // powerFlow stays NaN, and the host rebinds often — an unconditional CONNECTED made the
                // field flicker Searching→Connected for a meter that isn't there. The merged collector
                // below flips to CONNECTED only on a non-NaN power value.
                emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                delay(1000)
                // Initial battery from the last-known 0x52 code (real value once the meter has sent
                // one; GOOD until then). Live updates come through the merged collector below.
                emitter.onNext(OnBatteryStatus(batteryStatusOf(antManager.batteryFlow(deviceNumber).value)))
                delay(1000)
                // Product Information: manufacturer=Enderthor, serial=the ANT device id, and model=the
                // real meter's FULL name (e.g. "KPOWER Garmin Rally 200") so the details screen shows what
                // it actually mirrors — the sensor-LIST name stays the short "KPW Rally 200". Falls back to
                // "KPOWER" until the 0x50 page has resolved the brand/model.
                val full = antManager.manufacturerFlow(deviceNumber).value
                val model = if (!full.isNullOrBlank()) "KPOWER $full" else "KPOWER"
                emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Enderthor", deviceNumber.toString(), model)))

                // Power is the primary signal: it governs the CONNECTED/SEARCHING state.
                // When the meter drops out the watchdog blanks the value to NaN; we must
                // tell the Karoo the sensor is SEARCHING so it blanks the field instead of
                // holding the last watts. Power and cadence are merged into a SINGLE flow so
                // exactly one coroutine calls emitter.onNext (concurrent onNext on one
                // karoo-ext Emitter/AIDL handler is not guaranteed safe). Connection-status
                // transitions are driven ONLY by power within this single collector (cadence
                // never writes `connected`) so there is no shared-state race.
                // Starts false (we emitted SEARCHING above): the first non-NaN power flips it to CONNECTED,
                // a NaN/dropout flips it back — so status is driven purely by real data, never a timer.
                var connected = false
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

package com.enderthor.kpower.vdevice

import android.content.Context

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.BatteryStatus
import io.hammerhead.karooext.models.ConnectionStatus
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.ManufacturerInfo
import io.hammerhead.karooext.models.OnBatteryStatus
import io.hammerhead.karooext.models.OnConnectionStatus
import io.hammerhead.karooext.models.OnDataPoint
import io.hammerhead.karooext.models.OnManufacturerInfo
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

import com.enderthor.kpower.activity.dataStore
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.RealKarooValues
import com.enderthor.kpower.data.defaultConfigData
import com.enderthor.kpower.screens.preferencesKey
import kotlinx.coroutines.FlowPreview


import timber.log.Timber


class EstimatedPowerSource(extension: String,  private val hr: Int ,private val karooSystem: KarooSystemService, private val context: Context)
{
    val source by lazy {
        Device(
            extension,
            "estimated-power-$hr",
            listOf(DataType.Source.POWER),
            "Estimated Power $hr Source",
        )
    }

    private val mutableState = MutableStateFlow(RealKarooValues())
    val state: StateFlow<RealKarooValues> = mutableState.asStateFlow()
    private val _powerConfigsFlow = MutableStateFlow<List<ConfigData>>(emptyList())
    val powerConfigsFlow: StateFlow<List<ConfigData>> = _powerConfigsFlow.asStateFlow()


    /**
     * Connect and start feeding [DeviceEvent]
     *
     * @see [DeviceEvent]
     */
    @OptIn(FlowPreview::class)
    fun connect(emitter: Emitter<DeviceEvent>, extension: String) {

        Timber.d("Init Connect Power Estimator")
        val job = CoroutineScope(Dispatchers.IO).launch {
            // 2s searching
            emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
            delay(2000)
            // Update device is now connected
            emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
            delay(1000)
            // Update battery status
            emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
            delay(1000)
            // Send manufacturer info
            emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Enderthor", "1234", "POWER-EXT-1")))
            delay(1000)

            try {
                val preferences = context.dataStore.data.first()
                val entries = Json.decodeFromString<MutableList<ConfigData>>(
                    preferences[preferencesKey] ?: defaultConfigData
                )
                _powerConfigsFlow.value = entries

                Timber.d("Preferences loaded in EstimatedPowerSource")
            } catch(e: Throwable){
                Timber.tag("kpower").e(e, "Failed to read preferences")
            }

            // Load preferences data from Karoo useprofile
            var userMass: Double = 0.0
            var factorMass: Double = 1.0
            var factorDistance: Double = 1.0
            var factorElevation: Double = 1.0


            karooSystem.connect { connected ->
                Timber.i("Karoo System connected=$connected")
            }
            karooSystem.addConsumer { user: UserProfile ->
                userMass = user.weight.toDouble()
                factorMass = if (user.preferredUnit.weight == UserProfile.PreferredUnit.UnitType.IMPERIAL) 0.453592 else 1.0
                factorDistance = if (user.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL) 1.60934 else 1.0
                factorElevation = if (user.preferredUnit.elevation == UserProfile.PreferredUnit.UnitType.IMPERIAL) 0.3048 else 1.0
            }

            // Start subscribe data from Karoo events

            karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.SPEED)) { event: OnStreamState ->
                mutableState.update { currentState -> currentState.copy(speed = event.state) }
            }
            karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.ELEVATION_GRADE)) { event: OnStreamState ->
                mutableState.update { currentState -> currentState.copy(slope = event.state) }
            }

            karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.PRESSURE_ELEVATION_CORRECTION)) { event: OnStreamState ->
                mutableState.update { currentState -> currentState.copy(elevation = event.state) }
            }

            karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.Type.CADENCE)) { event: OnStreamState ->
                mutableState.update { currentState -> currentState.copy(cadence = event.state) }
            }

            karooSystem.addConsumer(OnStreamState.StartStreaming(DataType.dataTypeId(extension, "powerheadwind"))) { event: OnStreamState ->
                mutableState.update { currentState -> currentState.copy(headwind = event.state) }
            }
            var cadence:Double
            var isforcepower:Boolean

            powerConfigsFlow.collect { powerconfigs ->
                repeat(Int.MAX_VALUE)
                {

                    var speed = if (state.value.speed is StreamState.Streaming ) {
                        (state.value.speed as StreamState.Streaming).dataPoint.singleValue
                            ?: 0.0
                    } else {
                        0.0

                    }

                    if (state.value.cadence is StreamState.Streaming ) {
                        isforcepower = powerconfigs[0].isforcepower
                        cadence= (state.value.cadence as StreamState.Streaming).dataPoint.singleValue ?: 0.0
                    }
                    else {
                        isforcepower = true
                        cadence = 0.0
                    }


                    var slope = if (state.value.slope is StreamState.Streaming) {
                        (state.value.slope as StreamState.Streaming).dataPoint.singleValue ?: 0.0
                    } else {
                        0.0
                    }

                    var elevation = if (state.value.elevation is StreamState.Streaming) {
                        (state.value.elevation as StreamState.Streaming).dataPoint.singleValue ?: 0.0
                    } else {
                        0.0
                    }

                    var finalHeadwind =
                       if (state.value.headwind is StreamState.Streaming)
                       {
                           (state.value.headwind as StreamState.Streaming).dataPoint.singleValue
                               ?: 0.0
                       }else {
                               0.0
                       }

                    Timber.d("Init data: Speed is $speed, Slope is $slope, Elevation is $elevation, Windspeed is $finalHeadwind")

                    val powerbike = CyclingWattageEstimator(
                        slope = slope / 100, // convert from percentage
                        totalMass = (userMass + powerconfigs[0].bikeMass.toDouble()) * factorMass, // in kg
                        rollingResistanceCoefficient = powerconfigs[0].rollingResistanceCoefficient.toDouble(),
                        dragCoefficient = powerconfigs[0].dragCoefficient.toDouble(),
                        speed = speed * factorDistance, // in m/s
                        elevation = elevation * factorElevation, // in m
                        windSpeed = finalHeadwind , // in m/s
                        powerLoss = powerconfigs[0].powerLoss.toDouble() / 100,
                        frontalArea = powerconfigs[0].frontalArea.toDouble(),
                        ftp = powerconfigs[0].ftp.toDouble(),
                        cadence = cadence,
                        surface = powerconfigs[0].surface.factor,
                        isforcepower = isforcepower
                    )
                    Timber.d("Out Estimated Power is ${powerbike.calculateCyclingWattage()}")
                    emitter.onNext(
                        OnDataPoint(
                            DataPoint(
                                source.dataTypes.first(),
                                values = mapOf(DataType.Field.POWER to powerbike.calculateCyclingWattage()),
                                sourceId = source.uid,
                            ),
                        ),
                    )
                    delay(2000)
                }
            }
            awaitCancellation()
        }
        emitter.setCancellable {
            job.cancel()
        }
    }

    companion object {
        fun fromUid(extension: String, uid: String, karooSystem: KarooSystemService, context: Context): EstimatedPowerSource? {
            return uid.substringAfterLast("-").toIntOrNull()?.let {
                EstimatedPowerSource(extension, it, karooSystem, context)
            }
        }
    }
}
package com.enderthor.kpower.vdevice

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.RealKarooValues
import com.enderthor.kpower.data.previewConfigData
import com.enderthor.kpower.extension.*


class EstimatedPowerSource(
    extension: String,
    private val hr: Int,
    private val karooSystem: KarooSystemService,
    private val context: Context
) {
    val source by lazy {
        Device(
            extension,
            "estimated-power-$hr",
            listOf(DataType.Source.POWER),
            "Estimated Power $hr Source"
        )
    }

    @OptIn(FlowPreview::class)
    fun connect(emitter: Emitter<DeviceEvent>, extension: String) {
        Timber.d("Init Connect Power Estimator")
        val scope = CoroutineScope(Dispatchers.IO)

        scope.launch {
            try {

                emitter.onNext(OnConnectionStatus(ConnectionStatus.SEARCHING))
                delay(2000)
                emitter.onNext(OnConnectionStatus(ConnectionStatus.CONNECTED))
                delay(1000)
                emitter.onNext(OnBatteryStatus(BatteryStatus.GOOD))
                delay(1000)
                emitter.onNext(OnManufacturerInfo(ManufacturerInfo("Enderthor", "1234", "POWER-EXT-1")))
                delay(1000)


                val userProfile = karooSystem.consumerFlow<UserProfile>().first()
                val (userMass, factorMass, factorDistance, factorElevation) = getUserProfileFactors(userProfile)

                // Convertir powerConfigFlow en un flujo estable
                val powerConfigFlow = context.loadPreferencesFlow()
                    .catch { e ->
                        Timber.e(e, "Error loading power configs")
                        emit(previewConfigData)
                    }
                    .stateIn(
                        scope = scope,
                        started = SharingStarted.Eagerly,
                        initialValue = previewConfigData
                    )

                combine(
                    karooSystem.streamDataMonitorFlow(DataType.Type.SPEED),
                    karooSystem.streamDataMonitorFlow(DataType.Type.ELEVATION_GRADE),
                    karooSystem.streamDataMonitorFlow(DataType.Type.PRESSURE_ELEVATION_CORRECTION),
                    karooSystem.streamDataMonitorFlow(DataType.Type.CADENCE,true),
                    karooSystem.headwindFlow(context),
                    powerConfigFlow
                ) { streams: Array<*> ->
                    Timber.d("Streams: ${streams.joinToString { it.toString() }}")

                    val speed = streams[0] as StreamState
                    val slope = streams[1] as StreamState
                    val elevation = streams[2] as StreamState
                    val cadence = streams[3] as StreamState
                    val headwind = streams[4] as StreamState

                    Timber.w(" Despues del FLOW COMBINE Speed: $speed, Slope: $slope, Elevation: $elevation, Cadence: $cadence, Headwind: $headwind")

                    @Suppress("UNCHECKED_CAST")
                    val configs = streams[5] as List<ConfigData>
                    val karooValues = RealKarooValues(
                        speed = speed,
                        slope = slope,
                        elevation = elevation,
                        cadence = cadence,
                        headwind = headwind
                    )
                    Pair(karooValues, configs)
                }.throttle(900L)
                    .collect { (karooValues, configs) ->
                        if (configs.isNotEmpty()) {
                            val powerBike = calculatePowerBike(
                                userMass,
                                factorMass,
                                factorDistance,
                                factorElevation,
                                configs,
                                karooValues
                            )

                            val powerValue = powerBike.calculateCyclingWattage()
                            emitter.onNext(
                                OnDataPoint(
                                    DataPoint(
                                        source.dataTypes.first(),
                                        values = mapOf(DataType.Field.POWER to powerValue),
                                        sourceId = source.uid
                                    )
                                )
                            )
                        }
                    }

                awaitCancellation()
            } catch (e: CancellationException) {
                Timber.w("Connect coroutine was cancelled")
            } catch (e: Exception) {
                Timber.e(e, "Error in connect function")
                emitter.onError(e)
            }
        }

        emitter.setCancellable {
            Timber.w("Stopping connect coroutine")
            scope.cancel()
        }
    }

    private fun calculatePowerBike(
        userMass: Double,
        factorMass: Double,
        factorDistance: Double,
        factorElevation: Double,
        powerConfigs: List<ConfigData>,
        values: RealKarooValues
    ): CyclingWattageEstimator {
        val speed = values.speed.getValueOrDefault()

        val slope = values.slope.getValueOrDefault()
        val elevation = values.elevation.getValueOrDefault()
        val finalHeadwind = values.headwind.getValueOrDefault()

        var cadence = 0.0
        var isforcepower = powerConfigs[0].isforcepower

        if( values.cadence is StreamState.Streaming) cadence = values.cadence.getValueOrDefault()
        else isforcepower = true


        //Timber.w("VALUES  Speed: $speed, Cadence: $cadence, Slope: $slope, Elevation: $elevation, Headwind: $finalHeadwind")

        return CyclingWattageEstimator(
            slope = slope / 100,
            totalMass = (userMass + powerConfigs[0].bikeMass.toDoubleLocale()) * factorMass,
            rollingResistanceCoefficient = powerConfigs[0].rollingResistanceCoefficient.toDoubleLocale(),
            dragCoefficient = powerConfigs[0].dragCoefficient.toDoubleLocale(),
            speed = speed * factorDistance,
            elevation = elevation * factorElevation,
            windSpeed = finalHeadwind,
            powerLoss = powerConfigs[0].powerLoss.toDoubleLocale() / 100,
            frontalArea = powerConfigs[0].frontalArea.toDoubleLocale(),
            ftp = powerConfigs[0].ftp.toDoubleLocale(),
            cadence = cadence,
            surface = powerConfigs[0].surface.factor,
            isforcepower = isforcepower
        )
    }


    companion object {
        fun fromUid(extension: String, uid: String, karooSystem: KarooSystemService, context: Context): EstimatedPowerSource? {
            return uid.substringAfterLast("-").toIntOrNull()?.let {
                EstimatedPowerSource(extension, it, karooSystem, context)
            }
        }
    }
}



package com.enderthor.kpower.vdevice

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import com.enderthor.kpower.BuildConfig
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.RealKarooValues
import com.enderthor.kpower.data.previewConfigData
import com.enderthor.kpower.extension.*
import kotlin.time.Duration.Companion.milliseconds


private data class EnvAndConfig(
    val values: RealKarooValues,
    val configs: List<ConfigData>,
    val weatherTempC: Double?,
    val weatherPressureHpa: Double?,
    val karooTemp: StreamState,
)

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
            "KPowerv2"
        )
    }

    // Re-entrancy guard: if Karoo re-invokes connect() during the same ride
    // (data field change, source restart), we cancel the previous scope so
    // background work doesn't accumulate.
    @Volatile
    private var activeScope: CoroutineScope? = null

    private val accelerationTracker = AccelerationTracker()

    // Suavizado de pendiente (ataca el ruido del grade en origen), suavizado de la
    // potencia final ("3s power", como los medidores reales) e histéresis del gate
    // de cadencia (evita el parpadeo 0 W/valor alrededor del corte).
    private val gradeSmoother = GradeSmoother()
    private val powerSmoother = PowerSmoother()
    private val cadenceGate = CadenceGate()

    @OptIn(FlowPreview::class)
    fun connect(emitter: Emitter<DeviceEvent>, extension: String) {
        Timber.d("Init Connect Power Estimator")

        // Cancel any previously-attached scope before installing the new one.
        activeScope?.cancel()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        activeScope = scope

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
                // karoo-ext entrega weight siempre en kg y los streams en SI (m/s, m),
                // independientemente de preferredUnit (solo afecta a la pantalla):
                // no hay que aplicar factores de conversión imperial aquí.
                val userMass = userProfile.weight.toDouble()
                val userFtp = userProfile.ftp


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

                val weatherEnvFlow = context.streamCurrentWeatherData()
                    .map { (it.current.temperature) to (it.current.surfacePressure) }
                    .onStart { emit(null to null) }

                val karooTempFlow = karooSystem.streamDataMonitorFlow(DataType.Type.TEMPERATURE, noCheck = true)
                    .onStart { emit(StreamState.NotAvailable) }

                combine(
                    karooSystem.speedStreamWithStaleness(),
                    karooSystem.streamDataMonitorFlow(DataType.Type.ELEVATION_GRADE),
                    karooSystem.streamDataMonitorFlow(DataType.Type.PRESSURE_ELEVATION_CORRECTION),
                    karooSystem.streamDataMonitorFlow(DataType.Type.CADENCE, noCheck = true),
                    karooSystem.headwindFlow(context),
                    powerConfigFlow,
                    weatherEnvFlow,
                    karooTempFlow
                ) { streams: Array<*> ->
                    val speed = streams[0] as StreamState
                    val slope = streams[1] as StreamState
                    val elevation = streams[2] as StreamState
                    val cadence = streams[3] as StreamState
                    val headwind = streams[4] as StreamState

                    @Suppress("UNCHECKED_CAST")
                    val configs = streams[5] as List<ConfigData>

                    @Suppress("UNCHECKED_CAST")
                    val env = streams[6] as Pair<Double?, Double?>
                    val karooTemp = streams[7] as StreamState

                    val karooValues = RealKarooValues(
                        speed = speed,
                        slope = slope,
                        elevation = elevation,
                        cadence = cadence,
                        headwind = headwind
                    )
                    EnvAndConfig(karooValues, configs, env.first, env.second, karooTemp)
                }
                    // Rate-limit BEFORE the expensive compute. combine() of 8 streams
                    // can fire up to ~8x/s under staggered emissions; sample() keeps
                    // only the latest value per 900 ms window.
                    .sample(900.milliseconds)
                    .collect { bundle ->
                        val configs = bundle.configs
                        if (configs.isNotEmpty()) {
                            val config = configs[0]
                            val nowMs = System.currentTimeMillis()
                            val speedMs = bundle.values.speed.getValueOrDefault()
                            val acceleration = accelerationTracker.update(speedMs, nowMs)
                            val slopePercent = gradeSmoother.update(
                                bundle.values.slope.getValueOrDefault(), nowMs
                            )

                            val tempC: Double? = bundle.weatherTempC ?: run {
                                if (config.useKarooTemp && bundle.karooTemp is StreamState.Streaming) {
                                    bundle.karooTemp.getValueOrDefault() - 5.0
                                } else null
                            }
                            val pressurePa: Double? = bundle.weatherPressureHpa?.times(100.0)

                            val powerBike = calculatePowerBike(
                                userMass,
                                configs,
                                bundle.values,
                                slopePercent,
                                tempC,
                                pressurePa,
                                acceleration,
                                userFtp
                            )

                            val powerValue = powerSmoother.update(powerBike.calculateCyclingWattage(), nowMs)
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

    private fun calculatePowerBike(
        userMass: Double,
        powerConfigs: List<ConfigData>,
        values: RealKarooValues,
        slopePercent: Double,
        temperatureC: Double?,
        pressurePa: Double?,
        acceleration: Double,
        userFtp: Int
    ): CyclingWattageEstimator {
        val speed = values.speed.getValueOrDefault()
        val elevation = values.elevation.getValueOrDefault()
        val finalHeadwind = values.headwind.getValueOrDefault()

        var isPedaling = true
        var isforcepower = powerConfigs[0].isforcepower

        if (values.cadence is StreamState.Streaming) {
            isPedaling = cadenceGate.update(values.cadence.getValueOrDefault())
        } else isforcepower = true

        val ftp = if (powerConfigs[0].useProfileFtp && userFtp > 0) userFtp.toDouble()
        else powerConfigs[0].ftp.toDoubleLocale()

        return CyclingWattageEstimator(
            slope = slopePercent / 100,
            totalMass = userMass + powerConfigs[0].bikeMass.toDoubleLocale(),
            rollingResistanceCoefficient = powerConfigs[0].rollingResistanceCoefficient.toDoubleLocale(),
            dragCoefficient = powerConfigs[0].dragCoefficient.toDoubleLocale(),
            speed = speed,
            elevation = elevation,
            windSpeed = finalHeadwind,
            powerLoss = powerConfigs[0].powerLoss.toDoubleLocale() / 100,
            frontalArea = powerConfigs[0].frontalArea.toDoubleLocale(),
            ftp = ftp,
            isPedaling = isPedaling,
            surface = powerConfigs[0].surface.factor,
            isforcepower = isforcepower,
            temperatureC = temperatureC,
            pressurePa = pressurePa,
            acceleration = acceleration
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

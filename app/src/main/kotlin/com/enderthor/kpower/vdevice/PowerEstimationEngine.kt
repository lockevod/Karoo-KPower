package com.enderthor.kpower.vdevice

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.RealKarooValues
import com.enderthor.kpower.data.previewConfigData
import com.enderthor.kpower.data.GpsCoordinates
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.SURFACE_MAX_AGE_MS
import com.enderthor.kpower.data.SURFACE_MIN_INTERVAL_MS
import com.enderthor.kpower.data.SURFACE_MIN_MOVE_M
import com.enderthor.kpower.surface.SurfaceConditionReader
import com.enderthor.kpower.surface.effectiveSurface
import com.enderthor.kpower.extension.*
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private data class EngineEnvAndConfig(
    val values: RealKarooValues,
    val configs: List<ConfigData>,
    val weatherTempC: Double?,
    val weatherPressureHpa: Double?,
    val karooTemp: StreamState,
)

class PowerEstimationEngine(
    private val karooSystem: KarooSystemService,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _instantW = MutableStateFlow(Double.NaN)
    private val _powerEmaW = MutableStateFlow(0.0)
    private val _power3sW = MutableStateFlow(Double.NaN)
    private val _npW = MutableStateFlow(Double.NaN)
    private val _avgW = MutableStateFlow(Double.NaN)
    private val _hasSample = MutableStateFlow(false)

    val instantW: StateFlow<Double> = _instantW.asStateFlow()
    val powerEmaW: StateFlow<Double> = _powerEmaW.asStateFlow()
    val power3sW: StateFlow<Double> = _power3sW.asStateFlow()
    val npW: StateFlow<Double> = _npW.asStateFlow()
    val avgW: StateFlow<Double> = _avgW.asStateFlow()
    val hasSample: StateFlow<Boolean> = _hasSample.asStateFlow()

    private val accelerationTracker = AccelerationTracker()
    private val gradeSmoother = GradeSmoother()
    private val powerSmoother = PowerSmoother()
    private val cadenceGate = CadenceGate()
    private val surfaceReader by lazy { SurfaceConditionReader(context) }

    @Volatile private var liveSurface: KarooSurface? = null
    @Volatile private var liveSurfaceAtMs: Long = 0L
    @Volatile private var latestInstantW: Double = Double.NaN

    private val ma3s = MovingAverage(windowSamples = 3)
    private val npCalc = NormalizedPowerCalculator()
    private val runningAvg = RunningAverage()
    @Volatile private var recording = false

    private val refCount = AtomicInteger(0)
    private var pipelineJob: Job? = null
    private var metricJob: Job? = null

    @Synchronized fun acquire() {
        if (refCount.incrementAndGet() == 1) startPipeline()
    }

    @Synchronized fun release() {
        if (refCount.decrementAndGet() <= 0) {
            refCount.set(0)
            stopPipeline()
        }
    }

    fun onRideState(state: RideState) {
        when (state) {
            is RideState.Recording -> {
                if (!recording) resetSessionAccumulators()
                recording = true
            }
            is RideState.Paused -> recording = false
            is RideState.Idle -> recording = false
        }
    }

    private fun resetSessionAccumulators() {
        ma3s.reset(); npCalc.reset(); runningAvg.reset()
        _power3sW.value = Double.NaN
        _npW.value = Double.NaN
        _avgW.value = Double.NaN
    }

    @OptIn(FlowPreview::class)
    private fun startPipeline() {
        if (pipelineJob != null) return
        Timber.d("PowerEstimationEngine: start")
        val job = SupervisorJob(scope.coroutineContext[Job])
        val engineScope = CoroutineScope(Dispatchers.IO + job)

        pipelineJob = engineScope.launch {
            val userProfile = karooSystem.consumerFlow<UserProfile>().first()
            val userMass = userProfile.weight.toDouble()
            val userFtp = userProfile.ftp

            val powerConfigFlow = context.loadPreferencesFlow()
                .catch { e ->
                    Timber.e(e, "Error loading power configs")
                    emit(previewConfigData)
                }
                .stateIn(engineScope, SharingStarted.Eagerly, previewConfigData)

            val weatherEnvFlow = context.streamCurrentWeatherData()
                .map { (it.current.temperature) to (it.current.surfacePressure) }
                .onStart { emit(null to null) }

            val karooTempFlow = karooSystem.streamDataMonitorFlow(DataType.Type.TEMPERATURE, noCheck = true)
                .onStart { emit(StreamState.NotAvailable) }

            launch {
                try {
                    var lastLat = Double.NaN
                    var lastLon = Double.NaN
                    var lastMs = 0L
                    karooSystem.streamLocation()
                        .filter { it.orientation != null }
                        .collect { loc ->
                            val cfg = powerConfigFlow.value.firstOrNull()
                            if (cfg?.useRouteSurface != true) {
                                liveSurface = null
                                return@collect
                            }
                            val now = System.currentTimeMillis()
                            val movedM = if (lastLat.isNaN()) Double.MAX_VALUE
                                else GpsCoordinates(lastLat, lastLon)
                                    .distanceTo(GpsCoordinates(loc.lat, loc.lng)) * 1000.0
                            if (movedM >= SURFACE_MIN_MOVE_M && now - lastMs >= SURFACE_MIN_INTERVAL_MS) {
                                liveSurface = surfaceReader.classifyAt(loc.lat, loc.lng)
                                liveSurfaceAtMs = now
                                lastLat = loc.lat; lastLon = loc.lng; lastMs = now
                            }
                        }
                } finally {
                    runCatching { surfaceReader.close() }
                }
            }

            combine(
                karooSystem.speedStreamWithStaleness(),
                karooSystem.streamDataMonitorFlow(DataType.Type.ELEVATION_GRADE),
                karooSystem.streamDataMonitorFlow(DataType.Type.PRESSURE_ELEVATION_CORRECTION),
                karooSystem.streamDataMonitorFlow(DataType.Type.CADENCE, noCheck = true),
                karooSystem.headwindFlow(context),
                powerConfigFlow,
                weatherEnvFlow,
                karooTempFlow,
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
                EngineEnvAndConfig(karooValues, configs, env.first, env.second, karooTemp)
            }
                .sample(900.milliseconds)
                .collect { bundle ->
                    val configs = bundle.configs
                    if (configs.isEmpty()) return@collect
                    val config = configs[0]
                    val nowMs = System.currentTimeMillis()
                    val speedMs = bundle.values.speed.getValueOrDefault()
                    val acceleration = accelerationTracker.update(speedMs, nowMs)
                    val slopePercent = gradeSmoother.update(bundle.values.slope.getValueOrDefault(), nowMs)
                    val tempC: Double? = bundle.weatherTempC ?: run {
                        if (config.useKarooTemp && bundle.karooTemp is StreamState.Streaming)
                            bundle.karooTemp.getValueOrDefault() - 5.0 else null
                    }
                    val pressurePa: Double? = bundle.weatherPressureHpa?.times(100.0)

                    val rawPower = calculatePowerBike(
                        userMass, configs, bundle.values, slopePercent, tempC, pressurePa, acceleration, userFtp
                    ).calculateCyclingWattage()
                    val ema = powerSmoother.update(rawPower, nowMs)

                    latestInstantW = rawPower
                    _instantW.value = rawPower
                    _powerEmaW.value = ema
                    if (!_hasSample.value) _hasSample.value = true
                }
        }

        metricJob = engineScope.launch {
            while (isActive) {
                delay(1.seconds)
                val w = latestInstantW
                if (w.isNaN()) continue
                _power3sW.value = ma3s.add(w)
                if (recording) {
                    npCalc.add(w)
                    runningAvg.add(w)
                    _npW.value = npCalc.value
                    _avgW.value = runningAvg.value
                }
            }
        }
    }

    private fun stopPipeline() {
        Timber.d("PowerEstimationEngine: stop")
        pipelineJob?.cancel(); pipelineJob = null
        metricJob?.cancel(); metricJob = null
        _hasSample.value = false
        latestInstantW = Double.NaN
    }

    private fun calculatePowerBike(
        userMass: Double,
        powerConfigs: List<ConfigData>,
        values: RealKarooValues,
        slopePercent: Double,
        temperatureC: Double?,
        pressurePa: Double?,
        acceleration: Double,
        userFtp: Int,
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
            surface = effectiveSurface(powerConfigs[0], freshLiveSurface()).factor,
            isforcepower = isforcepower,
            temperatureC = temperatureC,
            pressurePa = pressurePa,
            acceleration = acceleration,
        )
    }

    private fun freshLiveSurface(): KarooSurface? =
        liveSurface?.takeIf { System.currentTimeMillis() - liveSurfaceAtMs < SURFACE_MAX_AGE_MS }
}

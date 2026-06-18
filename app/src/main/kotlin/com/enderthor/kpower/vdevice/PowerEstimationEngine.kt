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
    private val activeProfileIdFlow: kotlinx.coroutines.flow.StateFlow<String?>,
) {
    private val _instantW = MutableStateFlow(Double.NaN)
    private val _powerEmaW = MutableStateFlow(Double.NaN)
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

    private val _estimateIsPrimary = MutableStateFlow(false)
    /** True while KPower's virtual device has >=1 live connection (the Karoo binds it as the
     *  active power source). Keyed by connection TOKEN (not a raw counter): add/remove are
     *  idempotent and balanced by identity, so an overlapping connect/disconnect (rebind) or a
     *  repeated connect can't desync a count the way a +1/-1 counter could. */
    val estimateIsPrimary: StateFlow<Boolean> = _estimateIsPrimary.asStateFlow()
    private val virtualDeviceTokens = HashSet<Any>()
    @Synchronized fun setVirtualDeviceConnected(token: Any, connected: Boolean) {
        if (connected) virtualDeviceTokens.add(token) else virtualDeviceTokens.remove(token)
        _estimateIsPrimary.value = virtualDeviceTokens.isNotEmpty()
    }

    private val accelerationTracker = AccelerationTracker()
    private val gradeSmoother = GradeSmoother()
    private val powerSmoother = PowerSmoother()
    private val cadenceGate = CadenceGate()
    private val surfaceReader by lazy { SurfaceConditionReader(context) }

    // Superficie + su timestamp en UN solo volatile: el lector empareja siempre valor y
    // tiempo del mismo instante (dos volatiles independientes podían leerse cruzados).
    @Volatile private var liveSurfaceSample: Pair<KarooSurface, Long>? = null
    @Volatile private var latestInstantW: Double = Double.NaN

    private val ma3s = MovingAverage(windowSamples = 3)
    private val npCalc = NormalizedPowerCalculator()
    private val runningAvg = RunningAverage()
    @Volatile private var recording = false
    @Volatile private var pendingReset = false

    // Consumidores por TOKEN (no un contador): el dispositivo virtual usa su instancia
    // como token y el modo comparación un token fijo. Un Set hace acquire/release
    // idempotentes — un doble release (p.ej. el host cancela un emitter dos veces) no
    // puede desbalancear el contador y parar el motor mientras otro consumidor lo usa.
    private val consumers = HashSet<Any>()
    private var engineJob: Job? = null
    private var pipelineJob: Job? = null
    private var metricJob: Job? = null

    @Synchronized fun acquire(token: Any) {
        if (consumers.add(token) && consumers.size == 1) startPipeline()
    }

    @Synchronized fun release(token: Any) {
        if (consumers.remove(token) && consumers.isEmpty()) stopPipeline()
    }

    /**
     * True while the estimator is actually in use (≥1 consumer: the paired virtual device and/or
     * comparison mode). When false the engine is dormant, so estimator-only background work (e.g. the
     * weather refresh) can be skipped — this is what makes a "dynamics only, no estimator" setup cost
     * nothing for estimation.
     */
    @Synchronized fun isActive(): Boolean = consumers.isNotEmpty()

    @Synchronized fun onRideState(state: RideState) {
        when (state) {
            is RideState.Recording -> {
                if (!recording) resetSessionAccumulators()
                recording = true
            }
            is RideState.Paused -> recording = false
            is RideState.Idle -> recording = false
        }
    }

    // Resets only the published StateFlows (StateFlow.value is thread-safe). The
    // accumulator-object resets (ma3s/npCalc/runningAvg) are deferred to the metric
    // loop via pendingReset, so they run on the same thread that calls .add(...).
    private fun resetSessionAccumulators() {
        pendingReset = true
        _power3sW.value = Double.NaN
        _npW.value = Double.NaN
        _avgW.value = Double.NaN
    }

    @OptIn(FlowPreview::class)
    private fun startPipeline() {
        if (pipelineJob != null) return
        Timber.d("PowerEstimationEngine: start")
        val job = SupervisorJob(scope.coroutineContext[Job])
        engineJob = job
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
                            val cfg = com.enderthor.kpower.data.resolveActiveConfig(powerConfigFlow.value, activeProfileIdFlow.value)
                            if (cfg?.useRouteSurface != true) {
                                liveSurfaceSample = null
                                return@collect
                            }
                            val now = System.currentTimeMillis()
                            val movedM = if (lastLat.isNaN()) Double.MAX_VALUE
                                else GpsCoordinates(lastLat, lastLon)
                                    .distanceTo(GpsCoordinates(loc.lat, loc.lng)) * 1000.0
                            if (movedM >= SURFACE_MIN_MOVE_M && now - lastMs >= SURFACE_MIN_INTERVAL_MS) {
                                val classified = surfaceReader.classifyAt(loc.lat, loc.lng)
                                liveSurfaceSample = classified?.let { it to now }
                                if (FileLogTree.enabled) Timber.tag("SURFACE").d(
                                    "classifyAt(%.5f,%.5f) -> %s",
                                    loc.lat, loc.lng, classified?.name ?: "Unknown(->preset)"
                                )
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
                    val config = com.enderthor.kpower.data.resolveActiveConfig(configs, activeProfileIdFlow.value) ?: return@collect
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
                        userMass, config, bundle.values, slopePercent, tempC, pressurePa, acceleration, userFtp
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
                // Self-clock for the 1Hz metric tick. Acceptable here; the FIT-file
                // cadence is driven separately by ELAPSED_TIME elsewhere.
                delay(1.seconds)
                // Consume deferred accumulator reset on the metric-loop thread so the
                // .reset()/.add(...) mutations never race across threads.
                if (pendingReset) {
                    ma3s.reset(); npCalc.reset(); runningAvg.reset()
                    pendingReset = false
                }
                val w = latestInstantW
                if (w.isNaN()) continue
                // The 3s moving average accumulates every tick by design (NOT gated by
                // `recording`, unlike NP/avg below).
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
        engineJob?.cancel(); engineJob = null; pipelineJob = null; metricJob = null
        _hasSample.value = false
        latestInstantW = Double.NaN
        _powerEmaW.value = Double.NaN
        _instantW.value = Double.NaN
    }

    private fun calculatePowerBike(
        userMass: Double,
        config: ConfigData,
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
        var isforcepower = config.isforcepower

        if (values.cadence is StreamState.Streaming) {
            isPedaling = cadenceGate.update(values.cadence.getValueOrDefault())
        } else isforcepower = true

        val ftp = if (config.useProfileFtp && userFtp > 0) userFtp.toDouble()
        else config.ftp.toDoubleLocale()

        return CyclingWattageEstimator(
            slope = slopePercent / 100,
            totalMass = userMass + config.bikeMass.toDoubleLocale(),
            rollingResistanceCoefficient = config.rollingResistanceCoefficient.toDoubleLocale(),
            dragCoefficient = config.dragCoefficient.toDoubleLocale(),
            speed = speed,
            elevation = elevation,
            windSpeed = finalHeadwind,
            powerLoss = config.powerLoss.toDoubleLocale() / 100,
            frontalArea = config.frontalArea.toDoubleLocale(),
            ftp = ftp,
            isPedaling = isPedaling,
            surface = resolveSurfaceForCalc(config).factor,
            isforcepower = isforcepower,
            temperatureC = temperatureC,
            pressurePa = pressurePa,
            acceleration = acceleration,
        )
    }

    private fun freshLiveSurface(): KarooSurface? =
        liveSurfaceSample?.takeIf { System.currentTimeMillis() - it.second < SURFACE_MAX_AGE_MS }?.first

    @Volatile private var lastSurfaceLogKey: String? = null

    /**
     * Effective surface used by the calc, plus a diagnostic log (when logging is on) that makes the
     * source explicit so deviations can be correlated: whether it's the LIVE map classification or a
     * fallback to the rider's PRESET (feature off / no map data). Logged only on change → no spam.
     */
    private fun resolveSurfaceForCalc(config: ConfigData): KarooSurface {
        val live = freshLiveSurface()
        val effective = effectiveSurface(config, live)
        if (FileLogTree.enabled) {
            val source = when {
                !config.useRouteSurface -> "preset(feature-off)"
                live == null -> "preset(no-live-data)"
                else -> "live-map"
            }
            val key = "${effective.name}/$source"
            if (key != lastSurfaceLogKey) {
                lastSurfaceLogKey = key
                Timber.tag("SURFACE").d(
                    "effective=%s (factor=%.2f) source=%s preset=%s",
                    effective.name, effective.factor, source, config.surface.name
                )
            }
        }
        return effective
    }
}

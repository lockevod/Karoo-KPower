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
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.time.Duration.Companion.seconds

/** The Karoo's internal temperature sensor reads several °C above ambient (it sits inside the warm
 *  device); subtract this when using it as the air-density fallback so density isn't underestimated.
 *  Mid-range of the observed +3..+8 °C bias. Only applied when real weather temperature is absent. */
private const val KAROO_TEMP_SENSOR_BIAS_C = 5.0

/** Fallback rider mass (kg) when the Karoo UserProfile can't be read at pipeline start. */
private const val DEFAULT_USER_MASS_KG = 75.0

/** Max time to hold the last grade across a stream dropout before falling back to flat (0%). */
private const val GRADE_MAX_HOLD_MS = 60_000L

/**
 * Below this GPS speed (m/s ≈ 2.9 km/h) the rider isn't producing estimable cycling power, so the
 * estimate is forced to 0. Without this, GPS speed NOISE while stationary (e.g. the Karoo sitting on a
 * table indoors with no fix) drives the acceleration term and shows a phantom few watts that fluctuate
 * 0–9 W. A real cyclist is never below this while pedalling, so it can't suppress a genuine effort.
 */
private const val MIN_SPEED_FOR_POWER_MS = 0.8

/**
 * Speed must stay above [MIN_SPEED_FOR_POWER_MS] for this many consecutive 1 Hz ticks before the rider
 * counts as "moving". A single GPS speed spike (indoor noise) is one tick, so it never crosses this and
 * can't produce a phantom watt; sustained real riding crosses it in ~2 s. Used BOTH as the estimate gate
 * and — when there is NO cadence sensor — as the "is pedalling" proxy so the rider's "only calculate when
 * pedalling" toggle is honoured (instead of forcing power on just because cadence is absent).
 */
private const val MIN_MOVING_TICKS = 2

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
    // Last good grade %/elevation m, held across stream dropouts (a missing stream != 0% / sea level).
    @Volatile private var lastGoodSlope = 0.0
    @Volatile private var lastGoodElevation = 0.0
    @Volatile private var lastSlopeMs = 0L   // for the staleness bound below
    // Consecutive 1 Hz ticks with speed above MIN_SPEED_FOR_POWER_MS (debounces GPS speed spikes). Only
    // touched on the single estimate-collect coroutine.
    private var movingTicks = 0

    // Field calibration: derives Crr & CdA from the REAL meter vs the model, accumulated over a
    // comparison session. realPowerProvider is set by the extension (active meter's live power, NaN if none).
    private val fieldCalibrator = FieldCalibrator()
    @Volatile var realPowerProvider: (() -> Double)? = null

    /** Best fit (CdA + per-surface Crr with std errors) from the current session, or null if too few. */
    fun calibrationFit(): FieldCalibrator.Fit? = fieldCalibrator.result()

    /** Discard accumulated calibration samples (e.g. the active bike changed mid-ride). */
    fun resetCalibration() = fieldCalibrator.reset()
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
    // Encapsulates "reset only on a genuine new ride (Idle->Recording), freeze during pause" — see
    // RideResetGate. Only touched under the @Synchronized onRideState() below.
    private val resetGate = RideResetGate()
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

    // Reset only on a genuine new ride (Idle->Recording); freeze (keep accumulating) across a pause —
    // an autopause resume must NOT wipe NP/avg/calibration. See RideResetGate.
    @Synchronized fun onRideState(state: RideState) {
        if (resetGate.onRideState(state)) resetSessionAccumulators()
        recording = resetGate.recording
    }

    // Resets only the published StateFlows (StateFlow.value is thread-safe). The
    // accumulator-object resets (ma3s/npCalc/runningAvg) are deferred to the metric
    // loop via pendingReset, so they run on the same thread that calls .add(...).
    private fun resetSessionAccumulators() {
        pendingReset = true
        _power3sW.value = Double.NaN
        _npW.value = Double.NaN
        _avgW.value = Double.NaN
        fieldCalibrator.reset()   // a new ride → a fresh calibration session
    }

    @OptIn(FlowPreview::class)
    private fun startPipeline() {
        if (pipelineJob != null) return
        Timber.d("PowerEstimationEngine: start")
        val job = SupervisorJob(scope.coroutineContext[Job])
        engineJob = job
        val engineScope = CoroutineScope(Dispatchers.IO + job)

        pipelineJob = engineScope.launch {
            // Bounded: if the UserProfile consumer never emits (service not bound / transient AIDL
            // failure) we must NOT hang the whole estimation pipeline forever — degrade to a default
            // mass (FTP 0 → config's own ftp is used) so the estimate still runs.
            val userProfile = withTimeoutOrNull(5_000) { karooSystem.consumerFlow<UserProfile>().first() }
            val userMass = userProfile?.weight?.toDouble() ?: DEFAULT_USER_MASS_KG
            val userFtp = userProfile?.ftp ?: 0

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
                // noCheck skips the emit(initialState) path, so without a synthetic first emission this
                // is the only combine input with no initial value — the whole combine (and every est_*
                // output) would stall until the host sends the first cadence StreamState. NotAvailable is
                // the correct fallback: a non-Streaming cadence makes the estimator fall back to the
                // movement-based pedalling proxy below, which is right before the real stream arrives.
                karooSystem.streamDataMonitorFlow(DataType.Type.CADENCE, noCheck = true)
                    .onStart { emit(StreamState.NotAvailable) },
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
                .sample(1.seconds)   // match the 1 Hz metric tick that reads latestInstantW (no rate skew)
                .collect { bundle ->
                    val configs = bundle.configs
                    val config = com.enderthor.kpower.data.resolveActiveConfig(configs, activeProfileIdFlow.value) ?: return@collect
                    val nowMs = System.currentTimeMillis()
                    val speedMs = bundle.values.speed.getValueOrDefault()
                    // "Moving" = speed sustained above the gate for >= MIN_MOVING_TICKS ticks, so a 1-tick
                    // GPS noise spike never counts. ONLY update on a FRESH speed sample: a stream dropout
                    // (tunnel/GPS gap, where getValueOrDefault() returns 0.0) must HOLD the last state, not
                    // flap moving→false mid-effort. `moving` is used ONLY as the no-cadence pedalling proxy
                    // below (which still respects the force-power toggle) — it is NOT a hard output gate.
                    if (bundle.values.speed is StreamState.Streaming) {
                        if (speedMs >= MIN_SPEED_FOR_POWER_MS) { if (movingTicks < MIN_MOVING_TICKS) movingTicks++ } else movingTicks = 0
                    }
                    val moving = movingTicks >= MIN_MOVING_TICKS
                    val acceleration = accelerationTracker.update(speedMs, nowMs)
                    // HOLD the last good grade/elevation across stream dropouts instead of treating a
                    // missing stream as 0 — a momentary grade dropout would otherwise read as 0% (flat)
                    // and collapse the gravity term mid-climb (and elevation→0 = sea-level air density).
                    val slopeStream = bundle.values.slope
                    if (slopeStream is StreamState.Streaming) {
                        lastGoodSlope = slopeStream.getValueOrDefault(); lastSlopeMs = nowMs
                    } else if (lastSlopeMs != 0L && nowMs - lastSlopeMs > GRADE_MAX_HOLD_MS) {
                        // Don't hold a stale grade forever (e.g. a steep ramp pinned through a long
                        // tunnel/GPS outage would over-estimate); after the bound, fall back to flat.
                        lastGoodSlope = 0.0
                    }
                    val slopePercent = gradeSmoother.update(lastGoodSlope, nowMs)
                    val elevStream = bundle.values.elevation
                    if (elevStream is StreamState.Streaming) lastGoodElevation = elevStream.getValueOrDefault()
                    val heldElevation = lastGoodElevation
                    val tempC: Double? = bundle.weatherTempC ?: run {
                        if (config.useKarooTemp && bundle.karooTemp is StreamState.Streaming)
                            bundle.karooTemp.getValueOrDefault() - KAROO_TEMP_SENSOR_BIAS_C else null
                    }
                    val pressurePa: Double? = bundle.weatherPressureHpa?.times(100.0)

                    val est = calculatePowerBike(
                        userMass, config, bundle.values, slopePercent, heldElevation, acceleration, tempC, pressurePa, userFtp, moving
                    )
                    // calculateCyclingWattage returns the cap applied to the SIGNED total (so a big
                    // acceleration spike can't show an absurd instant value); we floor it to ≥0 here.
                    // Floor PER SAMPLE — a real power meter never reports negative, so for the comparison
                    // (and NP, which 4th-powers values) to be apples-to-apples the estimate must floor too.
                    // GPS-acceleration noise is handled by AccelerationTracker's EMA, not by allowing
                    // negative power (NP can't average signed samples — (−x)⁴ = (+x)⁴).
                    // No output gate here: calculateCyclingWattage already returns 0 when (no force-power AND
                    // not pedalling), where "pedalling" is the cadence sensor or — with no cadence — `moving`.
                    // A previous `if (!moving) 0.0` here ALSO zeroed power on slow climbs / GPS dropouts and
                    // overrode the force-power toggle; gating only inside the estimator fixes all three.
                    val rawSigned = est.calculateCyclingWattage()
                    val instantW = maxOf(0.0, rawSigned)
                    // Field calibration: while recording with a real meter present, accumulate the
                    // CdA + per-surface-Crr least-squares regression of REAL power vs the model regressors.
                    if (recording) realPowerProvider?.invoke()?.let { rp ->
                        est.calibrationRegressors(rp)?.let { (y, x1, x2) ->
                            fieldCalibrator.add(y, x1, x2, resolveSurfaceForCalc(config))
                        }
                    }
                    val ema = powerSmoother.update(instantW, nowMs)

                    latestInstantW = instantW                  // floored → metrics (incl. NP) match a real meter
                    _instantW.value = instantW                 // instant field: non-negative display
                    _powerEmaW.value = ema                     // already >= 0 (floored input)
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
                val w = latestInstantW   // already floored to ≥0 (matches a real meter for NP/avg/3s)
                if (w.isNaN()) continue
                // The 3s moving average accumulates every tick by design (NOT gated by `recording`,
                // unlike NP/avg below).
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
        // _power3sW is a LIVE rolling value like instant/EMA (fed every metric tick regardless of
        // `recording`), so it must not survive a pipeline stop either — otherwise a re-acquire can
        // publish a stale pre-pause 3s value while est_power is still NaN. _npW/_avgW are SESSION
        // aggregates (gated on `recording`, reset via RideResetGate on Idle->Recording) and are left
        // alone here.
        _power3sW.value = Double.NaN
        // Drop held grade/elevation so an acquire→release→acquire within one process doesn't resume
        // with a stale grade if the first sample after re-acquire is a dropout.
        lastGoodSlope = 0.0; lastGoodElevation = 0.0; lastSlopeMs = 0L
    }

    private fun calculatePowerBike(
        userMass: Double,
        config: ConfigData,
        values: RealKarooValues,
        slopePercent: Double,
        elevation: Double,
        acceleration: Double,
        temperatureC: Double?,
        pressurePa: Double?,
        userFtp: Int,
        moving: Boolean,
    ): CyclingWattageEstimator {
        val speed = values.speed.getValueOrDefault()
        val finalHeadwind = values.headwind.getValueOrDefault()

        val isforcepower = config.isforcepower
        // "Is pedalling": from the cadence sensor when present, else from sustained MOVEMENT. This is the
        // fix for the rider's "only calculate when pedalling" toggle being ignored with no cadence sensor:
        // we no longer force power on — without cadence, not-moving counts as not-pedalling, so the toggle
        // (isforcepower=false) correctly yields 0 on a stationary unit. With the toggle OFF (isforcepower
        // =true) power is computed regardless, exactly as before.
        val isPedaling = if (values.cadence is StreamState.Streaming) {
            cadenceGate.update(values.cadence.getValueOrDefault())
        } else moving

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

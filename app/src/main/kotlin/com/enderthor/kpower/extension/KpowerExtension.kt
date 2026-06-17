package com.enderthor.kpower.extension


import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.Device
import io.hammerhead.karooext.models.DeviceEvent
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

import com.enderthor.kpower.BuildConfig
import com.enderthor.kpower.data.HeadwindStats
import com.enderthor.kpower.data.WEATHER_CHECK_INTERVAL_MS
import com.enderthor.kpower.data.WEATHER_MAX_AGE_MS
import com.enderthor.kpower.data.WEATHER_MIN_MOVE_KM
import com.enderthor.kpower.data.WEATHER_RETRY_DELAY_MS
import com.enderthor.kpower.vdevice.EstimatedPowerSource
import com.enderthor.kpower.vdevice.PowerEstimationEngine

import timber.log.Timber


class KpowerExtension : KarooExtension("kpower", BuildConfig.VERSION_NAME)
{

    lateinit var karooSystem: KarooSystemService

    private val supervisor = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + supervisor)

    private val engine: PowerEstimationEngine by lazy {
        PowerEstimationEngine(karooSystem, applicationContext, serviceScope)
    }

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            EstimatedPowerDataType(extension, TYPE_EST_INSTANT, engine, { applicationContext.comparisonModeFlow() }) { it.instantW },
            EstimatedPowerDataType(extension, TYPE_EST_3S, engine, { applicationContext.comparisonModeFlow() }) { it.power3sW },
            EstimatedPowerDataType(extension, TYPE_EST_NP, engine, { applicationContext.comparisonModeFlow() }) { it.npW },
            EstimatedPowerDataType(extension, TYPE_EST_AVG, engine, { applicationContext.comparisonModeFlow() }) { it.avgW },
        )
    }

    private val fieldEstPower = DeveloperField(0, 136, "est_power", "W")
    private val fieldEstPower3s = DeveloperField(1, 136, "est_power_3s", "W")
    private val fieldEstNp = DeveloperField(2, 136, "est_np", "W")
    private val fieldEstAvg = DeveloperField(3, 136, "est_avg", "W")

    override fun onCreate() {
        super.onCreate()

        Timber.d("Service created")
        karooSystem = KarooSystemService(applicationContext)

        karooSystem.connect { connected ->
            if (connected) {
                Timber.d("Connected to Karoo system")
            }
        }

        serviceScope.launch {
            karooSystem.updateLastKnownGps(this@KpowerExtension)
        }

        serviceScope.launch {
            weatherRefreshLoop()
        }

        // Mantiene el engine vivo (acquire) mientras (toggle ON && Recording) aunque la
        // fuente de potencia activa sea el potenciómetro real; y le pasa el RideState para
        // resetear NP/media en Idle->Recording y congelar en pausa.
        serviceScope.launch {
            var acquiredForComparison = false
            karooSystem.consumerFlow<RideState>()
                .combine(applicationContext.comparisonModeFlow()) { state, mode -> state to mode }
                // Dedup is safe: RideState.Recording/Idle are payload-free objects, so
                // collapsing identical emissions never drops a real transition. And
                // engine.onRideState(state) is intentionally called before the comparison
                // shouldRun gate below, so NP/avg reset + pause-freeze work even when
                // comparison mode is OFF.
                .distinctUntilChanged()
                .collect { (state, mode) ->
                    engine.onRideState(state)
                    val shouldRun = mode && state is RideState.Recording
                    if (shouldRun && !acquiredForComparison) {
                        engine.acquire(); acquiredForComparison = true
                    } else if (!shouldRun && acquiredForComparison) {
                        engine.release(); acquiredForComparison = false
                    }
                }
        }
    }

    /**
     * Weather refresh policy (Headwind-style):
     *  - tick every WEATHER_CHECK_INTERVAL_MS
     *  - only fetch when GPS moved >= WEATHER_MIN_MOVE_KM from the last successful
     *    fetch position OR the last fetch is >= WEATHER_MAX_AGE_MS old (or there
     *    is no previous fetch)
     *  - on HTTP error, wait WEATHER_RETRY_DELAY_MS before next attempt
     *
     * Replaces the previous distinctUntilChanged(1m) + debounce(10s) + 15-min
     * timer pipeline, which under continuous riding produced 0 weather updates
     * because debounce never expired.
     */
    private suspend fun weatherRefreshLoop() {
        while (coroutineContext.isActive) {
            try {
                val preferences = loadPreferencesFlow().first()
                if (preferences.isEmpty()) {
                    delay(WEATHER_CHECK_INTERVAL_MS)
                    continue
                }
                val cfg = preferences[0]
                val gps = karooSystem.getGpsCoordinateFlow(this@KpowerExtension)
                    .firstOrNull()
                if (gps == null) {
                    delay(WEATHER_CHECK_INTERVAL_MS)
                    continue
                }
                val stats = try { streamStats().first() } catch (_: Throwable) { HeadwindStats() }

                val now = System.currentTimeMillis()
                val lastPos = stats.lastSuccessfulWeatherPosition
                val lastMs = stats.lastSuccessfulWeatherRequest ?: 0L
                val movedFarEnough = lastPos == null ||
                    gps.distanceTo(lastPos) >= WEATHER_MIN_MOVE_KM
                val tooOld = lastMs == 0L || (now - lastMs) >= WEATHER_MAX_AGE_MS

                if (!movedFarEnough && !tooOld) {
                    delay(WEATHER_CHECK_INTERVAL_MS)
                    continue
                }

                // Si Headwind está instalado y el usuario no fuerza la meteo propia,
                // tomamos sus datos del stream en vez de pedir nuestra propia API HTTP.
                // Si Headwind no emite datos a tiempo, caemos al HTTP de abajo (fallback).
                if (cfg.preferHeadwind && isHeadwindInstalled()) {
                    val isImperial = runCatching {
                        karooSystem.consumerFlow<UserProfile>().first()
                            .preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                    }.getOrDefault(false)

                    val hw = karooSystem.fetchHeadwindWeatherSnapshot(gps, isImperial)
                    if (hw != null) {
                        try {
                            saveCurrentData(applicationContext, hw)
                            saveStats(
                                this@KpowerExtension,
                                stats.copy(
                                    lastSuccessfulWeatherRequest = now,
                                    lastSuccessfulWeatherPosition = gps,
                                ),
                            )
                        } catch (e: Throwable) {
                            Timber.e(e, "Failed to save Headwind weather data")
                        }
                        delay(WEATHER_CHECK_INTERVAL_MS)
                        continue
                    }
                    Timber.w("Headwind installed but no data; falling back to own weather API")
                }

                val response = karooSystem.makeOpenMeteoHttpRequest(
                    gps,
                    cfg.isOpenWeather,
                    cfg.apikey,
                )

                if (response.error != null) {
                    runCatching {
                        saveStats(
                            this@KpowerExtension,
                            stats.copy(failedWeatherRequest = now),
                        )
                    }.onFailure { Timber.e(it, "Failed to write failed-stats") }
                    delay(WEATHER_RETRY_DELAY_MS)
                    continue
                }

                try {
                    val body = String(response.body ?: ByteArray(0))
                    val data = parseWeatherResponse(body)
                    saveCurrentData(applicationContext, data)
                    saveStats(
                        this@KpowerExtension,
                        stats.copy(
                            lastSuccessfulWeatherRequest = now,
                            lastSuccessfulWeatherPosition = gps,
                        ),
                    )
                } catch (e: Throwable) {
                    Timber.e(e, "Failed to parse/save weather data")
                }

                delay(WEATHER_CHECK_INTERVAL_MS)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Timber.e(e, "weatherRefreshLoop error")
                delay(WEATHER_CHECK_INTERVAL_MS)
            }
        }
    }

    override fun startScan(emitter: Emitter<Device>) {
        val job: Job = serviceScope.launch {
            delay(2000L)
            Timber.d("Start scan")
            emitter.onNext(EstimatedPowerSource.buildDevice(extension, 2000, engine).source)
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Timber.d("Connect Device")
        EstimatedPowerSource.fromUid(extension, uid, engine)?.connect(emitter, extension)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun startFit(emitter: Emitter<FitEffect>) {
        val job = serviceScope.launch {
            // startFit lo llama el host en TODAS las marchas (fitFile=true). Para no pagar
            // coste cuando el modo comparación está OFF (el caso por defecto), solo nos
            // suscribimos a ELAPSED_TIME mientras el toggle está ON; flatMapLatest re-suscribe
            // al togglear (hot-toggle). ELAPSED_TIME cadencia a 1 Hz mientras se graba y se
            // detiene en pausa -> escritura sin drift y sin samples fantasma (patrón KSafe).
            // Sesión = "last write wins": solo emitimos cuando NP/media cambian, para no
            // churnar un Binder round-trip + allocation cada segundo.
            var lastSesNp = Double.NaN
            var lastSesAvg = Double.NaN
            applicationContext.comparisonModeFlow()
                .flatMapLatest { mode ->
                    if (mode) karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME) else emptyFlow()
                }
                .collect { elapsed ->
                    if (elapsed !is StreamState.Streaming) return@collect

                    val instant = engine.instantW.value
                    val p3s = engine.power3sW.value
                    val recordValues = buildList {
                        if (!instant.isNaN()) add(FieldValue(fieldEstPower, instant))
                        if (!p3s.isNaN()) add(FieldValue(fieldEstPower3s, p3s))
                    }
                    if (recordValues.isNotEmpty()) emitter.onNext(WriteToRecordMesg(recordValues))

                    val np = engine.npW.value
                    val avg = engine.avgW.value
                    if (np != lastSesNp || avg != lastSesAvg) {
                        val sessionValues = buildList {
                            if (!np.isNaN()) add(FieldValue(fieldEstNp, np))
                            if (!avg.isNaN()) add(FieldValue(fieldEstAvg, avg))
                        }
                        if (sessionValues.isNotEmpty()) emitter.onNext(WriteToSessionMesg(sessionValues))
                        lastSesNp = np
                        lastSesAvg = avg
                    }
                }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun onDestroy() {
        runCatching { serviceScope.cancel() }
        karooSystem.disconnect()
        super.onDestroy()
    }
}

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
import io.hammerhead.karooext.models.InRideAlert
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

import com.enderthor.kpower.BuildConfig
import com.enderthor.kpower.R
import com.enderthor.kpower.ant.BatteryLevel
import com.enderthor.kpower.ant.batteryLevelOf
import com.enderthor.kpower.ant.isAutoMeterLabel
import com.enderthor.kpower.data.HeadwindStats
import com.enderthor.kpower.data.WEATHER_CHECK_INTERVAL_MS
import com.enderthor.kpower.data.WEATHER_MAX_AGE_MS
import com.enderthor.kpower.data.WEATHER_MIN_MOVE_KM
import com.enderthor.kpower.data.WEATHER_RETRY_DELAY_MS
import com.enderthor.kpower.vdevice.EstimatedPowerSource
import com.enderthor.kpower.vdevice.PowerEstimationEngine

import timber.log.Timber


/** Holder for the combine driving the startFit per-record writes (mirrors [RideGate]). */
private data class FitTick(
    val elapsed: StreamState,
    val meters: List<com.enderthor.kpower.ant.SavedMeter>,
    val comparisonMode: Boolean,
)

/** Holder for the 3-way combine driving the ride-state connect gate. */
private data class RideGate(
    val state: RideState,
    val mode: Boolean,
    val meters: List<com.enderthor.kpower.ant.SavedMeter>,
)

class KpowerExtension : KarooExtension("kpower", BuildConfig.VERSION_NAME)
{

    lateinit var karooSystem: KarooSystemService

    private val supervisor = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + supervisor)

    private val activeProfileIdFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private val engine: PowerEstimationEngine by lazy {
        PowerEstimationEngine(karooSystem, applicationContext, serviceScope, activeProfileIdFlow)
    }

    private val antManager: com.enderthor.kpower.ant.AntPowerManager by lazy {
        com.enderthor.kpower.ant.AntPowerManager(applicationContext)
    }

    // Token estable del consumidor "modo comparación" para el ref-count del engine.
    private val comparisonToken = Any()

    // Cached ride-recording flag, updated by the RideState observer in onCreate. Gates the
    // weather loop so it skips the expensive GPS/stats/HTTP work when not recording.
    @Volatile private var isRecording = false

    // Latest saved-meters snapshot, kept current by a collector in onCreate. connectDevice() runs on
    // a host (binder) thread and isn't suspend, so it can't read DataStore inline — it reads this to
    // resolve a meter's friendly label for the reconnect Device name.
    @Volatile private var savedMetersSnapshot: List<com.enderthor.kpower.ant.SavedMeter> = emptyList()

    // ONE shared saved-meters flow for ALL consumers — every placed data field AND the two always-on
    // service collectors (savedMetersSnapshot + brand auto-detect, in onCreate). Each consumer used
    // to spin up its own antMetersFlow() collector (a JSON decode per DataStore emission); with all
    // fields placed that was ~34 decodes per pref write, plus the two always-on collectors decoding
    // independently regardless. shareIn collapses every consumer to a single upstream decode fanned
    // out to all; the cheap enabled-gate is derived once. The always-on collectors keep it hot for
    // the service lifetime, so there is exactly ONE decode per emission. WhileSubscribed still tears
    // it down shortly after the last subscriber goes (service teardown).
    private val sharedMeters by lazy {
        applicationContext.antMetersFlow().shareIn(serviceScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }
    private val sharedEnabledGate by lazy {
        sharedMeters.map { ms -> ms.any { it.enabled } }.distinctUntilChanged()
    }

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            EstimatedPowerDataType(extension, TYPE_EST_INSTANT, engine) { it.instantW },
            EstimatedPowerDataType(extension, TYPE_EST_3S, engine) { it.power3sW },
            EstimatedPowerDataType(extension, TYPE_EST_NP, engine) { it.npW },
            EstimatedPowerDataType(extension, TYPE_EST_AVG, engine) { it.avgW },
            RealPowerDataType(extension, realFieldTypeId(0, "power"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.powerFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "3s"),    0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.power3sFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "np"),    0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.npFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "avg"),   0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.avgFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "max"),     0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.maxFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "10s"),     0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.power10sFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "cadence"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.cadenceFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "torque"),  0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.torqueFlow(dn) },
            // Live cycling-dynamics fields (slot 0), gated on "a meter is recorded" (saved meters
            // list non-empty). Each metricFlowFor maps a STABLE manager-level dynamics sink to a
            // Double (null -> NaN -> `---`). These sinks survive meter reconnect: the bridges[dn]
            // coroutine re-mirrors each new RawAntPowerMeter into the same MutableStateFlow, so the
            // field never freezes.
            // Two-sided dynamics shown as graphical "L/R" (e.g. "47/53"): balance, torque
            // effectiveness, pedal smoothness. Left value first (left pedal), right second.
            DualValueDataType(extension, dynFieldTypeId("balance"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.balanceFlow(dn).map { r -> if (r.isNaN()) null to null else (100.0 - r) to r } },
            DualValueDataType(extension, dynFieldTypeId("te"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.tePsFlow(dn).map { it?.teLeftPct to it?.teRightPct } },
            DualValueDataType(extension, dynFieldTypeId("ps"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.tePsFlow(dn).map { it?.psLeftPct to it?.psRightPct } },
            DynamicsDataType(extension, dynFieldTypeId("pp-left"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.forceLeftFlow(dn).map { it?.startAngleDeg ?: Double.NaN } },
            DynamicsDataType(extension, dynFieldTypeId("pp-right"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.forceRightFlow(dn).map { it?.startAngleDeg ?: Double.NaN } },
            DynamicsDataType(extension, dynFieldTypeId("peakpp-left"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.forceLeftFlow(dn).map { it?.startPeakDeg ?: Double.NaN } },
            DynamicsDataType(extension, dynFieldTypeId("peakpp-right"), 0, { sharedEnabledGate }, { sharedMeters }) { dn -> antManager.forceRightFlow(dn).map { it?.startPeakDeg ?: Double.NaN } },
        )
    }

    // Memoize per (slot,kind): names/units are fixed, so a new DeveloperField every FIT
    // tick was a needless allocation. ConcurrentHashMap + computeIfAbsent: the FIT collect loop is
    // normally the only writer, but if the host ever overlaps two startFit subscriptions a plain
    // HashMap could ConcurrentModificationException mid-recording — this makes it crash-proof.
    private val meterFieldCache = java.util.concurrent.ConcurrentHashMap<Pair<Int, Int>, DeveloperField>()
    private fun meterField(slot: Int, kind: Int, name: String, units: String) =
        meterFieldCache.computeIfAbsent(slot to kind) {
            DeveloperField(
                (com.enderthor.kpower.ant.fitFieldBase(slot) + kind).toShort(), 136, name, units,
            )
        }

    // Memoized DeveloperField cache for cycling-dynamics fields (single meter). Numbers come from
    // DynField (32..). ConcurrentHashMap for the same overlap-safety reason as meterFieldCache.
    private val dynFieldCache = java.util.concurrent.ConcurrentHashMap<Int, DeveloperField>()
    private fun dynField(num: Int, name: String, units: String) =
        dynFieldCache.computeIfAbsent(num) { DeveloperField(num.toShort(), 136, name, units) }

    private val fieldEstPower = DeveloperField(0, 136, "est_power", "W")
    private val fieldEstPower3s = DeveloperField(1, 136, "est_power_3s", "W")
    private val fieldEstNp = DeveloperField(2, 136, "est_np", "W")
    private val fieldEstAvg = DeveloperField(3, 136, "est_avg", "W")

    @OptIn(ExperimentalCoroutinesApi::class)
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

        // Drive the diagnostic file logger from the rider's toggle (off by default). On turn-OFF,
        // flush the tail and close the writer so the last seconds aren't lost and the fd isn't leaked.
        serviceScope.launch {
            applicationContext.diagnosticLogFlow().collect { on ->
                FileLogTree.enabled = on
                if (!on) FileLogTree.flushAndClose()
            }
        }

        serviceScope.launch {
            weatherRefreshLoop()
        }

        // Keep a snapshot of saved meters so connectDevice() (non-suspend, binder thread) can resolve
        // a meter's friendly label for the reconnect Device name.
        serviceScope.launch {
            sharedMeters.collect { savedMetersSnapshot = it }
        }

        // Brand auto-detect: once a connected meter reports its manufacturer (0x50 page), fill in the
        // saved label IF the rider hasn't named it. Never clobbers a manual rename (userNamed flag).
        // Only meaningful while the meter is connected (recording). The write is an ATOMIC transform
        // so it can't clobber a concurrent rename/toggle.
        serviceScope.launch {
            sharedMeters
                .map { it.firstOrNull { m -> m.enabled }?.deviceNumber }
                .distinctUntilChanged()
                .flatMapLatest { dn -> if (dn == null) emptyFlow() else antManager.manufacturerFlow(dn).map { dn to it } }
                .collect { (dn, brand) ->
                    if (brand.isNullOrBlank()) return@collect
                    updateAntMeters(applicationContext) { meters ->
                        meters.map {
                            if (it.deviceNumber == dn && !it.userNamed && isAutoMeterLabel(it.label, dn))
                                it.copy(label = brand) else it
                        }
                    }
                }
        }

        // Battery alert (opt-in): while the toggle is ON and recording, watch the enabled meter's
        // battery level and fire a one-time InRideAlert on entering LOW, and again on CRITICAL — at
        // most two per ride. Edge-triggered; the fired flags reset on the Idle/Paused -> Recording
        // transition (armed). The alert names the meter. flatMapLatest re-subscribes the battery flow
        // when the toggle, ride state, or enabled meter changes; an immediate emission means a meter
        // that's already LOW/CRITICAL at ride start warns once right away (battery code persists).
        serviceScope.launch {
            var firedLow = false
            var firedCritical = false
            // Reset the per-ride fired flags ONLY on Idle -> Recording (a genuine new ride), never on
            // Paused -> Recording — otherwise autopause at every traffic light would re-arm and the
            // alert would spam on each resume. sawIdle starts true so the first Recording arms once.
            var sawIdle = true
            // The active meter can be switched mid-ride (several may be saved, one active). A different
            // meter has its own battery, so re-arm when the enabled device changes.
            var lastAlertDn: Int? = null
            combine(
                applicationContext.batteryAlertFlow(),
                karooSystem.consumerFlow<RideState>().distinctUntilChanged(),
                sharedMeters.map { it.firstOrNull { m -> m.enabled } }.distinctUntilChanged(),
            ) { enabled, ride, meter -> Triple(enabled, ride, meter) }
                .flatMapLatest { (enabled, ride, meter) ->
                    when (ride) {
                        is RideState.Idle -> sawIdle = true
                        is RideState.Recording -> if (sawIdle) { firedLow = false; firedCritical = false; sawIdle = false }
                        else -> {} // Paused: keep flags, don't re-arm
                    }
                    if (!enabled || ride !is RideState.Recording || meter == null) emptyFlow()
                    else {
                        // Re-arm when the ACTIVE meter changes — but ONLY here, inside the recording +
                        // enabled path. Doing it earlier would let a meter swap during an autopause
                        // (Paused) re-arm and then re-fire on resume, exceeding the per-ride cap.
                        if (meter.deviceNumber != lastAlertDn) {
                            firedLow = false; firedCritical = false; lastAlertDn = meter.deviceNumber
                        }
                        antManager.batteryFlow(meter.deviceNumber).map { code -> meter to code }
                    }
                }
                .collect { (meter, code) ->
                    when (batteryLevelOf(code)) {
                        BatteryLevel.CRITICAL -> if (!firedCritical) { firedCritical = true; dispatchBatteryAlert(meter, critical = true) }
                        // Critical implies low is already covered: don't fire a (lesser) LOW after a
                        // CRITICAL has fired — the ANT+ code can oscillate 5->4->5 near the threshold,
                        // which would otherwise read as a confusing "recovered to low" message.
                        BatteryLevel.LOW -> if (!firedLow && !firedCritical) { firedLow = true; dispatchBatteryAlert(meter, critical = false) }
                        else -> {}
                    }
                }
        }

        // Mirror the active Karoo ride-profile id (drives bike resolution in the engine)
        // and learn profiles (id+name) so the Settings UI can offer them for mapping.
        serviceScope.launch {
            karooSystem.streamRideProfile().collect { profile ->
                activeProfileIdFlow.value = profile.id
                runCatching {
                    val known = applicationContext.knownProfilesFlow().first()
                    if (known.none { it.id == profile.id && it.name == profile.name }) {
                        val upserted = known.filterNot { it.id == profile.id } +
                            com.enderthor.kpower.data.KnownProfile(profile.id, profile.name)
                        saveKnownProfiles(applicationContext, upserted)
                    }
                }
            }
        }

        // Mantiene el engine vivo (acquire) mientras (toggle ON && Recording) aunque la
        // fuente de potencia activa sea el potenciómetro real; y le pasa el RideState para
        // resetear NP/media en Idle->Recording y congelar en pausa.
        serviceScope.launch {
            var acquiredForComparison = false
            // Tracks the previous recording state so FileLogTree.newRide fires exactly once on the
            // Idle/Paused -> Recording transition (not on every emission of this combine).
            var wasRecording = false
            kotlinx.coroutines.flow.combine(
                karooSystem.consumerFlow<RideState>(),
                applicationContext.comparisonModeFlow(),
                sharedMeters,
            ) { state, mode, meters -> RideGate(state, mode, meters) }
                // Dedup is safe: RideState.Recording/Idle are payload-free objects, so
                // collapsing identical emissions never drops a real transition. And
                // engine.onRideState(state) is intentionally called before the comparison
                // shouldRun gate below, so NP/avg reset + pause-freeze work even when
                // comparison mode is OFF.
                .distinctUntilChanged()
                .collect { (state, mode, meters) ->
                    engine.onRideState(state)
                    antManager.onRideState(state)
                    isRecording = state is RideState.Recording
                    // New per-ride diagnostic log on the transition INTO Recording (no-op when the
                    // toggle is off). Fires once per ride start, not on every emission.
                    if (isRecording && !wasRecording) {
                        FileLogTree.newRide(System.currentTimeMillis())
                    }
                    wasRecording = isRecording
                    // Estimate engine acquire/release stays tied to COMPARISON mode only;
                    // dynamics recording does not need the estimate engine running.
                    val shouldRunComparison = mode && state is RideState.Recording
                    // Raw ANT meters connect when at least one saved meter is ENABLED AND we are in an
                    // ACTIVE ride session — Recording OR Paused. We include Paused (not just Recording)
                    // so the rider can see live real power on the ride screen during a stop/autopause
                    // without the channel flapping closed-open every traffic light. We deliberately do
                    // NOT include Idle: Idle covers BOTH "ride screen, not yet recording" AND "in the
                    // settings/scan screen", and keeping the channel open in Idle starves the ANT+ scan
                    // (MultiDeviceSearch) and drains the radio off-ride. So real power appears once the
                    // ride is started; before that the field shows `---`.
                    // Comparison mode no longer drives the meter connection — it only drives the
                    // estimate engine, acquired separately via shouldRunComparison above.
                    val inActiveRide = state is RideState.Recording || state is RideState.Paused
                    val shouldConnect = meters.any { it.enabled } && inActiveRide
                    if (shouldRunComparison && !acquiredForComparison) {
                        engine.acquire(comparisonToken); acquiredForComparison = true
                    } else if (!shouldRunComparison && acquiredForComparison) {
                        engine.release(comparisonToken); acquiredForComparison = false
                    }
                    if (shouldConnect) antManager.connectMeters(meters.filter { it.enabled }.map { it.deviceNumber })
                    else antManager.disconnectAll()
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
                // Weather only feeds the estimator. Skip the expensive DataStore/GPS/HTTP work
                // unless we're recording AND the estimator is actually in use (paired virtual
                // device and/or comparison mode). In a "dynamics only, no estimator" setup the
                // engine is dormant, so this stays idle — zero estimator/weather cost.
                if (!isRecording || !engine.isActive()) {
                    delay(WEATHER_CHECK_INTERVAL_MS)
                    continue
                }
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
            val savedMeters = applicationContext.antMetersFlow().first()
            savedMeters.forEach { sm ->
                emitter.onNext(
                    com.enderthor.kpower.vdevice.RealPowerSource
                        .buildDevice(extension, sm.deviceNumber, meterDisplayName(sm.deviceNumber, sm.label), antManager).source
                )
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun connectDevice(uid: String, emitter: Emitter<DeviceEvent>) {
        Timber.d("Connect Device")
        if (uid.startsWith("real-power-")) {
            val dn = uid.substringAfterLast("-").toIntOrNull() ?: return
            val saved = savedMetersSnapshot.firstOrNull { it.deviceNumber == dn }
            com.enderthor.kpower.vdevice.RealPowerSource
                .buildDevice(extension, dn, meterDisplayName(dn, saved?.label), antManager)
                .connect(emitter, extension)
        } else {
            EstimatedPowerSource.fromUid(extension, uid, engine)?.connect(emitter, extension)
        }
    }

    /** Friendly Karoo display name for a real meter: "KPW <name>". When the rider hasn't named it, use
     *  the detected model ("KPW Garmin Rally 200") if the 0x50 page has been seen this service lifetime,
     *  else the bare number ("KPW #<deviceNumber>") — never just a number when we know the model. */
    private fun meterDisplayName(deviceNumber: Int, label: String?): String {
        val clean = label?.trim().orEmpty()
        if (!isAutoMeterLabel(clean, deviceNumber)) return "KPW $clean"
        val model = antManager.manufacturerFlow(deviceNumber).value
        return if (!model.isNullOrBlank()) "KPW $model" else "KPW #$deviceNumber"
    }

    /** Fire the battery in-ride alert, naming the meter. Colours/icon are @ColorRes/@DrawableRes (the
     *  host resolves them via Context.getColor — a packed ARGB would crash the ride app). */
    private fun dispatchBatteryAlert(meter: com.enderthor.kpower.ant.SavedMeter, critical: Boolean) {
        karooSystem.dispatch(
            InRideAlert(
                id = "kpower-battery-${if (critical) "critical" else "low"}-${meter.deviceNumber}",
                icon = R.drawable.ic_battery_alert,
                title = applicationContext.getString(
                    if (critical) R.string.battery_alert_critical_title else R.string.battery_alert_low_title
                ),
                detail = applicationContext.getString(
                    if (critical) R.string.battery_alert_critical_detail else R.string.battery_alert_low_detail,
                    meter.label,
                ),
                autoDismissMs = 15_000L,
                backgroundColor = if (critical) R.color.alert_bg_critical else R.color.alert_bg_low,
                textColor = R.color.alert_text,
            )
        )
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
            kotlinx.coroutines.flow.combine(
                // Subscribe to ELAPSED_TIME (1Hz while recording) when the comparison-mode toggle is
                // ON OR there is a saved meter (its dynamics are recorded automatically);
                // flatMapLatest re-subscribes on hot-toggle / meter add/remove.
                combine(
                    applicationContext.comparisonModeFlow(),
                    sharedMeters,
                ) { mode, meters -> mode || meters.any { it.enabled } }
                    .flatMapLatest { on -> if (on) karooSystem.streamDataFlow(DataType.Type.ELAPSED_TIME) else emptyFlow() },
                sharedMeters,
                applicationContext.comparisonModeFlow(),
            ) { elapsed, meters, comparisonMode -> FitTick(elapsed, meters, comparisonMode) }
                .collect { (elapsed, metersSnapshot, comparisonMode) ->
                    if (elapsed !is StreamState.Streaming) return@collect

                    // Dedup is automatic: if the KPower virtual device is the Karoo's bound power
                    // source, the estimate is already in the standard `power` stream, so we omit the
                    // est_* developer fields to avoid duplicating it.
                    val estPrimary = engine.estimateIsPrimary.value

                    val instant = engine.instantW.value
                    val p3s = engine.power3sW.value
                    val recordValues = mutableListOf<FieldValue>().apply {
                        // Estimate FIT fields are opt-in: only logged when "Log estimated power (FIT)"
                        // is ON. The estimator may be running anyway (a placed data field acquires
                        // it), but recording it to the FIT is a separate, explicit choice for
                        // comparison rides. writeEstimateFields dedups against the bound primary.
                        if (comparisonMode && writeEstimateFields(estPrimary)) {
                            if (!instant.isNaN()) add(FieldValue(fieldEstPower, instant))
                            if (!p3s.isNaN()) add(FieldValue(fieldEstPower3s, p3s))
                        }
                    }
                    // A real meter recorded through KPower always writes its pm*_ fields (the Karoo
                    // doesn't record the extension's meter natively). Only one meter can be enabled
                    // at a time, but we filter defensively.
                    metersSnapshot.filter { it.enabled }.forEach { m ->
                        val reader = antManager.meter(m.deviceNumber)
                        val pw = reader?.power?.value ?: Double.NaN
                        val cad = reader?.cadence?.value ?: Double.NaN
                        val bal = reader?.balanceRightPct?.value ?: Double.NaN
                        val tq = reader?.torque?.value ?: Double.NaN
                        if (!pw.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 0, "pm${m.slot + 1}_power", "W"), pw))
                        if (!cad.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 1, "pm${m.slot + 1}_cad", "rpm"), cad))
                        if (!bal.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 2, "pm${m.slot + 1}_balance", "%"), bal))
                        if (!tq.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 3, "pm${m.slot + 1}_torque", "Nm"), tq))
                    }
                    // Cycling-dynamics developer fields. Written for every recorded meter
                    // automatically — independent of the comparison-mode primary-source filter
                    // (writeMeterFields). If the user added a real meter through KPower they want
                    // its dynamics (the Karoo can't record them natively). Each metric field is
                    // nullable; skip the write when null/NaN so the FIT stays clean while coasting.
                    // They join the SAME record message emitted below.
                    metersSnapshot.filter { it.enabled }.forEach { m ->
                        val reader = antManager.meter(m.deviceNumber) ?: return@forEach
                        reader.tePs.value?.let { d ->
                            d.teLeftPct?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.TE_LEFT, "dyn_te_l", "%"), it)) }
                            d.teRightPct?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.TE_RIGHT, "dyn_te_r", "%"), it)) }
                            d.psLeftPct?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PS_LEFT, "dyn_ps_l", "%"), it)) }
                            d.psRightPct?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PS_RIGHT, "dyn_ps_r", "%"), it)) }
                        }
                        reader.forceAngleLeft.value?.let { d ->
                            d.startAngleDeg?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PP_START_L, "dyn_pp_start_l", "deg"), it)) }
                            d.endAngleDeg?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PP_END_L, "dyn_pp_end_l", "deg"), it)) }
                            d.startPeakDeg?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PEAK_START_L, "dyn_peak_start_l", "deg"), it)) }
                            d.endPeakDeg?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PEAK_END_L, "dyn_peak_end_l", "deg"), it)) }
                            d.torqueNm?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.TORQUE_LEFT, "dyn_torque_l", "Nm"), it)) }
                        }
                        reader.forceAngleRight.value?.let { d ->
                            d.startAngleDeg?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PP_START_R, "dyn_pp_start_r", "deg"), it)) }
                            d.endAngleDeg?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PP_END_R, "dyn_pp_end_r", "deg"), it)) }
                            d.startPeakDeg?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PEAK_START_R, "dyn_peak_start_r", "deg"), it)) }
                            d.endPeakDeg?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PEAK_END_R, "dyn_peak_end_r", "deg"), it)) }
                            d.torqueNm?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.TORQUE_RIGHT, "dyn_torque_r", "Nm"), it)) }
                        }
                        reader.pedalPosition.value?.let { d ->
                            d.leftPcoMm?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PCO_LEFT, "dyn_pco_l", "mm"), it.toDouble())) }
                            d.rightPcoMm?.let { recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.PCO_RIGHT, "dyn_pco_r", "mm"), it.toDouble())) }
                            recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.RIDER_POSITION, "dyn_rider_pos", ""), d.riderPosition.ordinal.toDouble()))
                        }
                        reader.barycenter.value?.angleDeg?.let {
                            recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.BARYCENTER, "dyn_baryc", "deg"), it))
                        }
                    }
                    if (recordValues.isNotEmpty()) emitter.onNext(WriteToRecordMesg(recordValues))

                    if (comparisonMode && writeEstimateFields(estPrimary)) {
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
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun onDestroy() {
        runCatching { antManager.disconnectAll() }
        runCatching { serviceScope.cancel() }
        karooSystem.disconnect()
        super.onDestroy()
    }
}

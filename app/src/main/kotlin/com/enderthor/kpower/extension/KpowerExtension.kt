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
import io.hammerhead.karooext.models.SavedDevices
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
import kotlinx.coroutines.flow.flowOf
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

/** Holder for the 3-way combine driving the ride-state connect gate. Carries only the ENABLED device
 *  numbers (not the full meter list) so battery/label persistence writes can't flap the channel. */
private data class RideGate(
    val state: RideState,
    val mode: Boolean,
    val enabledDns: List<Int>,
)

/** One battery-alert event. flatMapLatest only SELECTS the flow that produces these (pure); the arm/
 *  fire state machine lives entirely in the collector. code == null is a gate-only event (ride/meter
 *  changed, no battery sample) so the collector can still (dis)arm. */
private data class BatEvent(
    val ride: RideState,
    val meter: com.enderthor.kpower.ant.SavedMeter?,
    val enabled: Boolean,
    val code: Int?,
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
    /**
     * The SINGLE resolved "active meter device number (or null)" — one enabled meter drives every real
     * field. Computed once and shared so the ~16 real/dynamics fields don't each run their own
     * combine(gate, meters)+firstOrNull scan (Ki2 resolves one currentDeviceId the same way). Also
     * reused by the brand/battery collectors below.
     */
    private val sharedActiveDn by lazy {
        sharedMeters.map { ms -> ms.firstOrNull { it.enabled }?.deviceNumber }.distinctUntilChanged()
    }

    override val types: List<DataTypeImpl> by lazy {
        listOf(
            EstimatedPowerDataType(extension, TYPE_EST_INSTANT, engine) { it.instantW },
            EstimatedPowerDataType(extension, TYPE_EST_3S, engine) { it.power3sW },
            EstimatedPowerDataType(extension, TYPE_EST_NP, engine) { it.npW },
            EstimatedPowerDataType(extension, TYPE_EST_AVG, engine) { it.avgW },
            RealPowerDataType(extension, realFieldTypeId(0, "power"),   { sharedActiveDn }) { dn -> antManager.powerFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "3s"),      { sharedActiveDn }) { dn -> antManager.power3sFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "np"),      { sharedActiveDn }) { dn -> antManager.npFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "avg"),     { sharedActiveDn }) { dn -> antManager.avgFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "max"),     { sharedActiveDn }) { dn -> antManager.maxFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "10s"),     { sharedActiveDn }) { dn -> antManager.power10sFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "cadence"), { sharedActiveDn }) { dn -> antManager.cadenceFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "torque"),  { sharedActiveDn }) { dn -> antManager.torqueFlow(dn) },
            // Live cycling-dynamics fields driven by the SAME single active-meter flow. Each metricFlowFor
            // maps a STABLE manager-level dynamics sink to a Double (null -> NaN -> `---`). These sinks
            // survive meter reconnect: the bridges[dn] coroutine re-mirrors each new RawAntPowerMeter into
            // the same MutableStateFlow, so the field never freezes.
            // Two-sided dynamics shown as graphical "L/R" (e.g. "47/53"): balance, torque effectiveness,
            // pedal smoothness. Left value first (left pedal), right second.
            DualValueDataType(extension, dynFieldTypeId("balance"), { sharedActiveDn }) { dn -> antManager.balanceFlow(dn).map { r -> if (r.isNaN()) null to null else (100.0 - r) to r } },
            DualValueDataType(extension, dynFieldTypeId("te"), { sharedActiveDn }) { dn -> antManager.tePsFlow(dn).map { it?.teLeftPct to it?.teRightPct } },
            DualValueDataType(extension, dynFieldTypeId("ps"), { sharedActiveDn }) { dn -> antManager.tePsFlow(dn).map { it?.psLeftPct to it?.psRightPct } },
            DynamicsDataType(extension, dynFieldTypeId("pp-left"), { sharedActiveDn }) { dn -> antManager.forceLeftFlow(dn).map { it?.startAngleDeg ?: Double.NaN } },
            DynamicsDataType(extension, dynFieldTypeId("pp-right"), { sharedActiveDn }) { dn -> antManager.forceRightFlow(dn).map { it?.startAngleDeg ?: Double.NaN } },
            DynamicsDataType(extension, dynFieldTypeId("peakpp-left"), { sharedActiveDn }) { dn -> antManager.forceLeftFlow(dn).map { it?.startPeakDeg ?: Double.NaN } },
            DynamicsDataType(extension, dynFieldTypeId("peakpp-right"), { sharedActiveDn }) { dn -> antManager.forceRightFlow(dn).map { it?.startPeakDeg ?: Double.NaN } },
        )
    }

    // Memoize per (slot,kind): names/units are fixed, so a new DeveloperField every FIT
    // tick was a needless allocation. ConcurrentHashMap + computeIfAbsent: the FIT collect loop is
    // normally the only writer, but if the host ever overlaps two startFit subscriptions a plain
    // HashMap could ConcurrentModificationException mid-recording — this makes it crash-proof.
    // Key packed into an Int ((slot shl 8) or kind) so the 1 Hz FIT loop probes the cache without
    // allocating a Pair every tick.
    private val meterFieldCache = java.util.concurrent.ConcurrentHashMap<Int, DeveloperField>()
    private fun meterField(slot: Int, kind: Int, name: String, units: String) =
        meterFieldCache.computeIfAbsent((slot shl 8) or kind) {
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

        // Field calibration: feed the engine the ACTIVE real meter's live power, and persist the running
        // CdA + per-surface-Crr fit every 30 s while recording, so the settings UI can offer it after the
        // ride (the extension process may be killed at ride end).
        engine.realPowerProvider = {
            val dn = savedMetersSnapshot.firstOrNull { it.enabled }?.deviceNumber
            if (dn != null) antManager.meter(dn)?.power?.value ?: Double.NaN else Double.NaN
        }
        // Field calibration is a DEV tuning aid: the fitted CdA + per-surface Crr (with ± std error) are
        // written to the DIAGNOSTIC LOG so they can be analysed offline to refine the model coefficients.
        // It is NOT a user-facing feature (no Apply). The per-tick accumulation runs in the engine (cheap,
        // O(surfaces) scalars); here we only periodically SOLVE + log, gated on recording + the estimate
        // engine active (comparison mode) + diagnostic logging on, so the matrix solve isn't wasted.
        serviceScope.launch {
            var lastLoggedSamples = -1L
            while (isActive) {
                delay(30_000L)
                if (!isRecording || !engine.isActive() || !FileLogTree.enabled) continue
                val fit = engine.calibrationFit() ?: continue
                if (fit.samples == lastLoggedSamples) continue
                logCalibration(fit); lastLoggedSamples = fit.samples
            }
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
            sharedActiveDn
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

        // HYBRID name+battery (PRIMARY source): the Karoo already knows its paired sensors. SavedDevices
        // gives their real NAME + battery with NO raw channel and NO scan conflict — same data the Karoo
        // shows, just read from its cache. Adopt it onto our saved meters, matched by ANT device number.
        // The raw-channel identify (ComparisonScreen, after Add) is the FALLBACK for meters the Karoo
        // hasn't paired. A manual rename (userNamed) is never overwritten.
        serviceScope.launch {
            combine(sharedMeters, karooSystem.savedDevicesFlow()) { meters, sd -> meters to sd.devices }
                .collect { (meters, devices) ->
                    if (FileLogTree.enabled) devices.forEach { d ->
                        Timber.tag("KAROODEV").d(
                            "saved id=%s conn=%s name=%s serial=%s batt=%s",
                            d.id, d.connectionType, d.name, d.details.serialNumber, d.details.lastBattery,
                        )
                    }
                    if (meters.isEmpty()) return@collect
                    updateAntMeters(applicationContext) { current ->
                        current.map { m ->
                            val match = devices.firstOrNull { it.matchesAntNumber(m.deviceNumber) } ?: return@map m
                            val name = match.name.trim()
                            if (name.isEmpty() || m.userNamed || !isAutoMeterLabel(m.label, m.deviceNumber)) return@map m
                            m.copy(label = name)
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
                // PURE: only select the flow. No state mutation here — flatMapLatest re-runs on every
                // upstream emission and on flow re-assembly, so mutating arm flags here would be a
                // non-idempotent side effect. We emit a gate-only event (null code) for the non-recording
                // path so the collector can still (dis)arm.
                .flatMapLatest { (enabled, ride, meter) ->
                    if (!enabled || ride !is RideState.Recording || meter == null)
                        flowOf(BatEvent(ride, meter, enabled, null))
                    else
                        antManager.batteryFlow(meter.deviceNumber).map { code -> BatEvent(ride, meter, enabled, code) }
                }
                // The arm/fire state machine lives ONLY here — runs exactly once per collected event.
                .collect { ev ->
                    when (ev.ride) {
                        is RideState.Idle -> sawIdle = true
                        // Re-arm ONLY on Idle -> Recording (a genuine new ride), never on Paused -> Recording.
                        is RideState.Recording -> if (sawIdle) { firedLow = false; firedCritical = false; sawIdle = false }
                        else -> {} // Paused: keep flags, don't re-arm
                    }
                    if (!ev.enabled || ev.ride !is RideState.Recording || ev.meter == null) return@collect
                    // Re-arm when the ACTIVE meter changes (different battery) — inside the recording path.
                    if (ev.meter.deviceNumber != lastAlertDn) {
                        firedLow = false; firedCritical = false; lastAlertDn = ev.meter.deviceNumber
                    }
                    val code = ev.code ?: return@collect
                    when (batteryLevelOf(code)) {
                        BatteryLevel.CRITICAL -> if (!firedCritical) { firedCritical = true; dispatchBatteryAlert(ev.meter, critical = true) }
                        // Critical implies low is already covered: don't fire a (lesser) LOW after a
                        // CRITICAL has fired — the ANT+ code can oscillate 5->4->5 near the threshold,
                        // which would otherwise read as a confusing "recovered to low" message.
                        BatteryLevel.LOW -> if (!firedLow && !firedCritical) { firedLow = true; dispatchBatteryAlert(ev.meter, critical = false) }
                        else -> {}
                    }
                }
        }

        // Mirror the active Karoo ride-profile id (drives bike resolution in the engine)
        // and learn profiles (id+name) so the Settings UI can offer them for mapping.
        serviceScope.launch {
            // distinctUntilChanged: the host re-emits ActiveRideProfile periodically; without this we'd
            // do a DataStore read + JSON decode of known profiles on every (usually unchanged) emission.
            karooSystem.streamRideProfile().distinctUntilChanged().collect { profile ->
                // A genuine mid-ride profile change means a different bike → drop calibration samples
                // collected under the previous bike so the fit isn't mis-attributed.
                val prev = activeProfileIdFlow.value
                if (prev != null && prev != profile.id && isRecording) engine.resetCalibration()
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
                // Only the ENABLED device numbers, SORTED + deduped: a battery/label write (or a list
                // reorder) that doesn't change the enabled SET won't re-trigger this gate — so no channel
                // close/reopen flap mid-ride. (sorted() makes distinctUntilChanged order-insensitive.)
                sharedMeters.map { ms -> ms.filter { it.enabled }.map { it.deviceNumber }.sorted() }.distinctUntilChanged(),
            ) { state, mode, dns -> RideGate(state, mode, dns) }
                // Dedup is safe: RideState.Recording/Idle are payload-free objects, so
                // collapsing identical emissions never drops a real transition. And
                // engine.onRideState(state) is intentionally called before the comparison
                // shouldRun gate below, so NP/avg reset + pause-freeze work even when
                // comparison mode is OFF.
                .distinctUntilChanged()
                .collect { (state, mode, enabledDns) ->
                    engine.onRideState(state)
                    antManager.onRideState(state)
                    isRecording = state is RideState.Recording
                    // New per-ride diagnostic log on the transition INTO Recording (no-op when the
                    // toggle is off). Fires once per ride start, not on every emission.
                    if (isRecording && !wasRecording) {
                        FileLogTree.newRide(System.currentTimeMillis())
                    }
                    // Ride ended: log the final calibration fit the 30 s loop may not have captured
                    // (the FieldCalibrator keeps its data until the next ride start, so the fit is valid).
                    if (wasRecording && !isRecording && FileLogTree.enabled) {
                        serviceScope.launch { engine.calibrationFit()?.let { logCalibration(it) } }
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
                    val shouldConnect = enabledDns.isNotEmpty() && inActiveRide
                    if (shouldRunComparison && !acquiredForComparison) {
                        engine.acquire(comparisonToken); acquiredForComparison = true
                    } else if (!shouldRunComparison && acquiredForComparison) {
                        engine.release(comparisonToken); acquiredForComparison = false
                    }
                    if (shouldConnect) antManager.connectMeters(enabledDns)
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
                // Use the bike actually in use (mapped profile / active), NOT preferences[0] — otherwise
                // the weather policy (preferHeadwind) is read from the wrong bike whenever the active one
                // isn't first, and the estimator consumes mismatched weather.
                val cfg = com.enderthor.kpower.data.resolveActiveConfig(preferences, activeProfileIdFlow.value) ?: preferences[0]
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
                // coerceAtLeast(0): a backward GPS clock jump must not make weather look "fresh" forever.
                val tooOld = lastMs == 0L || (now - lastMs).coerceAtLeast(0L) >= WEATHER_MAX_AGE_MS

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

                    val hw = karooSystem.fetchHeadwindWeatherSnapshot(gps, isImperial, cfg.headwindWindUnit)
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

                val response = karooSystem.makeOpenMeteoHttpRequest(gps)

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

    /** Friendly Karoo display name for a real meter: "KPOWER <name>". When the rider hasn't named it, use
     *  the detected brand+model ("KPOWER Garmin Rally 200") if the 0x50 page has been seen this service
     *  lifetime, else the bare number ("KPOWER #<deviceNumber>") — never just a number when we know it. */
    private fun meterDisplayName(deviceNumber: Int, label: String?): String {
        val clean = label?.trim().orEmpty()
        if (!isAutoMeterLabel(clean, deviceNumber)) return "KPOWER $clean"
        val name = antManager.manufacturerFlow(deviceNumber).value
        return if (!name.isNullOrBlank()) "KPOWER $name" else "KPOWER #$deviceNumber"
    }

    /** Write the field-calibration fit to the diagnostic log (dev tuning aid). Includes the active bike id
     *  for context. Caller already gated on FileLogTree.enabled. */
    private suspend fun logCalibration(fit: com.enderthor.kpower.vdevice.FieldCalibrator.Fit) {
        val bikeId = com.enderthor.kpower.data.resolveActiveConfig(
            applicationContext.loadPreferencesFlow().first(), activeProfileIdFlow.value
        )?.id
        Timber.tag("CALIB").d(
            "bike=%s cda=%.3f±%.3f (%s) n=%d | %s",
            bikeId?.toString() ?: "?", fit.cda, fit.cdaSe, if (fit.cdaReliable) "ok" else "uncertain", fit.samples,
            fit.perSurface.joinToString(" ") { s ->
                "${s.surface}:${s.crrEff?.let { "%.4f".format(it) } ?: "—"}±${s.crrSe?.let { "%.4f".format(it) } ?: "—"}(${s.samples}${if (s.reliable) "" else "?"})"
            },
        )
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
                    // doesn't record the extension's meter natively). Exactly ONE meter is active — use
                    // firstOrNull, NOT filter{enabled}: two enabled meters would write conflicting values
                    // into the same record fields (the host keeps one → corrupt FIT).
                    metersSnapshot.firstOrNull { it.enabled }?.let { m ->
                        val reader = antManager.meter(m.deviceNumber)
                        val pw = reader?.power?.value ?: Double.NaN
                        val cad = reader?.cadence?.value ?: Double.NaN
                        val bal = reader?.balanceRightPct?.value ?: Double.NaN
                        val tq = reader?.torque?.value ?: Double.NaN
                        // power/cadence/torque stay DEVELOPER fields: standard power(7)/cadence(4) may be
                        // written by the Karoo for the bound sensor (don't clobber), and torque has no
                        // standard record field. Balance goes to the STANDARD left_right_balance (30),
                        // right-referenced like Garmin (bit7 set + right %), so tools show it natively.
                        if (!pw.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 0, "pm${m.slot + 1}_power", "W"), pw))
                        if (!cad.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 1, "pm${m.slot + 1}_cad", "rpm"), cad))
                        if (!bal.isNaN()) recordValues.add(FieldValue(com.enderthor.kpower.ant.FitRecordField.LEFT_RIGHT_BALANCE, ((bal.toInt().coerceIn(0, 100)) or 0x80).toDouble()))
                        if (!tq.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 3, "pm${m.slot + 1}_torque", "Nm"), tq))
                    }
                    // Cycling-dynamics developer fields. Written for every recorded meter
                    // automatically — independent of the comparison-mode primary-source filter
                    // (writeMeterFields). If the user added a real meter through KPower they want
                    // its dynamics (the Karoo can't record them natively). Each metric field is
                    // nullable; skip the write when null/NaN so the FIT stays clean while coasting.
                    // They join the SAME record message emitted below.
                    metersSnapshot.firstOrNull { it.enabled }?.let { m ->
                        val reader = antManager.meter(m.deviceNumber) ?: return@let
                        val F = com.enderthor.kpower.ant.FitRecordField
                        // TE / PS → STANDARD record fields (43-46), display % (host applies the profile scale).
                        reader.tePs.value?.let { d ->
                            d.teLeftPct?.let { recordValues.add(FieldValue(F.LEFT_TORQUE_EFFECTIVENESS, it)) }
                            d.teRightPct?.let { recordValues.add(FieldValue(F.RIGHT_TORQUE_EFFECTIVENESS, it)) }
                            d.psLeftPct?.let { recordValues.add(FieldValue(F.LEFT_PEDAL_SMOOTHNESS, it)) }
                            d.psRightPct?.let { recordValues.add(FieldValue(F.RIGHT_PEDAL_SMOOTHNESS, it)) }
                        }
                        // Power phase / peak are FIT ARRAY fields [start, end]. karoo-ext's FieldValue is a
                        // single scalar (no array index) — two FieldValue with the same fieldNum collide
                        // (the host keeps one), so we CANNOT write the standard array fields (69-72) and
                        // keep these as per-element DEVELOPER fields, which preserve both angles. Torque
                        // likewise has no standard record field → dev.
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
                        // PCO → STANDARD left/right_pco (67/68) in mm. Rider position has no standard
                        // record field → dev.
                        reader.pedalPosition.value?.let { d ->
                            d.leftPcoMm?.let { recordValues.add(FieldValue(F.LEFT_PCO, it.toDouble())) }
                            d.rightPcoMm?.let { recordValues.add(FieldValue(F.RIGHT_PCO, it.toDouble())) }
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
        // close() (not just disconnectAll) so any source-device-held channels + the manager's own scope
        // are torn down deterministically if the service is destroyed without the process dying.
        runCatching { antManager.close() }
        runCatching { serviceScope.cancel() }
        runCatching { karooSystem.disconnect() }   // can throw if the binder is already dead at teardown
        super.onDestroy()
    }
}

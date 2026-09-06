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
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

import com.enderthor.kpower.activity.mirrorSettingsToBackup
import com.enderthor.kpower.BuildConfig
import com.enderthor.kpower.R
import com.enderthor.kpower.ant.BatteryLevel
import com.enderthor.kpower.ant.batteryLevelOf
import com.enderthor.kpower.ant.isAutoMeterLabel
import com.enderthor.kpower.data.OpenMeteoCurrentWeatherResponse
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

/** Holder for the combine driving the ride-state connect gate. Carries only the ENABLED device numbers
 *  (not the full meter list) so battery/label persistence writes can't flap the channel.
 *  [meterScreenActive] is already the debounced/self-expiring signal (see [meterScreenActiveFlow]) —
 *  the collector uses it as-is, no age math needed. */
private data class RideGate(
    val state: RideState,
    val mode: Boolean,
    val enabledDns: List<Int>,
    val meterScreenActive: Boolean,
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

// Diagnostic-log Telegram upload cadence (KGhost pattern).
// Drain OFTEN (every 3 min) so the tail left for ride-end is small: a long interval let ~20 min of log
// pile up and be POSTed in one burst right as the Karoo saves+uploads the activity → the rider felt the
// ride "take longer to finish". Frequent small tails keep the ride-end send tiny (and it must finish
// fast — the OS kills the extension process at ride end). Each send only reads the not-yet-sent tail.
private const val LOG_SEND_INTERVAL_MS = 3 * 60_000L    // periodic upload while recording
private const val LOG_CHUNK_CHARS = 60_000             // target chars/chunk (then byte-capped below)
private const val MAX_CHUNK_BYTES = 90_000            // hard UTF-8 byte cap (host MakeHttpRequest limit ~100 KB)
private const val LOG_PERIODIC_MAX_CHUNKS = 6           // cap per periodic tick
// Bounded ride-end burst: with 3-min draining the tail is normally ~1 chunk. This ceiling only bites if
// the link was down mid-ride and the periodic fell behind — then the excess beyond 20 chunks (~1.8 MB) is
// simply not sent (the on-device file keeps everything), rather than monopolising the ride save with a
// multi-MB burst. Priority: don't collapse the ride finish.
private const val RIDE_END_MAX_CHUNKS = 20

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

    // Diagnostic-log Telegram upload state (KGhost pattern). Uploads only when the rider has diagnostic
    // logging on AND the build carries Telegram credentials. sentLogBytes is a BYTE offset into the
    // current log file: each send seeks there and reads only the not-yet-sent tail (not the whole file),
    // so the ride-end drain doesn't re-read multi-MB of already-sent log and starve the Karoo's own
    // activity save. Advances per SUCCESSFUL chunk so a failure retries from the same point; reset on
    // each new ride. logSendInFlight stops the periodic and ride-end drains overlapping.
    @Volatile private var sentLogBytes = 0L
    @Volatile private var logChunkSeq = 0
    // The file sentLogBytes indexes into — so a mid-ride size-rotation (logFile -> *-p1.log) resets the
    // offset to the new file instead of silently skipping the rest of the log.
    @Volatile private var lastSentLogFile: java.io.File? = null
    private val logSendInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var installId: String? = null
    private var logSendJob: kotlinx.coroutines.Job? = null

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
            // estimated-avg removed (on-screen): redundant — the Karoo shows avg natively when KPW Estimated
            // is the paired sensor. The FIT est_avg developer field is kept for post-ride comparison.
            RealPowerDataType(extension, realFieldTypeId(0, "power"),   { sharedActiveDn }) { dn -> antManager.powerFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "3s"),      { sharedActiveDn }) { dn -> antManager.power3sFlow(dn) },
            RealPowerDataType(extension, realFieldTypeId(0, "np"),      { sharedActiveDn }) { dn -> antManager.npFlow(dn) },
            // L/R balance (instant + session average), dual "L/R" Glance fields. Only the KPW-virtual
            // (offset) source needs these — with a native meter the Karoo shows balance itself.
            BalanceDataType(extension, "real-balance-0",     getString(R.string.real1_balance_name),     { sharedActiveDn }) { dn -> antManager.balanceFlow(dn) },
            BalanceDataType(extension, "real-balance-avg-0", getString(R.string.real1_balance_avg_name), { sharedActiveDn }) { dn -> antManager.balanceAvgFlow(dn) },
            // real-avg/max/10s/cadence removed (on-screen): the Karoo shows cadence natively, and
            // avg/max are derivable post-ride from the per-second pm{n}_power in the FIT. (cadenceFlow
            // itself stays — the KPW virtual sensor uses it to broadcast cadence to the Karoo.)
            // On-screen dynamics + torque fields REMOVED: the Karoo shows ALL of these natively for a
            // natively-paired meter (DataType.Type TORQUE_EFFECTIVENESS / PEDAL_SMOOTHNESS / TORQUE /
            // AVERAGE_TORQUE / MAX_TORQUE / PEDAL_POWER_BALANCE), so KPower's versions only duplicated them.
            // Everything still goes to the FIT from startFit — and torque is UNIQUE there (FIT has no standard
            // torque record field, so the Karoo can't record it; KPower writes pm_torque + dyn_torque_l/r).
            // balance/TE/PS go to the FIT as dev fields (for the KPW-virtual case). KPower's on-screen value
            // is now the estimate + the real power/cadence of a SECOND meter (S3) / a KPW-virtual source.
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

        // Mirror the durable settings so a corrupted store (process killed mid-write at ride end)
        // is refilled on the next open instead of resetting the rider's setup.
        applicationContext.mirrorSettingsToBackup()

        // Field calibration: feed the engine the ACTIVE real meter's live power, and persist the running
        // CdA + per-surface-Crr fit every 30 s while recording, so the settings UI can offer it after the
        // ride (the extension process may be killed at ride end).
        engine.realPowerProvider = {
            val dn = savedMetersSnapshot.firstOrNull { it.enabled }?.deviceNumber
            if (dn != null) antManager.powerFlow(dn).value else Double.NaN
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

        // Clear any stale meter-screen "radio active" stamp left by a PREVIOUS process (e.g. the settings
        // screen was killed without onDispose). A fresh service process means no ComparisonScreen scan is
        // in flight (same process — a kill takes both down), so this can't stomp an active scan. Without it,
        // a recent-but-orphaned stamp would suppress the meter channel for up to the backstop window — even
        // during a ride started in that window.
        serviceScope.launch { runCatching { saveMeterScreenActive(applicationContext, false) } }

        startLogSendLoop()

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
            sharedMeters.collect { savedMetersSnapshot = it; antManager.setMeterOffsets(it) }
        }

        // Brand auto-detect: once a connected meter reports its manufacturer (0x50 page), fill in the
        // saved label IF the rider hasn't named it. Never clobbers a manual rename (userNamed flag).
        // Only meaningful while the meter is connected (recording). The write is an ATOMIC transform
        // so it can't clobber a concurrent rename/toggle.
        serviceScope.launch {
            sharedActiveDn
                .flatMapLatest { dn -> if (dn == null) emptyFlow() else antManager.manufacturerShortFlow(dn).map { dn to it } }
                .collect { (dn, short) ->
                    if (short.isNullOrBlank()) return@collect
                    updateAntMeters(applicationContext) { meters ->
                        meters.map {
                            // Store the SHORT name (model/brand) so the "KPW <label>" sensor name stays short.
                            if (it.deviceNumber == dn && !it.userNamed && isAutoMeterLabel(it.label, dn))
                                it.copy(label = short) else it
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
            // Tracks whether we were IN A RIDE (Recording OR Paused) so newRide fires once on entry and
            // the ride-end actions fire once on exit. Using "in ride" (not Recording-only) is essential:
            // an autopause flips Recording->Paused, which must NOT look like a ride end (that would rotate
            // the log file + spam the ride-end upload on every traffic light).
            var wasInRide = false
            kotlinx.coroutines.flow.combine(
                karooSystem.consumerFlow<RideState>(),
                applicationContext.comparisonModeFlow(),
                // Only the ENABLED device numbers, SORTED + deduped: a battery/label write (or a list
                // reorder) that doesn't change the enabled SET won't re-trigger this gate — so no channel
                // close/reopen flap mid-ride. (sorted() makes distinctUntilChanged order-insensitive.)
                sharedMeters.map { ms -> ms.filter { it.enabled }.map { it.deviceNumber }.sorted() }.distinctUntilChanged(),
                // Meter-management screen activity: while it's active the gate releases the channel so the
                // screen's ANT scan isn't starved by our off-ride channel. Self-expiring — re-fires false
                // on its own once the backstop lapses, even with no other RideGate input changing.
                applicationContext.meterScreenActiveFlow(),
            ) { state, mode, dns, meterScreenActive -> RideGate(state, mode, dns, meterScreenActive) }
                // Dedup is safe: RideState.Recording/Idle are payload-free objects, so
                // collapsing identical emissions never drops a real transition. And
                // engine.onRideState(state) is intentionally called before the comparison
                // shouldRun gate below, so NP/avg reset + pause-freeze work even when
                // comparison mode is OFF.
                .distinctUntilChanged()
                .collect { (state, mode, enabledDns, meterScreenActive) ->
                    engine.onRideState(state)
                    antManager.onRideState(state)
                    isRecording = state is RideState.Recording
                    val inRide = state is RideState.Recording || state is RideState.Paused
                    // New per-ride diagnostic log once on ENTERING a ride (Idle -> Recording/Paused),
                    // NOT on autopause resume. No-op when the diagnostic toggle is off.
                    if (inRide && !wasInRide) {
                        FileLogTree.newRide(System.currentTimeMillis())
                        sentLogBytes = 0L; logChunkSeq = 0; lastSentLogFile = null   // upload the new file from start
                    }
                    // Ride ended (genuine in-ride -> Idle, NOT a startup/rebind Idle and NOT an autopause):
                    // log the final calibration fit + upload the remaining diagnostic log tail. Gating on
                    // wasInRide is essential — RideState.Idle re-emits on every host rebind/reconnect, so an
                    // unguarded send would spam uploads without any ride (the KGhost ride-end bug).
                    if (wasInRide && !inRide && FileLogTree.enabled) {
                        serviceScope.launch { engine.calibrationFit()?.let { logCalibration(it) } }
                        serviceScope.launch { runCatching { sendLogTail("ride-end", RIDE_END_MAX_CHUNKS) } }
                    }
                    wasInRide = inRide
                    // Estimate engine acquire/release stays tied to COMPARISON mode only;
                    // dynamics recording does not need the estimate engine running. Kept alive across
                    // Recording<->Paused (autopause) too, not just Recording: releasing on every traffic
                    // light and re-acquiring on resume rebuilt the whole pipeline (incl. up to a 5s
                    // UserProfile fetch), leaving est_power/est_power_3s missing from the FIT for several
                    // seconds after each pause. Session-accumulation freeze during pause is already handled
                    // by the engine's own `recording` flag (see RideResetGate / onRideState), and FIT writes
                    // stop anyway while paused (ELAPSED_TIME doesn't tick), so there's no correctness cost
                    // to leaving the pipeline warm.
                    val shouldRunComparison = mode && (state is RideState.Recording || state is RideState.Paused)
                    // Raw ANT meters connect whenever at least one saved meter is ENABLED AND KPower's
                    // meter-management screen isn't using the radio. Ride state NO LONGER gates this — the
                    // channel stays open off-ride too, so the meter reports live power/battery in the
                    // Karoo's native Sensors screen, not just during a ride. The only thing that must free
                    // the radio is ComparisonScreen's raw scan (add-meter): it runs on a SEPARATE
                    // AntPowerManager instance, so we can't see its `scanning` flag — instead it marks
                    // itself active via DataStore (meterScreenActiveAt), and we release while that's recent.
                    // Disable/delete a meter -> enabledDns shrinks -> connectMeters drops that channel.
                    // Comparison mode no longer drives the meter connection — it only drives the estimate
                    // engine, acquired separately via shouldRunComparison above. meterScreenActive is
                    // already the self-expiring signal from meterScreenActiveFlow(): no age math here.
                    val shouldConnect = enabledDns.isNotEmpty() && !meterScreenActive
                    if (shouldRunComparison && !acquiredForComparison) {
                        engine.acquire(comparisonToken); acquiredForComparison = true
                    } else if (!shouldRunComparison && acquiredForComparison) {
                        engine.release(comparisonToken); acquiredForComparison = false
                    }
                    // Always go through connectMeters (never disconnectAll() here): connectMeters(emptyList())
                    // releases all toggle-held channels WITHOUT stopScan(), so the gate can never kill an
                    // in-flight scan. disconnectAll() (which also stopScan()s) is reserved for close().
                    antManager.connectMeters(if (shouldConnect) enabledDns else emptyList())
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
    /**
     * Deja constancia de QUÉ meteo alimentó el cálculo y de qué proveedor vino. Se emite sólo al
     * refrescar (cada >=3 km o >=30 min), así que no ensucia el log.
     *
     * Por qué importa: `preferHeadwind` es true por defecto, así que con Headwind instalado el
     * viento NO sale de la API propia. Sin esta línea, tras la marcha hay que reconstruir el viento
     * a posteriori — y en una comparación real contra un medidor eso movió el residuo casi dos
     * puntos, suficiente para justificar cambios de modelo innecesarios.
     */
    private fun logWeather(src: String, r: OpenMeteoCurrentWeatherResponse) {
        if (!FileLogTree.enabled) return
        val c = r.current
        Timber.tag("WEATHER").d(
            "src=%s wind=%.1f dir=%.0f temp=%s press=%s",
            src, c.windSpeed, c.windDirection,
            c.temperature?.let { "%.1f".format(it) } ?: "—",
            c.surfacePressure?.let { "%.0f".format(it) } ?: "—",
        )
    }

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
                        // Qué proveedor alimentó el cálculo. Sin esto, tras la marcha no se puede
                        // saber si el viento vino de Headwind o de la API propia — y ese dato cambia
                        // la interpretación de cualquier comparación contra un medidor real.
                        logWeather("headwind", hw)
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
                    logWeather("own", data)
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
        // SHORT name so it fits the Karoo Sensors screen: "KPW Rally 200" (model) / "KPW Garmin" (brand) /
        // "KPW #6593" (unknown). The rider's own name (or the already-short auto-detected one) is used as-is;
        // a placeholder label falls back to the live short brand/model, else the bare device number.
        if (!isAutoMeterLabel(clean, deviceNumber)) return "KPW $clean"
        val short = antManager.manufacturerShortFlow(deviceNumber).value
        return if (!short.isNullOrBlank()) "KPW $short" else "KPW #$deviceNumber"
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

    /**
     * Periodically upload the current ride's diagnostic log to the developer's Telegram (KGhost pattern),
     * so a ride that crashes/ends before the ride-end send still gets most of its log off the device
     * (the OS kills the extension process at ride end). Dormant unless diagnostic logging is on AND the
     * build carries Telegram credentials; only runs during an active recording.
     */
    private fun startLogSendLoop() {
        if (!LogReporter.configured) return
        if (logSendJob?.isActive == true) return
        logSendJob = serviceScope.launch {
            while (isActive) {
                delay(LOG_SEND_INTERVAL_MS)
                if (!FileLogTree.enabled || !isRecording) continue
                runCatching { sendLogTail("periodic", LOG_PERIODIC_MAX_CHUNKS) }
            }
        }
    }

    /**
     * Upload the part of the current ride's log not yet sent (from byte offset [sentLogBytes]) to
     * Telegram via [LogReporter] (GPS redacted), in chunks of [LOG_CHUNK_CHARS] — each httpRequest body
     * must stay under the host Binder limit (~80 KB) or the POST fails. At most [maxChunks] per call.
     * Seeks to [sentLogBytes] and reads ONLY the remaining tail (never the whole file) + builds multipart
     * on IO (never Main). Advances [sentLogBytes] only per SUCCESSFUL chunk.
     */
    private suspend fun sendLogTail(prefix: String, maxChunks: Int) = withContext(Dispatchers.IO) {
        if (!FileLogTree.enabled || !LogReporter.configured) return@withContext
        // CAS so the periodic loop and the ride-end send can't both pass the guard and double-advance.
        if (!logSendInFlight.compareAndSet(false, true)) return@withContext
        try {
            val file = FileLogTree.currentLogFile() ?: return@withContext
            // A mid-ride size rotation swaps currentLogFile to *-pN.log; restart the offset for the new
            // file (else sentLogBytes indexes a different, shorter file and the rest is silently skipped).
            if (file != lastSentLogFile) { sentLogBytes = 0L; lastSentLogFile = file }
            FileLogTree.requestFlush()            // get the last buffered second onto disk first
            delay(400)
            // Read ONLY the not-yet-sent tail (seek sentLogBytes -> EOF), not the whole file. At ride end
            // the file can be several MB but the periodic uploads already drained the bulk; re-reading +
            // re-scanning the already-sent prefix on every send was the CPU/IO spike that competed with
            // the Karoo's own activity save at finish. RandomAccessFile reads a consistent snapshot up to
            // length(); appends after that are picked up on the next call. sentLogBytes always lands on a
            // char boundary (it only ever advances by whole-char chunk byte-lengths), so the seek never
            // splits a UTF-8 sequence.
            val tail = runCatching {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val len = raf.length()
                    if (len <= sentLogBytes) return@use null
                    raf.seek(sentLogBytes)
                    val buf = ByteArray((len - sentLogBytes).toInt())
                    raf.readFully(buf)
                    String(buf, Charsets.UTF_8)
                }
            }.getOrNull() ?: return@withContext
            if (tail.isEmpty()) return@withContext
            val id = installId ?: runCatching { applicationContext.getOrCreateInstallId() }.getOrNull()?.also { installId = it }
                ?: return@withContext
            val sid = FileLogTree.sessionId
            val ver = BuildConfig.VERSION_NAME
            var pos = 0                           // char offset within `tail` (contiguous from sentLogBytes)
            var sent = 0
            while (pos < tail.length && sent < maxChunks) {
                val hardEnd = minOf(pos + LOG_CHUNK_CHARS, tail.length)
                val nl = tail.lastIndexOf('\n', hardEnd - 1)   // cut on a line boundary when possible
                var end = if (nl > pos) nl + 1 else hardEnd
                var chunk = tail.substring(pos, end)
                var bytes = chunk.toByteArray(Charsets.UTF_8)
                // Keep the multipart body under the host's binder cap: the chunk's UTF-8 size (multi-byte
                // chars expand) must stay well under 100 KB. Shrink by halving until it fits.
                while (end > pos + 1 && bytes.size > MAX_CHUNK_BYTES) {
                    end = pos + (end - pos) / 2
                    chunk = tail.substring(pos, end)
                    bytes = chunk.toByteArray(Charsets.UTF_8)
                }
                if (chunk.isBlank()) break
                val lines = chunk.count { it == '\n' }
                val fileName = "kpower_v${ver}_${id}_${sid}_p${"%03d".format(logChunkSeq)}.log"
                val caption = "KPower log ($prefix)\nAnon tag: $id\nSession: $sid | v$ver | $lines lines"
                val res = LogReporter.sendLogFile(chunk, fileName, caption, karooSystem)
                // Advance the file byte offset by the chunk's UTF-8 size (chunks are contiguous from pos),
                // so the next send seeks right past what we just delivered.
                if (res.ok) { pos = end; sentLogBytes += bytes.size; logChunkSeq += 1; sent++ }
                else { Timber.w("KPower log upload (%s) chunk failed: %s", prefix, res.message); break }
            }
            if (sent > 0) Timber.i("KPower log upload (%s) ✓ — %d chunk(s), through byte %d", prefix, sent, sentLogBytes)
        } finally {
            logSendInFlight.set(false)
        }
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
            var lastEstGateLogMs = 0L
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

                    val estPrimary = engine.estimateIsPrimary.value
                    val instant = engine.instantW.value
                    val p3s = engine.power3sW.value
                    // Diagnostic (throttled, only when the rider enabled file logging): records the exact
                    // gate inputs the FIT writer sees, so a "comparison ride wrote no est_*" report can be
                    // settled from the log instead of guessing whether the toggle was actually on.
                    if (FileLogTree.enabled) {
                        val now = System.currentTimeMillis()
                        if (now - lastEstGateLogMs >= 5_000L) {
                            lastEstGateLogMs = now
                            Timber.d("FIT-est gate: comparison=%b estPrimary=%b instant=%.0f", comparisonMode, estPrimary, instant)
                        }
                    }
                    val recordValues = mutableListOf<FieldValue>().apply {
                        // Estimate FIT fields are opt-in: only logged when "Log estimated power (FIT)"
                        // (comparison mode) is ON. The estimator may be running anyway (a placed data
                        // field acquires it), but recording it to the FIT is a separate, explicit
                        // choice for comparison rides. Gated ONLY on comparison mode — never on whether
                        // the virtual device is connected (see shouldWriteEstimateToFit).
                        if (shouldWriteEstimateToFit(comparisonMode, estPrimary)) {
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
                        val pw = antManager.powerFlow(m.deviceNumber).value
                        val cad = reader?.cadence?.value ?: Double.NaN
                        val bal = reader?.balanceRightPct?.value ?: Double.NaN
                        val tq = reader?.torque?.value ?: Double.NaN
                        // ALL of power/cadence/torque/balance stay DEVELOPER fields. The Karoo writes the
                        // STANDARD power(7)/cadence(4)/balance(30)/TE/PS for a NATIVELY-paired meter, so
                        // writing those standard fields too would double-write/conflict (balance even with a
                        // different convention — Karoo bit7=0 vs Garmin/our bit7=1). Developer fields never
                        // collide: they carry the data for the KPW-virtual case and are a harmless separate
                        // column in the native case. (bal = right %, so left = 100 − right.)
                        if (!pw.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 0, "pm${m.slot + 1}_power", "W"), pw))
                        if (!cad.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 1, "pm${m.slot + 1}_cad", "rpm"), cad))
                        if (!tq.isNaN()) recordValues.add(FieldValue(meterField(m.slot, 3, "pm${m.slot + 1}_torque", "Nm"), tq))
                        if (!bal.isNaN()) {
                            val r = bal.coerceIn(0.0, 100.0)
                            recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.BALANCE_RIGHT, "dyn_balance_r", "%"), r))
                            recordValues.add(FieldValue(dynField(com.enderthor.kpower.ant.DynField.BALANCE_LEFT, "dyn_balance_l", "%"), 100.0 - r))
                        }
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
                        val DF = com.enderthor.kpower.ant.DynField
                        // TE / PS → DEVELOPER fields (the Karoo writes the STANDARD 43-46 for a native meter;
                        // see FitRecordField). Display % per side.
                        reader.tePs.value?.let { d ->
                            d.teLeftPct?.let { recordValues.add(FieldValue(dynField(DF.TE_LEFT, "dyn_te_l", "%"), it)) }
                            d.teRightPct?.let { recordValues.add(FieldValue(dynField(DF.TE_RIGHT, "dyn_te_r", "%"), it)) }
                            d.psLeftPct?.let { recordValues.add(FieldValue(dynField(DF.PS_LEFT, "dyn_ps_l", "%"), it)) }
                            d.psRightPct?.let { recordValues.add(FieldValue(dynField(DF.PS_RIGHT, "dyn_ps_r", "%"), it)) }
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

                    if (shouldWriteEstimateToFit(comparisonMode, estPrimary)) {
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

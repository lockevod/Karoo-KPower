package com.enderthor.kpower.ant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** Owns the ANT+ power-meter scan and the connected per-device readers. */
class AntPowerManager(private val context: Context) {

    private val _detectedDevices = MutableStateFlow<List<AntDeviceInfo>>(emptyList())
    val detectedDevices: StateFlow<List<AntDeviceInfo>> = _detectedDevices.asStateFlow()

    // True while the wildcard background-scan channel is open (persistent until stopScan, like the Karoo).
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    @Volatile private var scanChannel: RawAntScanChannel? = null
    private val detectedLock = Any()
    private val meters = LinkedHashMap<Int, RawAntPowerMeter>()

    // Scan-list fast identify: like the Karoo, when the wildcard scan finds a device we open its OWN
    // bidirectional channel (via the normal acquire/release lifecycle) so it actively requests the 0x50
    // manufacturer page and the name resolves in ~1s instead of waiting ~30s for the passive rotation.
    // The channel is held for the WHOLE scan session (not a short timeout): a meter may be ASLEEP now and
    // wake when the rider pedals — keeping the request channel open means the name appears within ~1s of
    // it waking, instead of staying "Identifying…" forever. Released as soon as the name resolves, or when
    // the scan stops. Keyed by device number → its in-flight identify job (also the dedup).
    private val identifyToken = Any()
    private val identifyJobs = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.Job>()

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val powerFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val cadenceFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val power3sFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val npFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    // NOTE: torque / avg-torque / max-torque / TE-PS flows were removed — the Karoo shows all of those
    // natively (DataType.Type), so KPower has no on-screen field for them; they reach the FIT straight
    // from the live reader (reader.torque / reader.tePs), not via a manager flow.
    // Brand name from the 0x50 page (device identity); persists across reconnect, never reset to null.
    private val manufacturerFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<String?>>()
    // SHORT name (model/brand) for the compact "KPW <short>" virtual-sensor label; persists like the brand.
    private val manufacturerShortFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<String?>>()
    // Battery status code (1=New..5=Critical) from the 0x52 page; persists like manufacturer.
    private val batteryFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Int?>>()
    private val bridges = HashMap<Int, kotlinx.coroutines.Job>()

    // Ref-counted lifecycle: a meter's raw ANT channel stays open while ANY holder (the toggle
    // path or a paired source device or any future holder) wants it. Guarded by synchronized(meters).
    private val holders = HashMap<Int, MutableSet<Any>>()
    // The legacy comparison/dynamics toggle path is just one holder, identified by this token.
    private val toggleToken = Any()
    private val toggleHeld = mutableSetOf<Int>()

    // Ride state mirrored from the extension. `recording` gates NP/avg accumulation per meter;
    // `pendingReset` requests are pushed to each meter (consumed on its single-threaded loop).
    @Volatile private var recording = false

    // Encapsulates "reset only on a genuine new ride (Idle->Recording), freeze during pause" — see
    // RideResetGate. Only touched under the @Synchronized onRideState() below.
    private val resetGate = com.enderthor.kpower.vdevice.RideResetGate()

    /**
     * Mirror the Karoo RideState so each meter's metrics track NP/avg only while recording and
     * reset on the Idle->Recording transition — NOT on Paused->Recording (autopause must not wipe
     * a meter's NP/avg). Reset is requested per-meter (requestMetricsReset) and consumed on the
     * per-meter 1Hz loop, so metrics.reset()/tick() stay on one thread.
     */
    @Synchronized
    fun onRideState(state: io.hammerhead.karooext.models.RideState) {
        if (resetGate.onRideState(state)) {
            synchronized(meters) { meters.values.forEach { it.requestMetricsReset() } }
        }
        recording = resetGate.recording
    }

    /** Stable power flow for a device number (survives connect/disconnect; NaN when not streaming). */
    fun powerFlow(deviceNumber: Int): kotlinx.coroutines.flow.StateFlow<Double> =
        powerFlows.getOrPut(deviceNumber) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

    /** Stable cadence flow for a device number (survives connect/disconnect; NaN when not streaming). */
    fun cadenceFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> =
        cadenceFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

    /** Stable 3s / NP power flows for a device number (survive reconnect, like powerFlow). */
    fun power3sFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = power3sFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun npFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = npFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

    /** Detected brand name for a device (from the 0x50 page); null until seen. */
    fun manufacturerFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<String?> = manufacturerFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }

    /** Detected SHORT name (model/brand) for the compact "KPW <short>" sensor label; null until seen. */
    fun manufacturerShortFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<String?> = manufacturerShortFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }

    /** Battery status code for a device (from the 0x52 page, 1=New..5=Critical); null until seen. */
    fun batteryFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Int?> = batteryFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }

    /** Live reader for a device number, or null if not connected. */
    fun meter(deviceNumber: Int): RawAntPowerMeter? = synchronized(meters) { meters[deviceNumber] }

    @Synchronized
    fun startScan() {
        stopScan()
        synchronized(detectedLock) { _detectedDevices.value = emptyList() }
        Timber.d("ANT startScan: wildcard background scan (raw channel, BIKE_POWER)")
        _scanning.value = true
        // Copy the Karoo EXACTLY (verified by decompiling io.hammerhead.sensorservice): ONE persistent
        // raw wildcard background-scan channel, devices demuxed from the extended channel-id, brand/model
        // + battery parsed from the 0x50/0x52 common pages in the broadcast bytes. No MultiDeviceSearch,
        // no per-device PCC → one channel, never ALL_CHANNELS_IN_USE. Released once on stopScan.
        val sc = RawAntScanChannel(context) { dn, payload -> handleScanBroadcast(dn, payload) }
        scanChannel = sc
        sc.start()
    }

    /** One BIKE_POWER broadcast from the wildcard scan: list the device, and parse the common pages for
     *  name (0x50: mfr id LE 4-5, model LE 6-7) and battery (0x52: coarse status = byte 7 bits 4-6). */
    private fun handleScanBroadcast(dn: Int, payload: ByteArray) {
        val isNew = synchronized(detectedLock) {
            if (_detectedDevices.value.none { it.deviceNumber == dn }) {
                _detectedDevices.value = _detectedDevices.value + AntDeviceInfo(name = "Power #$dn", deviceNumber = dn)
                true
            } else false
        }
        // A newly-seen device: open its own channel briefly to ACTIVELY request the 0x50 page (fast name),
        // exactly like the Karoo. The passive 0x50 parse below is kept as a free fallback.
        if (isNew) startIdentify(dn)
        // Passive fallback: if the 0x50 manufacturer page happens to come through the wildcard scan, use it.
        if (payload.size < 8) return
        if ((payload[0].toInt() and 0xFF) == 0x50) {
            val mfg = (payload[4].toInt() and 0xFF) or ((payload[5].toInt() and 0xFF) shl 8)
            val model = (payload[6].toInt() and 0xFF) or ((payload[7].toInt() and 0xFF) shl 8)
            updateResolved(dn, antDeviceDisplayName(mfg, model))   // FULL "Garmin Rally 200"
        }
    }

    /**
     * Open device [dn]'s own bidirectional channel for a few seconds so it requests + reports its
     * manufacturer page, then release it. Reuses the ref-counted acquire/release lifecycle (under the
     * [identifyToken]) and the stable [manufacturerFlow], so it shares all the channel recovery logic.
     * No-op if already identifying [dn], the scan is not running, or the manager is closed.
     *
     * @Synchronized (same monitor as [stopScan]): startIdentify runs on the scan channel's callback
     * thread, so without it a stopScan racing this method could snapshot+clear [identifyJobs] just
     * before the insertion below — the new job would then be neither cancelled nor tracked, leaking
     * its identify channel (a sleeping meter never resolves) past the scan session. Serialized, this
     * either runs before stopScan (job gets cancelled+cleared) or after (bails on scanChannel==null).
     */
    @Synchronized
    private fun startIdentify(dn: Int) {
        if (closed || scanChannel == null || identifyJobs.containsKey(dn)) return
        // Already know this device's brand (cached from a prior identify / connection)? Just publish it,
        // no need to open a channel.
        manufacturerFlow(dn).value?.let { updateResolved(dn, it); return }
        // Cap concurrent identify channels: each holds one bidirectional ANT channel for the whole scan
        // (an asleep meter never resolves), so an environment with many meters could otherwise exhaust the
        // ~14-channel pool and starve recording / the scan itself. The passive 0x50 parse stays the fallback.
        if (identifyJobs.size >= MAX_CONCURRENT_IDENTIFY) return
        // LAZY + put in the map BEFORE starting: if launched eagerly, the coroutine can run to completion
        // (its finally removing a not-yet-present map entry, a no-op) before this thread even reaches the
        // map assignment — leaving a stale completed Job that blocks re-identifying dn until stopScan.
        // Starting only after the map entry exists guarantees the finally's remove() always finds it.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                // acquire INSIDE the coroutine: if the job is cancelled before its body runs (stopScan
                // racing the launch), the channel hold is never taken, so it can't leak. The channel is
                // held until the name resolves or stopScan cancels this job (finally releases either way).
                acquire(dn, identifyToken)
                val name = manufacturerFlow(dn).filterNotNull().first()
                updateResolved(dn, name)
                Timber.d("ANT identify #%d resolved: %s", dn, name)
            } finally {
                release(dn, identifyToken)
                identifyJobs.remove(dn)
            }
        }
        identifyJobs[dn] = job
        job.start()
    }

    private fun updateResolved(dn: Int, name: String?) {
        synchronized(detectedLock) {
            val cur = _detectedDevices.value.firstOrNull { it.deviceNumber == dn }
            if (cur != null && cur.identifyTried && cur.resolvedName == name) return
            Timber.d("ANT scan #%d identified: %s", dn, name)
            _detectedDevices.value = _detectedDevices.value.map {
                if (it.deviceNumber == dn) it.copy(resolvedName = name, identifyTried = true) else it
            }
        }
    }


    @Synchronized
    fun stopScan() {
        runCatching { scanChannel?.stop() }
        scanChannel = null
        _scanning.value = false
        // Cancel in-flight identify jobs; each job's finally releases its channel hold so no identify
        // channel lingers (and its manufacturerFlow.first() suspension can't leak) after the scan stops.
        // Clear the map too: a job cancelled BEFORE its body started never runs its finally, so it would
        // otherwise leave a stale entry that blocks re-identifying that device on the next scan.
        identifyJobs.values.toList().forEach { it.cancel() }
        identifyJobs.clear()
    }

    private companion object {
        const val MAX_CONCURRENT_IDENTIFY = 3   // cap identify channels so they can't exhaust the ANT pool
    }

    /**
     * Open the raw ANT channel for [dn] if not already open, and start its bridge loop. No-op if a
     * meter for [dn] already exists. Must be called under synchronized(meters) (acquire holds it).
     */
    private fun ensureMeter(dn: Int) {
        if (meters.containsKey(dn)) return
        val m = RawAntPowerMeter(context, dn).also { it.connect() }
        // A meter added mid-recording starts its metrics fresh on the first loop tick.
        m.requestMetricsReset()
        meters[dn] = m
        val sink = powerFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val cadenceSink = cadenceFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val p3sSink = power3sFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val npSink = npFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val mfgSink = manufacturerFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        val mfgShortSink = manufacturerShortFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        val batterySink = batteryFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        bridges[dn] = scope.launch {
            // mirror power + cadence into the stable sinks (re-binds the NEW meter on reconnect)
            launch { m.power.collect { sink.value = it } }
            launch { m.cadence.collect { cadenceSink.value = it } }
            // torque / TE-PS / balance / power-phase are NOT mirrored to flows (no on-screen field — the
            // Karoo shows them natively); they reach the FIT straight from the live reader in startFit.
            // Brand + battery (identity-ish): mirror once known; never push null back.
            launch { m.manufacturerName.collect { if (it != null) mfgSink.value = it } }
            launch { m.manufacturerShort.collect { if (it != null) mfgShortSink.value = it } }
            launch { m.batteryStatus.collect { if (it != null) batterySink.value = it } }
            // watchdog: expire stale values (no event for >5s) so the FIT records a
            // gap, not frozen watts. Both child launches live under this one
            // bridges[dn] job, so cancelling it stops the mirror and the watchdog.
            // This single loop also drives the per-meter metrics: reset + tick run
            // here only, so PowerSourceMetrics stays single-threaded.
            while (isActive) {
                kotlinx.coroutines.delay(1_000)
                m.expireIfStale(System.currentTimeMillis())
                if (m.consumePendingReset()) m.metrics.reset()
                m.metrics.tick(m.power.value, recording)
                // 3s is a live rolling average: blank it when power is stale (NaN) so the
                // field goes `---` on a dropout instead of freezing. NP is a session aggregate
                // and holds its last accumulated value.
                p3sSink.value = if (m.power.value.isNaN()) Double.NaN else m.metrics.power3sW.value
                npSink.value = m.metrics.npW.value
            }
        }
    }

    /** Reset all stable sinks for [dn] to their "no data" value (NaN / null). */
    private fun resetSinks(dn: Int) {
        powerFlows[dn]?.value = Double.NaN
        cadenceFlows[dn]?.value = Double.NaN
        power3sFlows[dn]?.value = Double.NaN
        npFlows[dn]?.value = Double.NaN
    }

    /**
     * Cancel the bridge and close the raw ANT channel for [dn]. Must be called under
     * synchronized(meters) (release holds it). The sinks are reset in the bridge job's completion
     * handler — AFTER its child collectors have fully stopped — so a late in-flight emission cannot
     * resurrect a stale value on a sink (the toggle path masks this by reconnecting, but a source
     * device may release without any reconnect). If a new meter for [dn] was created meanwhile, its
     * bridge owns the sinks, so we skip the reset.
     */
    private fun dropMeter(dn: Int) {
        meters.remove(dn)?.disconnect()
        val job = bridges.remove(dn)
        if (job == null) { resetSinks(dn); return }
        job.invokeOnCompletion {
            synchronized(meters) { if (!meters.containsKey(dn)) resetSinks(dn) }
        }
        job.cancel()
    }

    /** Set by close(); after this acquire() is a no-op so no channel is opened on a cancelled scope. */
    @Volatile private var closed = false

    /** Register [token] as a holder of [dn]'s channel and ensure the channel is open. No-op once
     *  closed (the scope is cancelled, so ensureMeter's bridge would never run and the opened channel
     *  would leak with no path to disconnect). */
    fun acquire(dn: Int, token: Any) = synchronized(meters) {
        if (closed) return@synchronized
        holders.getOrPut(dn) { mutableSetOf() }.add(token)
        ensureMeter(dn)
    }

    /** Drop [token]'s hold on [dn]; close the channel only when no holders remain. */
    fun release(dn: Int, token: Any) = synchronized(meters) {
        val s = holders[dn] ?: return@synchronized
        s.remove(token)
        if (s.isEmpty()) {
            holders.remove(dn)
            dropMeter(dn)
        }
    }

    /**
     * Connect readers for exactly these device numbers on behalf of the toggle path; disconnect any
     * the toggle previously held but no longer wants. Other holders' meters are untouched.
     */
    @Synchronized
    fun connectMeters(deviceNumbers: List<Int>) {
        val wanted = deviceNumbers.toSet()
        (toggleHeld - wanted).forEach { release(it, toggleToken) }
        (wanted - toggleHeld).forEach { acquire(it, toggleToken) }
        toggleHeld.clear()
        toggleHeld.addAll(deviceNumbers)
    }

    /**
     * Release only the TOGGLE path's holdings; meters held by a source device (or any other holder)
     * SURVIVE. Also stops scanning.
     */
    @Synchronized
    fun disconnectAll() {
        stopScan()
        toggleHeld.toList().forEach { release(it, toggleToken) }
        toggleHeld.clear()
    }

    /**
     * Full teardown for an owner whose lifetime ends (e.g. the settings/scan screen being left, or the
     * extension service's onDestroy): release this manager's toggle holdings AND cancel its coroutine
     * scope so the scope + Job graph don't linger until GC. Safe for the service to call in onDestroy
     * because the service's manager is a per-instance `by lazy` — a re-created service gets a fresh one,
     * so there's no "closed manager reused" hazard. Do NOT call this if you intend to keep using the
     * same manager instance afterwards (it sets `closed` and cancels the scope permanently).
     */
    @Synchronized
    fun close() {
        closed = true
        disconnectAll()   // also stopScan()s the MultiDeviceSearch (not tied to scope)
        scope.cancel()
    }
}

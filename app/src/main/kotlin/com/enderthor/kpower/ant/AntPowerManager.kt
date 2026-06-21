package com.enderthor.kpower.ant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val powerFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val cadenceFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val power3sFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val power10sFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val npFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val avgFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val maxFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val torqueFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    // Stable dynamics sinks (survive reconnect; re-bound by bridges[dn] to each new meter).
    private val balanceFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<Double>>()
    private val tePsFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<TePsData?>>()
    private val forceLeftFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<ForceAngleData?>>()
    private val forceRightFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<ForceAngleData?>>()
    // Brand name from the 0x50 page (device identity); persists across reconnect, never reset to null.
    private val manufacturerFlows = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.flow.MutableStateFlow<String?>>()
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

    /**
     * Mirror the Karoo RideState so each meter's metrics track NP/avg only while recording and
     * reset on the Idle->Recording transition. Reset is requested per-meter (requestMetricsReset)
     * and consumed on the per-meter 1Hz loop, so metrics.reset()/tick() stay on one thread.
     */
    @Synchronized
    fun onRideState(state: io.hammerhead.karooext.models.RideState) {
        when (state) {
            is io.hammerhead.karooext.models.RideState.Recording -> {
                if (!recording) synchronized(meters) { meters.values.forEach { it.requestMetricsReset() } }
                recording = true
            }
            else -> recording = false
        }
    }

    /** Stable power flow for a device number (survives connect/disconnect; NaN when not streaming). */
    fun powerFlow(deviceNumber: Int): kotlinx.coroutines.flow.StateFlow<Double> =
        powerFlows.getOrPut(deviceNumber) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

    /** Stable cadence flow for a device number (survives connect/disconnect; NaN when not streaming). */
    fun cadenceFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> =
        cadenceFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

    /** Stable 3s/10s/NP/avg/max/torque flows for a device number (survive reconnect, like powerFlow). */
    fun power3sFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = power3sFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun power10sFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = power10sFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun npFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = npFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun avgFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = avgFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun maxFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = maxFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun torqueFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = torqueFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }

    /** Stable dynamics flows for a device number (survive reconnect, like powerFlow). */
    fun balanceFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<Double> = balanceFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
    fun tePsFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<TePsData?> = tePsFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
    fun forceLeftFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<ForceAngleData?> = forceLeftFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
    fun forceRightFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<ForceAngleData?> = forceRightFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }

    /** Detected brand name for a device (from the 0x50 page); null until seen. */
    fun manufacturerFlow(dn: Int): kotlinx.coroutines.flow.StateFlow<String?> = manufacturerFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }

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
        synchronized(detectedLock) {
            if (_detectedDevices.value.none { it.deviceNumber == dn }) {
                _detectedDevices.value = _detectedDevices.value + AntDeviceInfo(name = "Power #$dn", deviceNumber = dn)
            }
        }
        // Only the 0x50 manufacturer page is parsed during the scan (for the name). Battery isn't shown in
        // the settings list (stale/low value); the live battery field + low/critical alert use batteryFlow
        // from the recorded meter's channel during a ride.
        if (payload.size < 8) return
        if ((payload[0].toInt() and 0xFF) == 0x50) {
            val mfg = (payload[4].toInt() and 0xFF) or ((payload[5].toInt() and 0xFF) shl 8)
            val model = (payload[6].toInt() and 0xFF) or ((payload[7].toInt() and 0xFF) shl 8)
            updateResolved(dn, antDeviceDisplayName(mfg, model))   // FULL "Garmin Rally 200"
        }
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
        val p10sSink = power10sFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val npSink = npFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val avgSink = avgFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val maxSink = maxFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val torqueSink = torqueFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val balanceSink = balanceFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(Double.NaN) }
        val tePsSink = tePsFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        val forceLeftSink = forceLeftFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        val forceRightSink = forceRightFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        val mfgSink = manufacturerFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        val batterySink = batteryFlows.getOrPut(dn) { kotlinx.coroutines.flow.MutableStateFlow(null) }
        bridges[dn] = scope.launch {
            // mirror power into the stable sink
            launch { m.power.collect { sink.value = it } }
            // mirror cadence + torque into the stable sinks (re-binds the NEW meter on reconnect)
            launch { m.cadence.collect { cadenceSink.value = it } }
            launch { m.torque.collect { torqueSink.value = it } }
            // mirror dynamics into the stable sinks (re-binds the NEW meter on reconnect)
            launch { m.balanceRightPct.collect { balanceSink.value = it } }
            launch { m.tePs.collect { tePsSink.value = it } }
            launch { m.forceAngleLeft.collect { forceLeftSink.value = it } }
            launch { m.forceAngleRight.collect { forceRightSink.value = it } }
            // Brand + battery (identity-ish): mirror once known; never push null back.
            launch { m.manufacturerName.collect { if (it != null) mfgSink.value = it } }
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
                // field goes `---` on a dropout instead of freezing. NP/avg are session
                // aggregates and hold their last accumulated value.
                p3sSink.value = if (m.power.value.isNaN()) Double.NaN else m.metrics.power3sW.value
                p10sSink.value = if (m.power.value.isNaN()) Double.NaN else m.metrics.power10sW.value
                npSink.value = m.metrics.npW.value
                avgSink.value = m.metrics.avgW.value
                maxSink.value = m.metrics.maxW.value
            }
        }
    }

    /** Reset all stable sinks for [dn] to their "no data" value (NaN / null). */
    private fun resetSinks(dn: Int) {
        powerFlows[dn]?.value = Double.NaN
        cadenceFlows[dn]?.value = Double.NaN
        power3sFlows[dn]?.value = Double.NaN
        power10sFlows[dn]?.value = Double.NaN
        npFlows[dn]?.value = Double.NaN
        avgFlows[dn]?.value = Double.NaN
        maxFlows[dn]?.value = Double.NaN
        torqueFlows[dn]?.value = Double.NaN
        balanceFlows[dn]?.value = Double.NaN
        tePsFlows[dn]?.value = null
        forceLeftFlows[dn]?.value = null
        forceRightFlows[dn]?.value = null
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

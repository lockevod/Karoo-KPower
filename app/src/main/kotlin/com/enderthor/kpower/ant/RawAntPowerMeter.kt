package com.enderthor.kpower.ant

import android.content.Context
import com.enderthor.kpower.vdevice.PowerSourceMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI

/**
 * Reads ONE ANT+ bike power meter by device number over a RAW ANT channel ([RawAntChannel])
 * instead of antpluginlib. This lets us also see the
 * Cycling Dynamics pages (0x13 / 0xE0 / 0xE1 / 0xE2 / 0x14) the plugin library does not expose.
 *
 * The power/cadence/balance/torque surface follows the StateFlow contract (NaN until first
 * sample), so [AntPowerManager] consumes it with no other change. Per the ANT+ Bicycle
 * Power profile the meter does not broadcast torque on the standard power-only page, so torque is
 * recomputed from power & cadence: τ = P / (2π·rpm/60).
 */
class RawAntPowerMeter(
    private val context: Context,
    val deviceNumber: Int,
) {
    private val _power = MutableStateFlow(Double.NaN)
    private val _cadence = MutableStateFlow(Double.NaN)
    private val _balanceRightPct = MutableStateFlow(Double.NaN)
    private val _torque = MutableStateFlow(Double.NaN)
    val power: StateFlow<Double> = _power.asStateFlow()
    val cadence: StateFlow<Double> = _cadence.asStateFlow()
    val balanceRightPct: StateFlow<Double> = _balanceRightPct.asStateFlow()
    val torque: StateFlow<Double> = _torque.asStateFlow()

    private val _forceAngleLeft = MutableStateFlow<ForceAngleData?>(null)
    private val _forceAngleRight = MutableStateFlow<ForceAngleData?>(null)
    private val _pedalPosition = MutableStateFlow<PedalPositionData?>(null)
    private val _tePs = MutableStateFlow<TePsData?>(null)
    private val _barycenter = MutableStateFlow<TorqueBarycenterData?>(null)
    /** Brand name from the 0x50 manufacturer page; null until seen. Device identity — survives reset. */
    private val _manufacturerName = MutableStateFlow<String?>(null)
    val manufacturerName: StateFlow<String?> = _manufacturerName.asStateFlow()

    /** Latest parsed Cycling Dynamics models; null until first seen / after a dropout reset. */
    val forceAngleLeft: StateFlow<ForceAngleData?> = _forceAngleLeft.asStateFlow()
    val forceAngleRight: StateFlow<ForceAngleData?> = _forceAngleRight.asStateFlow()
    val pedalPosition: StateFlow<PedalPositionData?> = _pedalPosition.asStateFlow()
    val tePs: StateFlow<TePsData?> = _tePs.asStateFlow()
    val barycenter: StateFlow<TorqueBarycenterData?> = _barycenter.asStateFlow()

    /**
     * Per-source 3s / NP / average metrics, symmetric with the estimate engine. NOT fed from
     * inside this class: the manager's single-threaded per-meter 1Hz loop drives tick()/reset(),
     * keeping the metric objects on one thread. Ride-boundary reset is requested via
     * requestMetricsReset()/consumePendingReset(); disconnect() does NOT touch metrics.
     */
    val metrics = PowerSourceMetrics()

    /** Ride-boundary reset request, set off-thread (onRideState), consumed on the 1Hz loop. */
    @Volatile private var pendingMetricsReset = false

    /** Request a metrics reset on the next loop tick (Idle->Recording, or new meter). */
    fun requestMetricsReset() { pendingMetricsReset = true }

    /** Read-and-clear the pending-reset flag. Call from the per-meter loop thread only. */
    fun consumePendingReset(): Boolean {
        if (!pendingMetricsReset) return false
        pendingMetricsReset = false
        return true
    }

    @Volatile private var channel: RawAntChannel? = null

    /** Timestamp (ms) of the most recent ANT event; 0 until the first sample arrives. */
    @Volatile private var lastEventMs: Long = 0L

    // ── Torque-based power (0x11/0x12) state ────────────────────────────────────────────────────
    // @Volatile because onPayload runs on the ANT callback thread, and a channel reopen registers a
    // FRESH event handler that may be invoked from a different thread — volatile publishes the last
    // delta state across that boundary so a reopen can't read a stale prevTorque and emit a spike.
    /** Previous torque page, for the delta that yields power/cadence. Null until the first one. */
    @Volatile private var prevTorque: TorqueData? = null
    /** Wall-clock of the last NEW rotation event (Δevent>0); drives coast-to-zero when pedalling stops. */
    @Volatile private var lastTorqueEventChangeMs: Long = 0L
    /**
     * True once any torque page has arrived. A torque-based meter (Garmin Rally/Vector, most
     * crank/spider meters) ALSO emits the 0x10 power-only page but with its instantaneous-power
     * field at 0 — so once we've seen a torque page we IGNORE 0x10's power and let the torque path
     * own power/cadence/torque (0x10 still provides balance).
     */
    @Volatile private var torquePageSeen = false

    fun connect() {
        // Capture and detach the old channel BEFORE starting the new one. Its async stop() now
        // races nothing: the old channel's own `stopped` guard prevents an orphan, and the field
        // already points at the freshly-started channel.
        val old = channel
        channel = RawAntChannel(context, deviceNumber, ::onPayload).also { it.start() }
        runCatching { old?.stop() }
    }

    /** Dispatch a raw broadcast payload by page number (b0). Unknown pages are ignored. */
    private fun onPayload(p: ByteArray) {
        if (p.isEmpty()) return
        when (p[0].toInt() and 0xFF) {
            CyclingDynamicsParser.PAGE_POWER_ONLY -> {
                val d = CyclingDynamicsParser.parsePowerOnly(p) ?: return
                // A torque-based meter also sends 0x10 but with power=0; once we've seen a torque
                // page, that path owns power/cadence/torque and we take only balance from 0x10.
                if (!torquePageSeen) {
                    _power.value = d.powerW ?: Double.NaN
                    _cadence.value = d.cadenceRpm ?: Double.NaN
                    _torque.value = computeTorque(d.powerW, d.cadenceRpm)
                } else if (_cadence.value.isNaN()) {
                    d.cadenceRpm?.let { _cadence.value = it }   // seed cadence before 2nd torque frame
                }
                _balanceRightPct.value = d.balanceRightPct ?: Double.NaN
                lastEventMs = System.currentTimeMillis()
            }
            CyclingDynamicsParser.PAGE_WHEEL_TORQUE, CyclingDynamicsParser.PAGE_CRANK_TORQUE -> {
                CyclingDynamicsParser.parseTorque(p)?.let { onTorquePage(it) }
            }
            CyclingDynamicsParser.PAGE_TE_PS -> {
                CyclingDynamicsParser.parseTePs(p)?.let { _tePs.value = it }
                lastEventMs = System.currentTimeMillis()
            }
            CyclingDynamicsParser.PAGE_RIGHT_FORCE_ANGLE -> {
                CyclingDynamicsParser.parseForceAngle(p, isLeft = false)
                    ?.let { _forceAngleRight.value = it }
                lastEventMs = System.currentTimeMillis()
            }
            CyclingDynamicsParser.PAGE_LEFT_FORCE_ANGLE -> {
                CyclingDynamicsParser.parseForceAngle(p, isLeft = true)
                    ?.let { _forceAngleLeft.value = it }
                lastEventMs = System.currentTimeMillis()
            }
            CyclingDynamicsParser.PAGE_PEDAL_POSITION -> {
                val d = CyclingDynamicsParser.parsePedalPosition(p) ?: return
                _pedalPosition.value = d
                // Cadence fallback: if the power-only page had no cadence, use this page's, and
                // recompute torque from the cached power so the snapshot stays consistent
                // (finite power + cadence must not leave torque NaN).
                if (_cadence.value.isNaN() && d.cadenceRpm != null) {
                    _cadence.value = d.cadenceRpm
                    val pw = _power.value
                    if (!pw.isNaN()) _torque.value = computeTorque(pw, d.cadenceRpm)
                }
                lastEventMs = System.currentTimeMillis()
            }
            CyclingDynamicsParser.PAGE_TORQUE_BARYCENTER -> {
                CyclingDynamicsParser.parseTorqueBarycenter(p)?.let { _barycenter.value = it }
                lastEventMs = System.currentTimeMillis()
            }
            CyclingDynamicsParser.PAGE_MANUFACTURER -> {
                // Device identity (brand). Not a "live" value, so it does NOT update lastEventMs and
                // is not cleared by reset()/expireIfStale.
                CyclingDynamicsParser.parseManufacturer(p)?.let {
                    _manufacturerName.value = AntManufacturers.name(it.manufacturerId)
                }
            }
        }
    }

    /**
     * Torque-page (0x11/0x12) handler: derive power/cadence/torque from the delta to the previous
     * page. When no new rotation event has arrived for [COAST_MS] (rider stopped pedalling), coast
     * power/cadence/torque to 0 instead of holding the last value forever.
     */
    private fun onTorquePage(d: TorqueData) {
        torquePageSeen = true
        val now = System.currentTimeMillis()
        val prev = prevTorque
        if (prev != null) {
            val tp = CyclingDynamicsParser.torquePower(prev, d)
            if (tp != null) {
                _power.value = tp.powerW
                _cadence.value = tp.cadenceRpm
                _torque.value = tp.torqueNm
                lastTorqueEventChangeMs = now
            } else if (now - lastTorqueEventChangeMs > COAST_MS) {
                // No new crank event for a while → coasting/stopped. Zero power/cadence/torque AND
                // clear the cycling-dynamics models so the FIT records a gap, not the last frozen
                // TE/PS/angles/position (those pages stop while coasting but their StateFlows would
                // otherwise hold the last value until the 5s stale reset).
                _power.value = 0.0; _cadence.value = 0.0; _torque.value = 0.0
                _tePs.value = null; _forceAngleLeft.value = null; _forceAngleRight.value = null
                _pedalPosition.value = null; _barycenter.value = null
            }
            // else: repeated frame within the coast window → hold the last values.
        } else {
            // First torque frame: seed cadence and START the coast timer, so an all-implausible
            // stream (every torquePower rejected by the clamp) still coasts to 0 instead of holding.
            if (_cadence.value.isNaN()) d.cadenceRpm?.let { _cadence.value = it }
            lastTorqueEventChangeMs = now
        }
        prevTorque = d
        lastEventMs = now
    }

    /** τ = P / (2π·rpm/60). NaN if power null or cadence null/≤0. */
    private fun computeTorque(powerW: Double?, cadenceRpm: Double?): Double {
        if (powerW == null || cadenceRpm == null || cadenceRpm <= 0.0) return Double.NaN
        return powerW / (2.0 * PI * cadenceRpm / 60.0)
    }

    /** Reset values to NaN if no ANT event arrived within [staleMs] (silent dropout w/o DEAD). */
    fun expireIfStale(nowMs: Long, staleMs: Long = 5_000L) {
        if (lastEventMs != 0L && nowMs - lastEventMs > staleMs) reset()
    }

    private fun reset() {
        _power.value = Double.NaN; _cadence.value = Double.NaN
        _balanceRightPct.value = Double.NaN; _torque.value = Double.NaN
        _forceAngleLeft.value = null; _forceAngleRight.value = null
        _pedalPosition.value = null; _tePs.value = null; _barycenter.value = null
        // prevTorque cleared so the next frame re-seeds (no stale delta). torquePageSeen is LATCHED:
        // it's device identity (this meter reports power via torque pages), so a >5s dropout must not
        // flip it back and let a 0x10 power=0 frame momentarily publish 0 W on reacquire.
        prevTorque = null; lastTorqueEventChangeMs = 0L
    }

    fun disconnect() {
        runCatching { channel?.stop() }
        channel = null; reset()
    }

    private companion object {
        /** Hold the last torque-derived power for this long without a new crank event, then coast to 0. */
        const val COAST_MS = 3_000L
    }
}

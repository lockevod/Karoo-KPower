package com.enderthor.kpower.ant

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.dsi.ant.AntService
import com.dsi.ant.channel.AntChannel
import com.dsi.ant.channel.AntChannelProvider
import com.dsi.ant.channel.IAntChannelEventHandler
import com.dsi.ant.channel.PredefinedNetwork
import com.dsi.ant.message.ChannelId
import com.dsi.ant.message.ChannelType
import com.dsi.ant.message.fromant.BroadcastDataMessage
import com.dsi.ant.message.fromant.MessageFromAntType
import com.dsi.ant.message.ipc.AntMessageParcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens a raw ANT+ SLAVE channel bound to ONE bike-power device number (type 11, RF 57,
 * period 8182) and delivers each broadcast payload to [onPayload]. Replaces antpluginlib for a
 * meter so we can also see Cycling Dynamics pages it does not expose. Proven on Karoo hardware
 * via AntChannelProbe (PredefinedNetwork.ANT_PLUS, 14 channels free).
 */
class RawAntChannel(
    private val context: Context,
    private val deviceNumber: Int,
    private val onPayload: (ByteArray) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var antService: AntService? = null
    @Volatile private var channel: AntChannel? = null

    /** Set true by stop(); after this no new channel may be opened or reopened. */
    @Volatile private var stopped = false

    /**
     * CONSECUTIVE open/death failures since the link was last healthy. Reset to 0 the moment we
     * receive a real broadcast page (the only proof the link actually works — an open() that
     * succeeds but never delivers data still counts against the budget). We stop retrying after
     * [MAX_FAILURES] to avoid a tight death/reopen loop; a later connect() (e.g. next ride) starts
     * fresh.
     */
    @Volatile private var failures = 0

    /** Guards against two open() attempts running at once (death-retry racing a late rebind). */
    @Volatile private var opening = false

    // ── Diagnostic ANT page logging (purely additive; only touched when FileLogTree.enabled) ─────
    /** Lifetime count of every page seen, keyed by page number, for the periodic SUMMARY line. */
    private val pageCounts = ConcurrentHashMap<Int, Long>()
    /** Per-page last-logged wall-clock time, to throttle the per-page diagnostic lines. */
    private val lastPageLogMs = ConcurrentHashMap<Int, Long>()
    /** Guards the SUMMARY loop so it is launched at most once. */
    @Volatile private var summaryStarted = false

    // Max instantaneous power / cadence seen across EVERY 0x10 frame (not just the throttled log
    // lines), plus whether the accumulated-power total ever changed. Answers definitively "did the
    // meter ever send non-zero power/cadence?" without un-throttling the per-page lines.
    @Volatile private var maxInstPower = -1
    @Volatile private var maxCadence = -1
    @Volatile private var firstAccumPower = -1
    @Volatile private var lastAccumPower = -1

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            antService = AntService(binder)
            // Guard the bind/open race: a stop() that beat onServiceConnected must not open.
            if (!stopped) scope.launch { open() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { antService = null }
    }

    fun start() {
        startSummaryLoop()
        runCatching { AntService.bindService(context, conn) }
    }

    /**
     * Launch the periodic page-count SUMMARY loop on [scope] (so stop()'s scope.cancel() kills it).
     * The loop itself is always running but is a no-op unless FileLogTree.enabled, and emits nothing
     * until at least one page has been counted.
     */
    private fun startSummaryLoop() {
        if (summaryStarted) return
        summaryStarted = true
        scope.launch {
            while (true) {
                delay(15_000)
                if (com.enderthor.kpower.extension.FileLogTree.enabled && pageCounts.isNotEmpty()) {
                    val accumMoved = firstAccumPower >= 0 && lastAccumPower != firstAccumPower
                    Timber.tag("ANTLOG").d(
                        "dev=%d SUMMARY %s | 0x10 maxW=%d maxRpm=%d accumMoved=%b",
                        deviceNumber,
                        pageCounts.entries.sortedBy { it.key }
                            .joinToString(" ") { "0x%02X=%d".format(it.key, it.value) },
                        maxInstPower, maxCadence, accumMoved,
                    )
                }
            }
        }
    }

    /**
     * Log a single raw ANT broadcast page to the diagnostic file logger. Caller MUST already have
     * checked FileLogTree.enabled, so the hex string (built here) is never produced on the hot path
     * while logging is off. Counts every page (for SUMMARY) and throttles per page: 0x10 at most
     * once / 5 s, every other page (incl. unknown) at most once / 1 s.
     */
    private fun logPage(p: ByteArray) {
        if (p.isEmpty()) return
        val page = p[0].toInt() and 0xFF
        pageCounts.merge(page, 1L, Long::plus)
        // Track power/cadence extremes over EVERY power-only frame (the per-line log below is
        // throttled, so without this we'd only sample ~1 frame / 5 s).
        if (page == 0x10 && p.size >= 8) {
            val cad = p[3].toInt() and 0xFF
            val instPower = (p[6].toInt() and 0xFF) or ((p[7].toInt() and 0xFF) shl 8)
            val accum = (p[4].toInt() and 0xFF) or ((p[5].toInt() and 0xFF) shl 8)
            if (instPower > maxInstPower) maxInstPower = instPower
            if (cad != 0xFF && cad > maxCadence) maxCadence = cad
            if (firstAccumPower < 0) firstAccumPower = accum
            lastAccumPower = accum
        }
        val now = System.currentTimeMillis()
        val minIntervalMs = if (page == 0x10) 5000L else 1000L
        val last = lastPageLogMs[page]
        if (last != null && now - last < minIntervalMs) return
        lastPageLogMs[page] = now
        val prefix = if (page in KNOWN_PAGES) "" else "UNKNOWN "
        val hex = p.joinToString(" ") { "%02X".format(it) }
        Timber.tag("ANTLOG").d("%sdev=%d PAGE 0x%02X payload=%s", prefix, deviceNumber, page, hex)
    }

    /**
     * Acquires a FRESH channel each call (so it is safe to call again on a reopen after death),
     * then assigns/configures/opens it. If stop() raced this call, the freshly-acquired channel is
     * released immediately and we return without opening, so no orphan channel is left.
     */
    private suspend fun open() {
        if (stopped || opening) return
        opening = true
        try {
            runCatching {
                // The ANT Radio Service can be mid-restart (ChannelNotAvailableException
                // SERVICE_INITIALIZING) or not yet rebound — treat a null provider as a transient
                // failure and retry, don't give up silently.
                val provider: AntChannelProvider = antService?.channelProvider
                    ?: throw IllegalStateException("ANT channelProvider not ready")
                val ch = provider.acquireChannel(context, PredefinedNetwork.ANT_PLUS)
                // stop() may have raced the async bind/acquire: if so, give the slot straight back.
                if (stopped) {
                    runCatching { ch.close() }
                    runCatching { ch.release() }
                    return
                }
                channel = ch
                ch.setChannelEventHandler(object : IAntChannelEventHandler {
                    override fun onReceiveMessage(type: MessageFromAntType?, msg: AntMessageParcel?) {
                        if (type == MessageFromAntType.BROADCAST_DATA && msg != null) {
                            // Receiving a page proves the link is healthy — clear the failure budget
                            // so a death hours later still gets a full set of retries.
                            failures = 0
                            runCatching {
                                val payload = BroadcastDataMessage(msg).payload
                                // Diagnostic ANT page logging — gated so it costs ~nothing when off.
                                if (com.enderthor.kpower.extension.FileLogTree.enabled) logPage(payload)
                                onPayload(payload)
                            }
                        }
                    }
                    override fun onChannelDeath() { reopenAfterDeath() }
                })
                ch.assign(ChannelType.SLAVE_RECEIVE_ONLY); delay(50)
                ch.setChannelId(ChannelId(deviceNumber, 11, 0)); delay(50)  // SPECIFIC device, type 11
                ch.setRfFrequency(57); delay(50)
                ch.setPeriod(8182); delay(50)
                // Re-check right before open(): a stop() during the configure delays must win.
                if (stopped) {
                    runCatching { ch.close() }
                    runCatching { ch.unassign() }
                    runCatching { ch.release() }
                    return
                }
                ch.open()
                Timber.d("RawAntChannel #%d open", deviceNumber)
                if (com.enderthor.kpower.extension.FileLogTree.enabled)
                    Timber.tag("ANTLOG").d("dev=%d channel open (type=11 rf=57 period=8182)", deviceNumber)
            }.onFailure { e ->
                Timber.e(e, "RawAntChannel #%d open failed", deviceNumber)
                // Drop any half-acquired channel, then back off and retry — the failure is usually
                // transient (service restarting). scheduleReopen() enforces the budget/backoff.
                val dead = channel
                channel = null
                runCatching { dead?.close() }
                runCatching { dead?.unassign() }
                runCatching { dead?.release() }
                scheduleReopen(e.message ?: "open failure")
            }
        } finally {
            opening = false
        }
    }

    /**
     * I1 recovery: on a radio-level channel death, forget the dead channel and schedule a fresh
     * reopen. Shares the [failures] budget/backoff with open()-failure so a SERVICE_INITIALIZING
     * (ANT service restart) is ridden out rather than giving up after one try.
     */
    private fun reopenAfterDeath() {
        Timber.w("RawAntChannel #%d death", deviceNumber)
        if (stopped) return
        // Forget the dead channel so open() acquires a brand-new one instead of reusing it.
        val dead = channel
        channel = null
        runCatching { dead?.close() }
        runCatching { dead?.unassign() }
        runCatching { dead?.release() }
        scheduleReopen("channel death")
    }

    /**
     * Back off and retry open(), with the delay growing per consecutive failure (capped) so a
     * multi-second ANT-service restart is survived. Gives up after [MAX_FAILURES] consecutive
     * failures with no data in between; the next connect() resets the channel and the budget.
     */
    private fun scheduleReopen(reason: String) {
        if (stopped) return
        if (++failures > MAX_FAILURES) {
            Timber.e("RawAntChannel #%d gave up after %d consecutive failures (%s)", deviceNumber, failures - 1, reason)
            return
        }
        val backoff = (REOPEN_BACKOFF_MS * failures).coerceAtMost(MAX_BACKOFF_MS)
        Timber.w("RawAntChannel #%d reopen in %dms (failure #%d: %s)", deviceNumber, backoff, failures, reason)
        scope.launch {
            delay(backoff)
            if (!stopped) open()
        }
    }

    fun stop() {
        stopped = true
        scope.launch {
            val ch = channel
            channel = null
            runCatching { ch?.close() }
            runCatching { ch?.unassign() }
            runCatching { ch?.release() }
            runCatching { context.unbindService(conn) }
        }.invokeOnCompletion {
            // Cancel the scope only AFTER cleanup runs, so the channel teardown + unbind complete.
            scope.cancel()
        }
    }

    private companion object {
        // ~10 retries with backoff growing 1s,2s,…,5s,5s gives ~40s of recovery attempts — enough
        // to ride out an ANT Radio Service restart (SERVICE_INITIALIZING) without giving up.
        const val MAX_FAILURES = 10
        const val REOPEN_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 5000L

        /** Pages we know how to decode (parser pages + ANT+ common pages); others are UNKNOWN. */
        val KNOWN_PAGES = setOf(0x10, 0x13, 0x14, 0xE0, 0xE1, 0xE2, 0x50, 0x51)
    }
}

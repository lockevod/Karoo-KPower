package com.enderthor.kpower.ant

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.dsi.ant.AntService
import com.dsi.ant.channel.AntChannel
import com.dsi.ant.channel.AntChannelProvider
import com.dsi.ant.channel.ChannelNotAvailableException
import com.dsi.ant.channel.IAntChannelEventHandler
import com.dsi.ant.channel.PredefinedNetwork
import com.dsi.ant.message.ChannelId
import com.dsi.ant.message.ChannelType
import com.dsi.ant.message.EventCode
import com.dsi.ant.message.HighPrioritySearchTimeout
import com.dsi.ant.message.LowPrioritySearchTimeout
import com.dsi.ant.message.fromant.BroadcastDataMessage
import com.dsi.ant.message.fromant.ChannelEventMessage
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rest to wait before the next low-priority search window.
 *
 * [meterEverSeen] is the safety gate, and it is the important half of this function. The backoff exists
 * ONLY to stop the radio hunting for a meter that is not there (bike parked, estimate-only bike, rider
 * indoors). A meter that has ALREADY answered on this channel is a meter the rider owns and wants, and
 * it goes silent for entirely normal reasons — coasting a descent, a traffic light, a cafe stop, a
 * firmware quirk that trips the 30s silence watchdog. Backing off on those means the rider resumes
 * pedalling and waits out a rest of up to [cap] with a blank power field: at the cap the meter can only
 * be heard during a 25s window every 145s, so the odds of NOT hearing a wake-up go from ~17% to ~83%
 * and the mean wait from under a second to ~50s. That trade is never worth any amount of battery on a
 * power-meter app, so a meter that has been seen keeps the flat [base] rest forever.
 *
 * For a meter never seen on this channel the rest doubles from [base] and saturates at [cap].
 *
 * Pure so BOTH halves are testable: the seen-gate (which is the regression guard) and the saturation
 * arithmetic (an off-by-one there either lets the rest grow without bound, so a meter that wakes is
 * never re-acquired, or pins it at [base], removing the whole point of the backoff).
 */
internal fun searchRestMs(rests: Int, meterEverSeen: Boolean, base: Long, cap: Long): Long =
    if (meterEverSeen) base
    else if (rests >= 63) cap
    else (base shl rests).let { if (it <= 0L || it > cap) cap else it }

internal fun tryBeginChannelOpen(opening: AtomicBoolean, blocked: () -> Boolean): Boolean {
    if (blocked() || !opening.compareAndSet(false, true)) return false
    if (blocked()) {
        opening.set(false)
        return false
    }
    return true
}

/**
 * Opens a raw ANT+ SLAVE channel bound to ONE bike-power device number (type 11, RF 57,
 * period 8182) and delivers each broadcast payload to [onPayload]. Replaces antpluginlib for a
 * meter so we can also see Cycling Dynamics pages it does not expose. Proven on Karoo hardware
 * via AntChannelProbe (PredefinedNetwork.ANT_PLUS, 14 channels free).
 *
 * The channel is **BIDIRECTIONAL_SLAVE** (not receive-only) so it can TRANSMIT acknowledged
 * host->device commands — exactly how the Karoo's own sensorservice works. On connect it requests
 * the [identityPages] (manufacturer/product/battery) via a Request Data Page so brand+battery are
 * known within ~1s instead of waiting ~30s for the meter's slow background rotation; once each page
 * has been seen it stops asking (so there is no ongoing TX cost for the rest of the ride). Arbitrary
 * acknowledged payloads (e.g. a calibration request) can be queued with [sendAcknowledged].
 *
 * [onFirstPage] (optional) fires once, on the first broadcast after each (re)open — used by the raw
 * calibrator to send its request only once the channel is actually tracking the meter.
 */
class RawAntChannel(
    private val context: Context,
    private val deviceNumber: Int,
    private val onPayload: (ByteArray) -> Unit,
    private val identityPages: List<Int> = AntPlusRequests.IDENTITY_PAGES,
    private val onFirstPage: (() -> Unit)? = null,
) {
    // Host->device acknowledged TX queue (drained one item per received broadcast, on the channel's
    // own callback thread). Mirrors the Karoo's per-channel send queue (rxantplus q8/g.java f4906g).
    private val txQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    // Identity pages already observed (so we stop re-requesting them) + last-request time per page.
    private val seenPages = java.util.Collections.synchronizedSet(HashSet<Int>())
    private val lastIdentityReqMs = ConcurrentHashMap<Int, Long>()
    // How many times we've requested each unseen identity page. Bounded: a meter that never sends a page
    // (commonly the 0x52 battery page) must NOT be re-requested forever — that would be continuous TX to
    // the meter for the whole ride (battery drain + RX contention). After the cap we give up on that page.
    private val identityReqCount = ConcurrentHashMap<Int, Int>()
    @Volatile private var firstPageFired = false
    @Volatile private var lastTxAttemptMs = 0L

    /** Queue an acknowledged host->device payload (must be 8 bytes); sent on the next broadcast slot. */
    fun sendAcknowledged(payload: ByteArray) { txQueue.add(payload) }
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

    /**
     * CONSECUTIVE search windows that expired without finding the meter, since this channel was opened.
     * Drives the exponential rest between search windows (see [reopenAfterSearchTimeout]).
     *
     * Why: a search window keeps the ANT receiver on for its whole 25s duration. With a fixed 5s rest a
     * meter that is simply NOT THERE (bike parked, estimate-only bike, rider indoors) pins the radio at
     * ~83% duty for as long as the extension process lives, and it buys nothing — a meter that isn't
     * there won't answer any faster for being asked more often.
     */
    @Volatile private var searchRests = 0

    /**
     * True once ANY ANT page has been received on this channel, i.e. the meter is PRESENT.
     *
     * This is the guard that keeps the backoff off the rider's path. It is deliberately sticky: it is
     * NOT cleared when the meter goes silent, because "went quiet" is exactly the normal case (coasting,
     * traffic light, cafe stop) that must keep the old flat 5s rest. Only a channel that has never once
     * heard from its meter is allowed to back off. Scope is one connection: RawAntPowerMeter.connect()
     * builds a fresh RawAntChannel, so a genuinely new attempt starts unseen again.
     */
    @Volatile private var everSawPage = false

    /** Wall-clock of the last broadcast page. 0 = none yet. Drives the heartbeat watchdog: a meter that
     *  goes silent while its channel stays nominally "tracking" (firmware quirk — no RX_SEARCH_TIMEOUT,
     *  no death) would otherwise freeze the field at `---` for the rest of the ride (Ki2 uses the same
     *  message-gap watchdog). 0 until the first page so the search-timeout path owns the never-tracked case. */
    @Volatile private var lastPageMs = 0L

    /** Guards against two open() attempts running at once (death-retry racing a late rebind). CAS so
     *  the check-and-set is atomic — a plain volatile read-then-write let two callers both pass. */
    private val opening = AtomicBoolean(false)

    /** When a delayed open() is already due; Long.MAX_VALUE once the failure budget is spent. The
     *  heartbeat backstop only fires past this, so it can neither cut short the search-rest duty cycle
     *  nor keep retrying (and logging an ERROR every 15 s) a channel that deliberately gave up. */
    @Volatile private var nextOpenDueMs = 0L

    /** Guards [releaseChannel] so a handle is torn down (close/unassign/release) at most once, even
     *  when open()'s own configure-time bailouts race stop() on the SAME channel (different IO
     *  threads) — a concurrent double-release can corrupt the ANT provider's free-channel accounting
     *  (vendor SDK opaque), leaking a channel for the rest of the session. */
    private val teardownLock = Any()
    private val releasedChannels = HashSet<AntChannel>()

    /**
     * Idempotent teardown: close/unassign/release [ch] exactly once. No-op for null or an
     * already-released channel. Never call while holding [teardownLock] across a suspend point —
     * the lock only guards the released-set check, the actual IPC calls run outside it.
     */
    private fun releaseChannel(ch: AntChannel?) {
        if (ch == null) return
        synchronized(teardownLock) {
            if (!releasedChannels.add(ch)) return
            // Bound the set: a long ride reopens the channel many times. A released handle is never
            // reused, so forgetting old entries is safe — this just caps memory, not correctness.
            if (releasedChannels.size > 32) { releasedChannels.clear(); releasedChannels.add(ch) }
        }
        runCatching { ch.close() }
        runCatching { ch.unassign() }
        runCatching { ch.release() }
    }

    // ── Diagnostic ANT page logging (purely additive; only touched when FileLogTree.enabled) ─────
    /** Lifetime count of every page seen, keyed by page number, for the periodic SUMMARY line. */
    private val pageCounts = ConcurrentHashMap<Int, Long>()
    /** Per-page last-logged wall-clock time, to throttle the per-page diagnostic lines. */
    private val lastPageLogMs = ConcurrentHashMap<Int, Long>()
    /** Distinct payloads seen per UNKNOWN page number — so we capture every data variant a device
     *  emits on pages we don't parse yet (discovery), deduped + capped instead of time-throttled. */
    private val unknownPayloads = ConcurrentHashMap<Int, MutableSet<String>>()
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
        override fun onServiceDisconnected(name: ComponentName?) {
            // The ANT Radio Service process died: the binder AND any acquired channel are dead. Null
            // both so a stale channel can't be reused. Android keeps the binding and re-delivers
            // onServiceConnected when the service restarts (which reopens); if the bind itself was lost,
            // scheduleRebind re-attempts — without this, an OS-driven service restart killed real power
            // for the rest of the ride (Ki2 recovers the same way via attemptBindToAntService).
            antService = null
            channel = null
            if (!stopped) scheduleRebind("ant service disconnected")
        }
    }

    /** Channel-availability broadcasts: when the provider reports free channels and we currently have
     *  none (a prior acquire hit ChannelNotAvailable), retry open() instead of giving up. */
    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED) return
            val n = intent.getIntExtra(AntChannelProvider.NUM_CHANNELS_AVAILABLE, 0)
            if (n > 0 && !stopped && channel == null && !opening.get()) scope.launch { open() }
        }
    }
    @Volatile private var providerRegistered = false

    fun start() {
        startSummaryLoop()
        runCatching {
            // EXPORTED: the broadcast comes from the ANT Radio Service (a separate app). ContextCompat
            // picks the right registerReceiver overload across API levels (the flag is API 33+).
            ContextCompat.registerReceiver(
                context,
                providerReceiver,
                IntentFilter(AntChannelProvider.ACTION_CHANNEL_PROVIDER_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            providerRegistered = true
        }
        bindAnt()
    }

    /** Bind the ANT Radio Service; if the bind is refused (false / throws), retry after a short delay
     *  until it succeeds or stop() is called — mirrors Ki2's attemptBindToAntService. */
    private fun bindAnt() {
        if (stopped) return
        val ok = runCatching { AntService.bindService(context, conn) }.getOrDefault(false)
        if (!ok) scheduleRebind("bindService returned false")
    }

    private fun scheduleRebind(reason: String) {
        if (stopped) return
        scope.launch {
            delay(REBIND_DELAY_MS)
            if (stopped || antService != null) return@launch
            Timber.w("RawAntChannel #%d rebind (%s)", deviceNumber, reason)
            runCatching { context.unbindService(conn) }   // drop a half-up binding before re-binding
            bindAnt()
        }
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
                // Heartbeat watchdog (always on, not gated on logging): if the channel is open and was
                // delivering data but has gone SILENT for > HEARTBEAT_TIMEOUT_MS without a search-timeout
                // or death, recycle it — covers meters that stop broadcasting while the channel still
                // reads as tracking. Budget-exempt (reuses the search-timeout duty-cycle path). lastPageMs
                // is reset to 0 so we don't recycle again until real data returns.
                // Backstop for the "no channel and nothing scheduled" hole: a request dropped by the
                // open() CAS can be the LAST one pending (its winner having bailed out on a path that
                // schedules nothing), and the silence check below can't help because it requires a
                // channel. Cheap: only fires when there is genuinely nothing to lose.
                if (!stopped && channel == null && !opening.get() &&
                    System.currentTimeMillis() > nextOpenDueMs
                ) {
                    Timber.i("RawAntChannel #%d idle with no channel — reopening", deviceNumber)
                    open()
                }
                val lp = lastPageMs
                if (!stopped && channel != null && !opening.get() && lp > 0L &&
                    System.currentTimeMillis() - lp > HEARTBEAT_TIMEOUT_MS
                ) {
                    Timber.i("RawAntChannel #%d silent %dms — recycling", deviceNumber, System.currentTimeMillis() - lp)
                    lastPageMs = 0L
                    reopenAfterSearchTimeout("heartbeat")
                }
                if (com.enderthor.kpower.extension.FileLogTree.enabled && pageCounts.isNotEmpty()) {
                    val accumMoved = firstAccumPower >= 0 && lastAccumPower != firstAccumPower
                    Timber.tag("ANTLOG").d(
                        "dev=%d SUMMARY %s | 0x10 maxW=%d maxRpm=%d accumMoved=%b",
                        deviceNumber,
                        pageCounts.entries.sortedBy { it.key }
                            .joinToString(" ") { "0x%02X=%d%s".format(it.key, it.value, if (it.key in KNOWN_PAGES) "" else "?") },
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
        // Discovery: for a page we don't parse yet, log EVERY DISTINCT payload (deduped, unthrottled,
        // capped) so a newly-added device reveals all the data it emits on other pages — not just one
        // sample per second. A page that just repeats the same bytes logs once; genuinely new bytes
        // (a different data variant) always get through, up to the cap.
        if (page !in KNOWN_PAGES) {
            val hex = p.joinToString(" ") { "%02X".format(it) }
            val seen = unknownPayloads.computeIfAbsent(page) { java.util.concurrent.ConcurrentHashMap.newKeySet() }
            if (seen.size < MAX_UNKNOWN_VARIANTS && seen.add(hex)) {
                Timber.tag("ANTLOG").d(
                    "UNKNOWN dev=%d PAGE 0x%02X payload=%s (variant %d)", deviceNumber, page, hex, seen.size,
                )
                return
            }
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
        // Claim only when no acquisition is in flight and no live channel already exists. A delayed
        // retry can otherwise overwrite a channel opened sooner by the provider/rebind callback.
        if (!tryBeginChannelOpen(opening) { stopped || channel != null }) return
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
                    releaseChannel(ch)
                    return
                }
                channel = ch
                // Fresh channel: no page yet. Reset so the heartbeat watchdog can't recycle this new
                // channel using a stale lastPageMs left over from the previous (dead) one before it has
                // had a chance to (re-)acquire the meter.
                lastPageMs = 0L
                firstPageFired = false   // re-fire onFirstPage after a reconnect (e.g. re-send calibration)
                ch.setChannelEventHandler(object : IAntChannelEventHandler {
                    override fun onReceiveMessage(type: MessageFromAntType?, msg: AntMessageParcel?) {
                        if (msg == null) return
                        // Ignore late events from a channel we've already replaced: this handler belongs
                        // to `ch`, but a reopen may have moved `channel` to a newer instance. Acting on a
                        // stale event would tear down the new channel (and reset failures/lastPageMs from
                        // a dead one). Only the current channel's events count.
                        if (ch !== channel) return
                        when (type) {
                            MessageFromAntType.BROADCAST_DATA -> {
                                // Receiving a page proves the link is healthy — clear the failure budget
                                // so a death hours later still gets a full set of retries.
                                failures = 0; nextOpenDueMs = 0L; searchRests = 0; everSawPage = true
                                lastPageMs = System.currentTimeMillis()
                                runCatching {
                                    val payload = BroadcastDataMessage(msg).payload
                                    // Diagnostic ANT page logging — gated so it costs ~nothing when off.
                                    if (com.enderthor.kpower.extension.FileLogTree.enabled) logPage(payload)
                                    onPayload(payload)
                                    if (payload.isNotEmpty()) seenPages.add(payload[0].toInt() and 0xFF)
                                }
                                // First page after (re)open: the channel is now tracking the meter — let
                                // the owner (e.g. the calibrator) act once it can actually be reached.
                                if (!firstPageFired) { firstPageFired = true; runCatching { onFirstPage?.invoke() } }
                                // Ask for any still-unseen identity page (manufacturer/product/battery),
                                // like the Karoo, then send one queued acknowledged command per slot.
                                pumpTx(ch)
                            }
                            MessageFromAntType.CHANNEL_EVENT -> {
                                // The finite low-priority search window expired (meter asleep / out of
                                // range) or the channel closed: recycle + retry as a duty cycle so the
                                // radio isn't pinned searching forever. Budget-exempt — sleeping meters
                                // are normal and must keep retrying for the whole ride.
                                // ONLY RX_SEARCH_TIMEOUT: CHANNEL_CLOSED also fires on OUR OWN close()
                                // (reopenAfterSearchTimeout/reopenAfterDeath/stop), which would self-trigger
                                // a second reopen and double-schedule open().
                                val ev = runCatching { ChannelEventMessage(msg).eventCode }.getOrNull()
                                if (ev == EventCode.RX_SEARCH_TIMEOUT) reopenAfterSearchTimeout(ev.toString())
                            }
                            else -> {}
                        }
                    }
                    override fun onChannelDeath() { if (ch === channel) reopenAfterDeath() }
                })
                // BIDIRECTIONAL_SLAVE (not RECEIVE_ONLY) so we can TX the identity/calibration requests.
                // It still receives every broadcast identically; with an empty TX queue it never transmits,
                // so once identity is resolved there is no ongoing send cost. This is the Karoo's own model.
                // Re-checked after EACH delay (not just once at the end): stop() runs on a different IO
                // thread and can land mid-sequence — bailing out promptly shrinks the window where its
                // teardown could race one of the calls below on the same handle.
                ch.assign(ChannelType.BIDIRECTIONAL_SLAVE); delay(50)
                if (stopped) { channel = null; releaseChannel(ch); return }
                ch.setChannelId(ChannelId(deviceNumber, 11, 0)); delay(50)  // SPECIFIC device, type 11
                if (stopped) { channel = null; releaseChannel(ch); return }
                ch.setRfFrequency(57); delay(50)
                if (stopped) { channel = null; releaseChannel(ch); return }
                ch.setPeriod(8182); delay(50)
                if (stopped) { channel = null; releaseChannel(ch); return }
                // Bound the low-priority search: when it expires (meter asleep / out of range) the
                // channel reports RX_SEARCH_TIMEOUT and reopenAfterSearchTimeout duty-cycles it, instead
                // of the radio searching nonstop. Search priority is left at the default (lowest) so this
                // additive read-only channel yields to the Karoo's own paired sensors.
                runCatching { ch.setSearchTimeout(LowPrioritySearchTimeout.TWENTY_FIVE_SECONDS, HighPrioritySearchTimeout.DISABLED) }; delay(50)
                // Re-check right before open(): a stop() during the configure delays must win. All
                // bailouts null the field BEFORE releasing so no late IPC callback can pass the
                // `ch === channel` identity check against an already-torn-down handle.
                if (stopped) {
                    channel = null
                    releaseChannel(ch)
                    return
                }
                ch.open()
                Timber.d("RawAntChannel #%d open", deviceNumber)
                if (com.enderthor.kpower.extension.FileLogTree.enabled)
                    Timber.tag("ANTLOG").d("dev=%d channel open (type=11 rf=57 period=8182)", deviceNumber)
            }.onFailure { e ->
                // Drop any half-acquired channel first.
                val dead = channel
                channel = null
                releaseChannel(dead)
                if (e is kotlinx.coroutines.CancellationException) {
                    // BENIGN: stop()/release() cancelled the scope mid-open — e.g. a scan-list identify
                    // whose meter was ASLEEP (no broadcast within the identify window, so the cranks
                    // weren't turning), or simply leaving the scan screen. NOT a failure: don't log an
                    // error (it just scares the reader with a stacktrace) and don't reschedule — the
                    // channel is going away. runCatching swallowed the cancellation; rethrow so the
                    // coroutine actually finishes cancelled instead of completing normally.
                    Timber.d("RawAntChannel #%d open cancelled (teardown — meter asleep or screen left)", deviceNumber)
                    throw e
                } else if (e is ChannelNotAvailableException) {
                    // All ANT channels busy (Karoo's own sensors + others). Don't burn the failure
                    // budget — providerReceiver retries open() when a channel frees up. Plus a long
                    // budget-exempt backstop in case that broadcast is missed/coalesced (so the meter
                    // can't be permanently dead).
                    Timber.w("RawAntChannel #%d no channel available; awaiting provider", deviceNumber)
                    nextOpenDueMs = System.currentTimeMillis() + NO_CHANNEL_RETRY_MS
                    scope.launch { delay(NO_CHANNEL_RETRY_MS); if (!stopped && channel == null && !opening.get()) open() }
                } else {
                    Timber.e(e, "RawAntChannel #%d open failed", deviceNumber)
                    // Usually transient (service restarting); scheduleReopen() enforces budget/backoff.
                    scheduleReopen(e.message ?: "open failure")
                }
            }
        } finally {
            opening.set(false)
        }
    }

    /**
     * Drive host->device transmission, called from the channel's broadcast callback (so we TX in the
     * channel's own slot). First (re)queues a Request Data Page for any identity page not yet seen
     * — throttled per page — then sends ONE acknowledged payload from the queue, but at most once per
     * [TX_MIN_INTERVAL_MS]. An acknowledged transfer takes longer than one broadcast period to complete
     * (it retries until the master ACKs), so attempting a send every broadcast just floods the radio with
     * TRANSFER_IN_PROGRESS failures; ~1 send/s is plenty for identity/calibration. Best-effort: a failed
     * transfer is dropped (identity re-requests; the calibrator re-queues). Costs nothing once the queue
     * is empty and every identity page has been seen.
     */
    private fun pumpTx(ch: AntChannel) {
        if (ch !== channel) return
        val now = System.currentTimeMillis()
        for (page in identityPages) {
            if (seenPages.contains(page)) continue
            if ((identityReqCount[page] ?: 0) >= MAX_IDENTITY_REQUESTS) continue   // gave up (e.g. no 0x52)
            val last = lastIdentityReqMs[page] ?: 0L
            if (now - last < IDENTITY_REQUEST_INTERVAL_MS) continue
            lastIdentityReqMs[page] = now
            identityReqCount[page] = (identityReqCount[page] ?: 0) + 1
            if (txQueue.size < MAX_TX_QUEUE) txQueue.add(AntPlusRequests.requestDataPage(page))
        }
        // Throttle the actual acknowledged send: a transfer in flight would fail any new send with
        // TRANSFER_IN_PROGRESS, so don't attempt more than ~1/s.
        if (now - lastTxAttemptMs < TX_MIN_INTERVAL_MS) return
        val payload = txQueue.poll() ?: return
        lastTxAttemptMs = now
        runCatching { ch.startSendAcknowledgedData(payload) }
            .onFailure { Timber.v("RawAntChannel #%d ack TX failed (will re-request): %s", deviceNumber, it.message) }
    }

    /**
     * I1 recovery: on a radio-level channel death, forget the dead channel and schedule a fresh
     * reopen. Shares the [failures] budget/backoff with open()-failure so a SERVICE_INITIALIZING
     * (ANT service restart) is ridden out rather than giving up after one try.
     */
    /**
     * Search window expired (or the channel closed) without finding the meter. Recycle the channel and
     * retry after a rest — a duty cycle that lets the radio breathe vs. searching nonstop. NOT counted
     * against the failure budget: a sleeping meter is normal and must keep retrying all ride.
     *
     * The rest grows exponentially (5s, 10s, 20s, … capped at [MAX_SEARCH_REST_MS]) ONLY for a meter
     * that has never answered on this channel ([everSawPage]); a meter that has been seen keeps the flat
     * [SEARCH_REST_MS] forever. That gate matters because this function is NOT only reached on a genuine
     * "meter not found": the 30s silence watchdog in [startSummaryLoop] also routes through here for a
     * meter that WAS delivering pages, and a meter that sleeps while the rider coasts or waits at a light
     * looks identical to one that is absent. Without the gate the rider resumes pedalling into a rest of
     * up to [MAX_SEARCH_REST_MS] with a blank power field. See [searchRestMs].
     */
    private fun reopenAfterSearchTimeout(reason: String) {
        // Re-entrancy guard: if an open() is already in flight (or a reopen already nulled the channel),
        // don't stack another delayed open().
        if (stopped || (channel == null && opening.get())) return
        val dead = channel
        channel = null
        releaseChannel(dead)
        val rest = searchRestMs(searchRests, everSawPage, SEARCH_REST_MS, MAX_SEARCH_REST_MS)
        if (!everSawPage && rest < MAX_SEARCH_REST_MS) searchRests++
        Timber.i("RawAntChannel #%d search idle (%s) — retry in %dms", deviceNumber, reason, rest)
        nextOpenDueMs = System.currentTimeMillis() + rest
        scope.launch { delay(rest); if (!stopped) open() }
    }

    private fun reopenAfterDeath() {
        Timber.w("RawAntChannel #%d death", deviceNumber)
        if (stopped) return
        // Forget the dead channel so open() acquires a brand-new one instead of reusing it.
        val dead = channel
        channel = null
        releaseChannel(dead)
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
            // Park the heartbeat backstop too, or the budget bounds nothing: it would call open() every
            // 15 s forever, logging an ERROR and burning a binder round-trip each time. Cleared with the
            // budget when a page arrives, and by a fresh connect().
            nextOpenDueMs = Long.MAX_VALUE
            return
        }
        val backoff = (REOPEN_BACKOFF_MS * failures).coerceAtMost(MAX_BACKOFF_MS)
        Timber.w("RawAntChannel #%d reopen in %dms (failure #%d: %s)", deviceNumber, backoff, failures, reason)
        nextOpenDueMs = System.currentTimeMillis() + backoff
        scope.launch {
            delay(backoff)
            if (!stopped) open()
        }
    }

    fun stop() {
        stopped = true
        if (providerRegistered) { runCatching { context.unregisterReceiver(providerReceiver) }; providerRegistered = false }
        scope.launch {
            val ch = channel
            channel = null
            releaseChannel(ch)
            runCatching { context.unbindService(conn) }
        }.invokeOnCompletion {
            // Cancel the scope only AFTER cleanup runs, so the channel teardown + unbind complete.
            scope.cancel()
        }
    }

    // internal (not private) so the unit test can assert against the REAL tuning constants instead of
    // copies — a change to SEARCH_REST_MS / MAX_SEARCH_REST_MS must reach the test.
    internal companion object {
        // ~10 retries with backoff growing 1s,2s,…,5s,5s gives ~40s of recovery attempts — enough
        // to ride out an ANT Radio Service restart (SERVICE_INITIALIZING) without giving up.
        const val MAX_FAILURES = 10
        const val REOPEN_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 5000L
        const val SEARCH_REST_MS = 5000L   // base rest between low-priority search windows (duty cycle)
        // Ceiling for the exponential search rest (5s,10s,20s,40s,80s,120s), reached after ~305s of
        // continuous silence. At the cap a 25s search window costs ~17% receiver duty instead of ~83%,
        // and a meter that is genuinely absent is still re-checked twice a minute. Only ever applied to
        // a channel that has never received a page — see [searchRestMs].
        const val MAX_SEARCH_REST_MS = 120_000L
        const val REBIND_DELAY_MS = 2000L  // wait before re-binding the ANT Radio Service
        const val NO_CHANNEL_RETRY_MS = 15000L  // backstop retry if the provider broadcast is missed
        const val HEARTBEAT_TIMEOUT_MS = 30000L // recycle a tracking channel that has gone silent this long
        const val IDENTITY_REQUEST_INTERVAL_MS = 3000L // re-ask for an unseen identity page at most this often
        const val MAX_IDENTITY_REQUESTS = 6     // give up requesting a page after this (≈18s) — e.g. a meter with no 0x52
        const val MAX_TX_QUEUE = 6              // bound the acknowledged-TX queue (drop excess, re-request later)
        const val TX_MIN_INTERVAL_MS = 1000L    // min gap between acknowledged-TX attempts (avoid TRANSFER_IN_PROGRESS flood)

        /** Pages we know how to decode (parser pages + ANT+ common pages); others are UNKNOWN. */
        const val MAX_UNKNOWN_VARIANTS = 40   // cap distinct payloads logged per unknown page
        val KNOWN_PAGES = setOf(0x10, 0x11, 0x12, 0x13, 0x14, 0xE0, 0xE1, 0xE2, 0x50, 0x51, 0x52)
    }
}

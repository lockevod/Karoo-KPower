package com.enderthor.kpower.ant

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.dsi.ant.AntService
import com.dsi.ant.channel.AntChannel
import com.dsi.ant.channel.AntChannelProvider
import com.dsi.ant.channel.Capabilities
import com.dsi.ant.channel.ChannelNotAvailableException
import com.dsi.ant.channel.IAntChannelEventHandler
import com.dsi.ant.channel.PredefinedNetwork
import com.dsi.ant.message.ChannelId
import com.dsi.ant.message.ChannelType
import com.dsi.ant.message.ExtendedAssignment
import com.dsi.ant.message.ExtendedData
import com.dsi.ant.message.LibConfig
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Power-meter discovery EXACTLY the way the Karoo's sensor service does it (verified by decompiling
 * io.hammerhead.sensorservice): a SINGLE raw ANT Radio Service channel in WILDCARD BACKGROUND-SCAN mode,
 * NOT antpluginlib's MultiDeviceSearch/PCC. One channel receives the broadcasts of every transmitting
 * device; each device is demultiplexed from the broadcast's EXTENDED channel-id (device number + type),
 * and brand/model + battery are parsed from the common pages (0x50/0x52) in the broadcast bytes.
 *
 * Why this and not MultiDeviceSearch: the chip has few channels; repeatedly opening/closing a
 * MultiDeviceSearch (+ a per-device PCC) leaks channels until ALL_CHANNELS_IN_USE. ONE persistent
 * background-scan channel, released exactly once on [stop], never exhausts the pool — the Karoo's model.
 *
 * Config mirrors the Karoo and is done SYNCHRONOUSLY with NO suspension points (so a concurrent [stop]
 * can never interrupt mid-configure and orphan a channel; the failure path always releases the LOCAL
 * channel reference). The raw channel's callbacks arrive on the ANT lib's own HandlerThread, so this may
 * run off the Main thread (unlike PCC, which needs a Looper on the calling thread).
 *
 * [onBroadcast] is called for every BIKE_POWER (device type 11) broadcast with (deviceNumber, payload).
 */
class RawAntScanChannel(
    private val context: Context,
    private val onBroadcast: (deviceNumber: Int, payload: ByteArray) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var antService: AntService? = null
    @Volatile private var channel: AntChannel? = null
    @Volatile private var stopped = false
    @Volatile private var openAttempts = 0
    private val opening = AtomicBoolean(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (stopped || service == null) return
            antService = AntService(service)
            // The service (re)started, so ANY handle still in the field belongs to the dead process —
            // it can only have been published by an open() that raced the disconnect. Drop it here, or
            // open()'s `channel == null` gate is satisfied by a corpse and the scan never recovers.
            val dead = synchronized(this@RawAntScanChannel) { channel.also { channel = null } }
            dead?.let { releaseQuietly(it) }
            scope.launch { open() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            antService = null
            // No reschedule here: retrying open() would only find `antService == null` and spin on the
            // null-provider path every 2 s for as long as the scan screen is open. Android redelivers
            // onServiceConnected when the service comes back, and that is what reopens.
            synchronized(this@RawAntScanChannel) { channel = null }
        }
    }

    fun start() {
        stopped = false
        openAttempts = 0
        val ok = runCatching { AntService.bindService(context, conn) }.getOrDefault(false)
        if (!ok) {
            Timber.w("RawAntScanChannel: bindService returned false; retrying")
            scope.launch { delay(2_000); if (!stopped) start() }
        }
    }

    /** Synchronous (non-suspend) so a concurrent stop()/scope.cancel() can't interrupt mid-config and
     *  orphan a channel. Always releases the LOCAL channel on failure (never the shared field). */
    private fun open() {
        // ponytail: unlike RawAntChannel this has no silence watchdog. A published-but-dead handle is
        // now cleared by onServiceConnected, which covers the reachable case; add a lastBroadcastMs
        // recycle loop if a channel that is open but silent is ever observed on device.
        if (!tryBeginChannelOpen(opening) { stopped || channel != null }) return
        try {
            openClaimed()
        } finally {
            opening.set(false)
        }
    }

    private fun openClaimed() {
        // channelProvider is a binder call (throws RemoteException if the ANT service died) — guard it,
        // else open() throws on the IO coroutine with no retry and the scan silently never recovers.
        val provider = runCatching { antService?.channelProvider }.getOrNull()
        if (provider == null) { scheduleRetry(2_000, countsToward = false); return }   // service not ready yet (transient)

        val ch: AntChannel = try {
            provider.acquireChannel(
                context, PredefinedNetwork.ANT_PLUS,
                // Request ONLY background scanning (exactly like the Karoo). Do NOT also require
                // supportSearchPriority: it's a separate capability and requiring it would make
                // acquireChannel throw NO_CHANNELS_MATCH_CRITERIA on any adapter that lacks it, killing
                // the scan — yet setSearchPriority(11) below is already best-effort (runCatching).
                Capabilities().apply { supportBackgroundScanning(true) },
            )
        } catch (e: ChannelNotAvailableException) {
            // No free channel (recorded meter / Karoo's own sensors hold them, or our own previous scan
            // channel is still releasing). TRANSIENT — retry indefinitely with backoff, budget-EXEMPT, so
            // it recovers when a channel frees (a fixed give-up would leave the scan permanently dead).
            Timber.w(e, "RawAntScanChannel: channel not available; will retry")
            scheduleRetry(4_000, countsToward = false); return
        } catch (e: Throwable) {
            Timber.e(e, "RawAntScanChannel: acquireChannel failed")
            scheduleRetry(2_000, countsToward = true); return
        }

        try {
            ch.setChannelEventHandler(handlerFor(ch))
            ch.assign(ChannelType.SLAVE_RECEIVE_ONLY, ExtendedAssignment().apply { enableBackgroundScanning() })
            ch.setRfFrequency(57)
            runCatching { ch.setSearchPriority(11) }
            // Adapter-wide: make broadcasts carry the extended channel-id (to demux device number) + RSSI;
            // no RX timestamp. Same LibConfig the Karoo sets. getPayload() still returns only the 8 data
            // bytes, so this doesn't affect the recorded meter's parsing.
            runCatching { ch.setAdapterWideLibConfig(LibConfig(true, true, false)) }
            ch.setChannelId(ChannelId(0, 0, 0))                 // wildcard = all devices
            if (stopped) { releaseQuietly(ch); return }
            ch.open()
            val published = synchronized(this) {
                if (stopped || channel != null) false else { channel = ch; true }
            }
            // Only reachable when stop() raced us (the `opening` CAS means no second openClaimed can be
            // in flight, so `channel != null` cannot happen here) — nothing to reschedule.
            if (!published) { releaseQuietly(ch); return }
            openAttempts = 0
            Timber.d("RawAntScanChannel: wildcard background scan open (rf=57 priority=11)")
        } catch (e: Throwable) {
            releaseQuietly(ch)                                  // LOCAL ch — never the field
            synchronized(this) { if (channel === ch) channel = null }
            Timber.e(e, "RawAntScanChannel: configure/open failed")
            scheduleRetry(2_000, countsToward = true)
        }
    }

    /** [countsToward]=false for transient channel-availability retries (retry forever with backoff);
     *  true for genuine config/open failures (bounded, so a hard error doesn't spin forever). */
    private fun scheduleRetry(delayMs: Long, countsToward: Boolean) {
        if (stopped) return
        if (countsToward && ++openAttempts > MAX_OPEN_ATTEMPTS) {
            Timber.e("RawAntScanChannel: giving up after %d failed opens", openAttempts)
            return
        }
        scope.launch { delay(delayMs); if (!stopped && channel == null) open() }
    }

    private fun handlerFor(ch: AntChannel) = object : IAntChannelEventHandler {
        override fun onReceiveMessage(type: MessageFromAntType?, msg: AntMessageParcel?) {
            if (msg == null || ch !== channel) return            // ignore events from a replaced channel
            if (type != MessageFromAntType.BROADCAST_DATA) return
            runCatching {
                val bdm = BroadcastDataMessage(msg)
                val ed = ExtendedData(bdm)
                if (!ed.hasChannelId()) return@runCatching         // need the device id to demux
                val cid = ed.channelId
                if ((cid.deviceType and 0x7F) != BIKE_POWER_TYPE) return@runCatching
                onBroadcast(cid.deviceNumber, bdm.payload)
            }
        }
        override fun onChannelDeath() {
            val current = synchronized(this@RawAntScanChannel) {
                if (stopped || channel !== ch) false else { channel = null; true }
            }
            if (!current) return
            releaseQuietly(ch)
            scheduleRetry(2_000, countsToward = true)
        }
    }

    private fun releaseQuietly(ch: AntChannel) {
        runCatching { ch.close() }
        runCatching { ch.unassign() }
        runCatching { ch.clearChannelEventHandler() }
        runCatching { ch.release() }
    }

    fun stop() {
        val ch = synchronized(this) {
            stopped = true
            channel.also { channel = null }
        }
        antService = null
        // Release + unbind are binder IPC that can block — do them OFF the caller (stop() is invoked from
        // Compose click handlers on the main thread), then cancel the scope once teardown is done.
        scope.launch {
            if (ch != null) releaseQuietly(ch)
            runCatching { context.unbindService(conn) }
        }.invokeOnCompletion { scope.cancel() }
    }

    companion object {
        private const val BIKE_POWER_TYPE = 11
        private const val MAX_OPEN_ATTEMPTS = 8
    }
}

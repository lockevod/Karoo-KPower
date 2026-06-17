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

    /** Radio-level channel-death count; we stop reopening after MAX_DEATHS to avoid tight loops. */
    @Volatile private var deaths = 0

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            antService = AntService(binder)
            // Guard the bind/open race: a stop() that beat onServiceConnected must not open.
            if (!stopped) scope.launch { open() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { antService = null }
    }

    fun start() { runCatching { AntService.bindService(context, conn) } }

    /**
     * Acquires a FRESH channel each call (so it is safe to call again on a reopen after death),
     * then assigns/configures/opens it. If stop() raced this call, the freshly-acquired channel is
     * released immediately and we return without opening, so no orphan channel is left.
     */
    private suspend fun open() {
        if (stopped) return
        runCatching {
            val provider: AntChannelProvider = antService?.channelProvider ?: return
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
                        runCatching { onPayload(BroadcastDataMessage(msg).payload) }
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
        }.onFailure { Timber.e(it, "RawAntChannel #%d open failed", deviceNumber) }
    }

    /**
     * I1 recovery: on a radio-level channel death, forget the dead channel and reopen a fresh one
     * after a 1s backoff. Capped at MAX_DEATHS reopen attempts to avoid a tight death/reopen loop;
     * past the cap we just log and stay dead until the next connect().
     */
    private fun reopenAfterDeath() {
        Timber.w("RawAntChannel #%d death (#%d)", deviceNumber, deaths + 1)
        if (stopped) return
        if (++deaths > MAX_DEATHS) {
            Timber.e("RawAntChannel #%d exceeded %d deaths; not reopening", deviceNumber, MAX_DEATHS)
            return
        }
        // Forget the dead channel so open() acquires a brand-new one instead of reusing it.
        val dead = channel
        channel = null
        runCatching { dead?.release() }
        scope.launch {
            delay(REOPEN_BACKOFF_MS)
            if (!stopped) open()
        }
    }

    fun stop() {
        stopped = true
        scope.launch {
            runCatching { channel?.close() }
            runCatching { channel?.unassign() }
            runCatching { context.unbindService(conn) }
        }.invokeOnCompletion {
            // Cancel the scope only AFTER cleanup runs, so the channel teardown + unbind complete.
            scope.cancel()
        }
    }

    private companion object {
        const val MAX_DEATHS = 5
        const val REOPEN_BACKOFF_MS = 1000L
    }
}

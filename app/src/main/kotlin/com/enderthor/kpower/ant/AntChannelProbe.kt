package com.enderthor.kpower.ant

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.dsi.ant.AntService
import com.dsi.ant.channel.AntChannel
import com.dsi.ant.channel.AntChannelProvider
import com.dsi.ant.channel.IAntChannelEventHandler
import com.dsi.ant.channel.NetworkKey
import com.dsi.ant.message.ChannelId
import com.dsi.ant.message.ChannelType
import com.dsi.ant.message.fromant.BroadcastDataMessage
import com.dsi.ant.message.fromant.MessageFromAntType
import com.dsi.ant.message.ipc.AntMessageParcel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * THROWAWAY feasibility spike: can a sideloaded app open a raw ANT channel on the Karoo and
 * receive ANT+ bike-power pages (incl. cycling-dynamics 0xE0/0xE1/0xE2)? Filter logcat: tag ANTPROBE.
 * Remove after the experiment.
 */
class AntChannelProbe(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var antService: AntService? = null
    @Volatile private var channel: AntChannel? = null

    // ANT+ managed network key.
    private val antPlusKey = byteArrayOf(
        0xB9.toByte(), 0xA5.toByte(), 0x21, 0xFB.toByte(),
        0xBD.toByte(), 0x72, 0xC3.toByte(), 0x45,
    )

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Timber.tag(TAG).w("ANT service connected")
            antService = AntService(binder)
            scope.launch { setup() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.tag(TAG).w("ANT service disconnected")
            antService = null
        }
    }

    fun start() {
        Timber.tag(TAG).w("ANT radio service versionCode=%d", AntService.getVersionCode(context))
        val bound = AntService.bindService(context, conn)
        Timber.tag(TAG).w("bindService returned %b", bound)
    }

    private suspend fun setup() {
        try {
            val provider: AntChannelProvider = antService?.channelProvider ?: run {
                Timber.tag(TAG).e("no channel provider"); return
            }
            val avail = provider.numChannelsAvailable
            val legacy = provider.isLegacyInterfaceInUse
            Timber.tag(TAG).w("numChannelsAvailable=%d legacyInUse=%b", avail, legacy)
            if (avail <= 0 && !legacy) {
                Timber.tag(TAG).e("WARNING: numChannelsAvailable=0 — but still probing PUBLIC + PRIVATE acquire to confirm")
            }

            // STEP 1: prove RAW channel acquisition works at all on the PUBLIC network.
            // If this succeeds, channels ARE available to third-party apps and the only block
            // is the private/ANT+ network key (rejected by the Karoo's ANT Radio Service).
            try {
                val pub = provider.acquireChannel(context, com.dsi.ant.channel.PredefinedNetwork.PUBLIC)
                Timber.tag(TAG).w("PUBLIC acquireChannel OK -> raw channel acquisition WORKS (channels available)")
                runCatching { pub.release() }
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "PUBLIC acquireChannel FAILED: %s", e.javaClass.simpleName)
            }

            // STEP 2: attempt the private (ANT+) network acquire. Expected to fail on the Karoo
            // with IllegalArgumentException (invalid/null network key) if third-party ANT+ is blocked.
            try {
                val ch = provider.acquireChannelOnPrivateNetwork(context, NetworkKey(antPlusKey))
                channel = ch
                Timber.tag(TAG).w("PRIVATE acquireChannelOnPrivateNetwork OK -> acquired ANT+ channel")
                ch.setChannelEventHandler(object : IAntChannelEventHandler {
                    override fun onReceiveMessage(type: MessageFromAntType?, msg: AntMessageParcel?) {
                        if (type == MessageFromAntType.BROADCAST_DATA && msg != null) {
                            val payload = BroadcastDataMessage(msg).payload
                            val page = payload[0].toInt() and 0xFF
                            Timber.tag(TAG).w("PAGE 0x%02X payload=%s", page, payload.joinToString(" ") { String.format("%02X", it) })
                        }
                    }
                    override fun onChannelDeath() { Timber.tag(TAG).e("ANT channel death") }
                })
                ch.assign(ChannelType.SLAVE_RECEIVE_ONLY); delay(100)
                ch.setChannelId(ChannelId(0, 11, 0)); delay(100)   // wildcard device, type 11 = bike power, wildcard tx
                ch.setRfFrequency(57); delay(100)                  // 2457 MHz (ANT+)
                ch.setPeriod(8182); delay(100)                     // 4.005 Hz (ANT+ power)
                ch.open()
                Timber.tag(TAG).w("ANT channel OPEN — listening; watch for PAGE 0xE0/0xE1/0xE2 (cycling dynamics)")
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "PRIVATE acquireChannelOnPrivateNetwork FAILED: %s", e.javaClass.simpleName)
            }

            Timber.tag(TAG).w("SUMMARY: if PUBLIC ok but PRIVATE failed with invalid-key -> Karoo blocks third-party ANT+ raw channels (cycling dynamics NOT feasible via public API)")
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "ANT probe failed: %s", e.javaClass.simpleName)
        }
    }

    fun stop() {
        scope.launch {
            runCatching { channel?.close() }
            runCatching { channel?.unassign() }
            runCatching { context.unbindService(conn) }
        }
    }

    companion object { private const val TAG = "ANTPROBE" }
}

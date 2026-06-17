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

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            antService = AntService(binder); scope.launch { open() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { antService = null }
    }

    fun start() { runCatching { AntService.bindService(context, conn) } }

    private suspend fun open() {
        runCatching {
            val provider: AntChannelProvider = antService?.channelProvider ?: return
            val ch = provider.acquireChannel(context, PredefinedNetwork.ANT_PLUS)
            channel = ch
            ch.setChannelEventHandler(object : IAntChannelEventHandler {
                override fun onReceiveMessage(type: MessageFromAntType?, msg: AntMessageParcel?) {
                    if (type == MessageFromAntType.BROADCAST_DATA && msg != null) {
                        runCatching { onPayload(BroadcastDataMessage(msg).payload) }
                    }
                }
                override fun onChannelDeath() { Timber.w("RawAntChannel #%d death", deviceNumber) }
            })
            ch.assign(ChannelType.SLAVE_RECEIVE_ONLY); delay(50)
            ch.setChannelId(ChannelId(deviceNumber, 11, 0)); delay(50)  // SPECIFIC device, type 11
            ch.setRfFrequency(57); delay(50)
            ch.setPeriod(8182); delay(50)
            ch.open()
            Timber.d("RawAntChannel #%d open", deviceNumber)
        }.onFailure { Timber.e(it, "RawAntChannel #%d open failed", deviceNumber) }
    }

    fun stop() {
        scope.launch {
            runCatching { channel?.close() }
            runCatching { channel?.unassign() }
            runCatching { context.unbindService(conn) }
        }
    }
}

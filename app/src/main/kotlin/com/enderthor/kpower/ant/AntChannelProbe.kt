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
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * THROWAWAY feasibility spike: can a sideloaded app open a raw ANT channel on the Karoo and
 * receive ANT+ bike-power pages (incl. cycling-dynamics 0xE0/0xE1/0xE2)? Filter logcat: tag ANTPROBE.
 * Remove after the experiment.
 */
class AntChannelProbe(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var antService: AntService? = null
    @Volatile private var channel: AntChannel? = null
    @Volatile private var idLogged = false

    // File logging (patrón KGhost simplificado): vuelca capturas a un fichero para poder
    // estudiar una salida a la calle SIN adb/logcat. Writer abierto toda la sesión.
    @Volatile private var fileWriter: BufferedWriter? = null
    private val pageCounts = ConcurrentHashMap<Int, Long>()
    private val lastPageLogMs = ConcurrentHashMap<Int, Long>()

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
            openFileLog()
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
                val pub = provider.acquireChannel(context, PredefinedNetwork.PUBLIC)
                Timber.tag(TAG).w("PUBLIC acquireChannel OK -> raw channel acquisition WORKS (channels available)")
                runCatching { pub.release() }
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "PUBLIC acquireChannel FAILED: %s", e.javaClass.simpleName)
            }

            // STEP 2: acquire on the ANT+ managed network (Ki2's working approach). The radio
            // service supplies the ANT+ network key — no manual key. This is the real path that
            // opens + listens for bike-power pages.
            try {
                val ch = provider.acquireChannel(context, PredefinedNetwork.ANT_PLUS)
                channel = ch
                Timber.tag(TAG).w("acquired ANT+ channel OK (PredefinedNetwork.ANT_PLUS)")
                ch.setChannelEventHandler(object : IAntChannelEventHandler {
                    override fun onReceiveMessage(type: MessageFromAntType?, msg: AntMessageParcel?) {
                        if (type == MessageFromAntType.BROADCAST_DATA && msg != null) {
                            val payload = BroadcastDataMessage(msg).payload
                            val page = payload[0].toInt() and 0xFF
                            // En cuanto llega el primer broadcast el canal comodín YA está
                            // enganchado a un sensor concreto: pedimos su device number para
                            // saber EXACTAMENTE a qué potenciómetro estamos oyendo.
                            if (!idLogged) {
                                idLogged = true
                                scope.launch { logResolvedId() }
                            }
                            // Contador por página: la línea de RESUMEN periódica lo vuelca y es
                            // lo que prueba si las dinámicas llegan (p.ej. 0xE0=12000 0xE2=12000).
                            pageCounts.merge(page, 1L) { a, b -> a + b }
                            // Páginas de Cycling Dynamics (Garmin): 0xE0 ángulo fuerza derecha,
                            // 0xE1 izquierda, 0xE2 posición pedal/PCO, 0x13 TE/PS. Las resaltamos.
                            val isDynamics = page == 0xE0 || page == 0xE1 || page == 0xE2 || page == 0x13
                            // Throttle: 0x10 está a 4 Hz toda la ruta (14k líneas/h). Lo limitamos a
                            // 1 línea/5 s; las de dinámicas a 1/s para no perder muestras útiles.
                            val now = System.currentTimeMillis()
                            val throttleMs = if (isDynamics) 1000L else 5000L
                            if (now - (lastPageLogMs[page] ?: 0L) >= throttleMs) {
                                lastPageLogMs[page] = now
                                val prefix = if (isDynamics) "*** DYNAMICS " else ""
                                val hex = payload.joinToString(" ") { String.format("%02X", it) }
                                Timber.tag(TAG).w("%sPAGE 0x%02X payload=%s", prefix, page, hex)
                                fileLog("${prefix}PAGE 0x%02X payload=%s".format(page, hex))
                            }
                        }
                    }
                    override fun onChannelDeath() { Timber.tag(TAG).e("ANT channel death") }
                })
                ch.assign(ChannelType.SLAVE_RECEIVE_ONLY); delay(100)
                ch.setChannelId(ChannelId(0, 11, 0)); delay(100)   // wildcard device, type 11 = bike power, wildcard tx
                ch.setRfFrequency(57); delay(100)                  // 2457 MHz (ANT+)
                ch.setPeriod(8182); delay(100)                     // 4.005 Hz (ANT+ power)
                ch.open()
                Timber.tag(TAG).w("ANT+ channel OPEN — watch for PAGE 0xE0/0xE1/0xE2 (cycling dynamics)")
            } catch (e: Throwable) {
                Timber.tag(TAG).e(e, "ANT_PLUS acquireChannel FAILED: %s", e.javaClass.simpleName)
            }

            Timber.tag(TAG).w("SUMMARY: if PUBLIC ok and ANT_PLUS opens with PAGE logs -> Karoo allows third-party ANT+ raw channels (cycling dynamics feasible via PredefinedNetwork.ANT_PLUS)")
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "ANT probe failed: %s", e.javaClass.simpleName)
        }
    }

    private fun logResolvedId() {
        try {
            val id = channel?.requestChannelId()?.channelId ?: return
            val line = "RESOLVED deviceNumber=%d deviceType=%d transmissionType=%d (este es el sensor enganchado)".format(
                id.deviceNumber, id.deviceType, id.transmissionType
            )
            Timber.tag(TAG).w(line)
            fileLog(line)
        } catch (e: Throwable) {
            Timber.tag(TAG).e(e, "requestChannelId failed: %s", e.javaClass.simpleName)
        }
    }

    /** Abre el fichero de log y lanza el bucle de resumen periódico (cada 15 s). */
    private fun openFileLog() {
        runCatching {
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "antprobe")
            dir.mkdirs()
            val f = File(dir, "antprobe.log")
            fileWriter = FileWriter(f, /* append= */ true).buffered()
            Timber.tag(TAG).w("file log -> %s", f.absolutePath)
            fileLog("===== ANTPROBE START ${System.currentTimeMillis()} -> ${f.absolutePath} =====")
        }.onFailure { Timber.tag(TAG).e(it, "openFileLog failed") }

        scope.launch {
            while (true) {
                delay(15_000)
                val summary = pageCounts.entries.sortedBy { it.key }
                    .joinToString(" ") { "0x%02X=%d".format(it.key, it.value) }
                Timber.tag(TAG).w("SUMMARY pages: %s", summary)
                fileLog("SUMMARY pages: $summary")
            }
        }
    }

    private fun fileLog(line: String) {
        val w = fileWriter ?: return
        runCatching { synchronized(w) { w.write(line); w.newLine(); w.flush() } }
    }

    fun stop() {
        scope.launch {
            runCatching { channel?.close() }
            runCatching { channel?.unassign() }
            runCatching { context.unbindService(conn) }
            runCatching { fileLog("===== ANTPROBE STOP ${System.currentTimeMillis()} =====") }
            runCatching { synchronized(fileWriter ?: return@runCatching) { fileWriter?.close() } }
        }
    }

    companion object { private const val TAG = "ANTPROBE" }
}

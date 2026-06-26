package com.enderthor.kpower.extension

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A Timber tree that writes diagnostic logs to a FILE so a ride (especially ANT meter behaviour)
 * can be studied afterwards without adb/logcat. Works in RELEASE builds; OFF by default; toggled
 * from the rider's config flow.
 *
 * Threading (adapted from KGhost's FileLogTree, simplified — no Telegram/sessionId/upload):
 *  - An in-memory ring buffer drained by a single background IO coroutine, so there is NO file I/O
 *    on the calling (Timber) thread.
 *  - A [BufferedWriter] is kept open across flushes; [writer]/[writerFile] are touched ONLY from the
 *    flush loop, so they need no synchronisation. The buffer uses a brief [synchronized] block.
 *  - [logFile] is read AND written under the [buffer] lock so that a [newRide] file-swap and the
 *    banner it enqueues stay consistent with the drain (otherwise a flush in flight could write the
 *    new ride's banner into the previous ride's file).
 *
 * Data availability:
 *  - [FLUSH_INTERVAL_MS] = 1 s while [enabled] — data is on disk within a second, readable mid-ride.
 *  - While disabled the loop idle-polls every [IDLE_POLL_MS].
 *  - [newRide] triggers an immediate flush via a [Channel] so the ride-start banner hits disk fast.
 *  - [flushAndClose] (called when the toggle goes OFF) drains the tail and CLOSES the writer, so the
 *    last seconds aren't lost and the file descriptor isn't leaked until process death.
 *
 * Storage bounds (only relevant while logging is ON):
 *  - A single file is rotated once it exceeds [MAX_FILE_BYTES] (so one long ride can't grow one file
 *    without limit).
 *  - Old `.log` files are purged to stay within BOTH [MAX_LOG_FILES] and [MAX_TOTAL_BYTES]; the
 *    currently-active file is never deleted.
 *
 * Storage location: app-scoped external dir `…/Android/data/com.enderthor.kpower/files/logs` (NO
 * runtime permission needed; readable via Android Studio Device Explorer / a file manager).
 *
 * Cost when [enabled] is false: a single volatile read per log call.
 */
object FileLogTree : Timber.Tree() {

    /** Toggled from the rider's config flow. Off by default. */
    @Volatile
    var enabled: Boolean = false

    /** Short id of the current ride's log, to group uploaded chunks of the same ride in Telegram. */
    @Volatile
    var sessionId: String = "000000"
        private set

    /** The current log file (or null if not started), for the diagnostic-log uploader. */
    fun currentLogFile(): File? = logFile

    /** Wake the flush loop now so buffered lines hit disk before the uploader reads the file. */
    fun requestFlush() { flushSignal.trySend(null) }

    /**
     * Force a flush and SUSPEND until it has actually completed (the whole buffer is drained to disk),
     * so an off-ride uploader reads a complete file — not whatever happened to be flushed. Robust
     * regardless of the periodic flush cadence: the single flush this triggers drains ALL buffered lines.
     */
    suspend fun flushNow() {
        if (!started) return
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        flushSignal.trySend(done)
        kotlinx.coroutines.withTimeoutOrNull(2_000) { done.await() }
    }

    private const val MAX_BUFFER = 4000
    private const val FLUSH_INTERVAL_MS = 1_000L   // 1 s: data on disk fast, visible mid-ride
    private const val IDLE_POLL_MS = 60_000L       // 60 s: slow poll while logging is OFF
    private const val MAX_LOG_FILES = 6
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024    // 5 MB: rotate a single file past this
    private const val MAX_TOTAL_BYTES = 40L * 1024 * 1024  // 40 MB: total .log budget across files

    private val buffer = ArrayDeque<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    // Millis in the ride stamp so two rides started in the same second can't collide on one filename.
    private val rideFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss-SSS").withZone(ZoneId.systemDefault())

    // Signals the flush loop to flush now. Payload is an optional CompletableDeferred the loop completes
    // AFTER the flush, so flushNow() can await actual completion. UNLIMITED so a queued deferred is never
    // dropped (CONFLATED would discard it and flushNow would always time out).
    private val flushSignal = Channel<kotlinx.coroutines.CompletableDeferred<Unit>?>(Channel.UNLIMITED)

    @Volatile private var logDir: File? = null
    // Current file = "<baseName>.log" (part 0) or "<baseName>-p<part>.log" after a size rotation.
    private var baseName: String = "kpower"
    private var part: Int = 0

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var started = false

    /** Requested by [flushAndClose]; consumed by the flush loop AFTER a final drain. */
    @Volatile
    private var closeRequested = false

    // ── Persistent writer — accessed ONLY from the flush loop (no lock needed) ──────────────────
    private var writer: BufferedWriter? = null
    private var writerFile: File? = null   // which file the writer is currently opened for

    private fun currentFile(): File? {
        val dir = logDir ?: return null
        return File(dir, if (part == 0) "$baseName.log" else "$baseName-p$part.log")
    }

    /**
     * Resolve the log directory and start the background flush loop. Call ONCE (from the
     * Application onCreate).
     */
    fun start(context: Context) {
        if (started) return
        started = true
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs")
        runCatching { dir.mkdirs() }
        logDir = dir
        baseName = "kpower"
        part = 0
        logFile = currentFile()
        scope.launch {
            while (true) {
                // Wait for a flush signal OR the periodic interval, whichever comes first. Using a
                // SUSPENDING receive (not tryReceive + delay) means requestFlush()/newRide()/flushAndClose()
                // wake the loop within milliseconds — the off-ride pairing-log uploader relies on this to
                // read a COMPLETE tail (the old tryReceive-then-delay could sleep up to a full interval
                // after a signal, so the last ~1s of lines, incl. "SCAN STOP", weren't on disk yet).
                val pending = runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(if (enabled) FLUSH_INTERVAL_MS else IDLE_POLL_MS) {
                        flushSignal.receive()
                    }
                }.getOrNull()
                flush()
                if (closeRequested) { closeWriter(); closeRequested = false }
                // Signal flushNow() callers that their flush is done (buffer drained to disk).
                pending?.complete(Unit)
            }
        }
    }

    /**
     * Called once per genuinely-new ride start. Switches the log file to a fresh per-ride file,
     * writes the ride-start banner, signals an immediate flush so the banner is on disk within
     * milliseconds, and schedules a purge of old files.
     *
     * No-op when [enabled] is false.
     */
    fun newRide(epochMs: Long) {
        if (!enabled) return
        val dir = logDir ?: return
        val stamp = rideFmt.format(Instant.ofEpochMilli(epochMs))
        // Swap the file AND enqueue the banner under the same lock the flush drain uses, so the
        // banner can't be drained into the previous ride's file.
        sessionId = "%06x".format((epochMs xor (epochMs ushr 16)) and 0xFFFFFFL)
        synchronized(buffer) {
            baseName = "kpower-$stamp"
            part = 0
            logFile = currentFile()
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast("${ts.format(Instant.now())} I/kpower: ===== RIDE START ($stamp) =====")
        }
        flushSignal.trySend(null)
        scope.launch { purgeOldLogs(dir) }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!enabled) return
        val line = buildString {
            append(ts.format(Instant.now())); append(' ')
            append(levelChar(priority)); append('/')
            append(tag ?: "kpower"); append(": ")
            append(message)
            if (t != null) { append('\n'); append(Log.getStackTraceString(t)) }
        }
        synchronized(buffer) {
            if (buffer.size >= MAX_BUFFER) buffer.removeFirst()
            buffer.addLast(line)
        }
    }

    /** Drain the tail and close the writer (called when logging is turned OFF). */
    fun flushAndClose() {
        closeRequested = true
        flushSignal.trySend(null)
    }

    /**
     * Drain the buffer to the current log file. Called ONLY from the flush-loop coroutine, so
     * [writer]/[writerFile]/[part] mutations here need no extra synchronisation (single writer).
     */
    private fun flush() {
        val targetFile: File
        val lines: List<String>
        synchronized(buffer) {
            if (buffer.isEmpty()) return
            targetFile = logFile ?: return
            lines = buffer.toList()
            buffer.clear()
        }
        // Re-open the writer when the target file changed (newRide / rotation).
        if (writerFile != targetFile) {
            runCatching { writer?.close() }
            writer = runCatching { FileWriter(targetFile, /* append= */ true).buffered() }.getOrNull()
            writerFile = targetFile
        }
        val w = writer ?: return
        runCatching {
            lines.forEach { line -> w.write(line); w.newLine() }
            w.flush()   // flush to OS; keep the writer open for the next cycle
        }.onFailure {
            runCatching { writer?.close() }
            writer = null
            writerFile = null
        }
        // Rotate if this file has grown past the per-file cap, so a single ride can't grow one
        // file without bound. Purge afterwards to keep the total byte budget mid-ride.
        val wf = writerFile
        if (wf != null && runCatching { wf.length() }.getOrDefault(0L) > MAX_FILE_BYTES) {
            rotate()
            logDir?.let { purgeOldLogs(it) }
        }
    }

    /** Move to the next file part (closes the current writer; next flush re-opens). */
    private fun rotate() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        writerFile = null
        synchronized(buffer) {
            part += 1
            logFile = currentFile()
        }
    }

    private fun closeWriter() {
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
        writer = null
        writerFile = null
    }

    /**
     * Delete the oldest `.log` files so at most [MAX_LOG_FILES] remain AND the total stays within
     * [MAX_TOTAL_BYTES]. The currently-active file is never deleted.
     */
    private fun purgeOldLogs(dir: File) {
        runCatching {
            val logs = (dir.listFiles { f -> f.name.endsWith(".log") } ?: return)
                .sortedBy { it.lastModified() }
            val active = logFile
            var count = logs.size
            var total = logs.sumOf { runCatching { it.length() }.getOrDefault(0L) }
            for (f in logs) {
                if (count <= MAX_LOG_FILES && total <= MAX_TOTAL_BYTES) break
                if (f == active) continue
                val len = runCatching { f.length() }.getOrDefault(0L)
                if (f.delete()) { count -= 1; total -= len }
            }
        }
    }

    private fun levelChar(p: Int): Char = when (p) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }

    /** Absolute path of the current log file, for the settings hint. */
    fun pathHint(): String = logFile?.absolutePath ?: "(not started yet)"
}

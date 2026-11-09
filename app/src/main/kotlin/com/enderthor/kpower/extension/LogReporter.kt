package com.enderthor.kpower.extension

import com.enderthor.kpower.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Sends the KPower diagnostic log to the developer's Telegram — only when the rider has the
 * (off-by-default) diagnostic log enabled. Ported from KGhost's LogReporter.
 *
 * Privacy:
 *  - **No bot credentials in the APK**: uploads go to a write-only relay (tools/log-relay, a Cloudflare
 *    Worker) that holds the bot token and the chat id. Only the relay URL is compiled in
 *    ([BuildConfig.LOG_RELAY_URL], from `local.properties`); it can't read, forward or delete anything.
 *    Absent URL makes this a no-op.
 *  - **No GPS coordinates or sensor identifiers leave the device**: [redactForUpload] strips every
 *    coordinate token (lat/lng/lon/latitude/longitude — incl. inside `GpsCoordinates(...)`) AND sensor
 *    serial numbers + the Karoo's saved-device names from the uploaded copy. The on-device file keeps
 *    full detail.
 *
 * Overhead: stateless. If the caller never invokes [sendLogFile] there is no background work; a send is a
 * single multipart POST run on the caller's IO coroutine.
 */
object LogReporter {

    // From local.properties at compile time: calib.relay_url=https://<worker>.workers.dev
    private val RELAY_URL: String get() = BuildConfig.LOG_RELAY_URL
    private const val APP_TAG = "kpower"   // must be in the Worker's ALLOWED_APPS
    private const val TIMEOUT_MS = 60_000L

    // Any coordinate token (`lat=`, `lng=`, `lon=` — note GpsCoordinates.toString() uses `lon=` —,
    // `latitude=`, `longitude=`) → `=•`. Then sensor serials and the Karoo saved-device names.
    private val COORD = Regex("(lat|lng|lon|latitude|longitude)=-?\\d+(\\.\\d+)?")
    private val CLASSIFY_COORDS = Regex("classifyAt\\([^\\r\\n)]*\\)")
    // Map tile x/y (z16 ≈ 0,5 km) is a location too: SurfaceConditionReader logs it on a mapfile miss.
    private val TILE = Regex("\\btile \\d+/\\d+(/\\d+)?")
    private val CALIBRATION_START = Regex("(CALIBRATION START #\\d+) \\([^\\r\\n)]*\\)")
    private val IDENTITY_PAYLOAD = Regex("(PAGE 0x5[01] payload=)[0-9A-Fa-f ]+")
    private val ANT_DEVICE = Regex("\\bdev=(\\d+)")
    // A DENYLIST, not an allowlist: everything `#<digits>` is treated as a device number except the
    // handful of known counters. An allowlist of prefixes would fail OPEN — several strings already
    // embed a raw device number ("Power #\$dn", "KPW #\$deviceNumber") and would leak the day someone
    // logs one. The exclusion exists because a bare `#\\d+` also ate `failure #3` in "RawAntChannel
    // #6593 reopen in 2000ms (failure #3: death)", making the retry counter unreadable on exactly the
    // failure path the log exists to diagnose.
    private val HASH_DEVICE = Regex("(?<!failure )#(\\d+)")
    // Any absolute path: mapfile and cache paths are named after the rider's region, and exception
    // messages (which FileLogTree appends in full) and kotlinx-serialization parse errors drag them —
    // and the offending JSON — into the upload.
    private val FS_PATH = Regex("/(storage|sdcard|data|mnt)/\\S+")
    // `conn=` is a connection TYPE (ANT_PLUS / BLE), not an identifier — redacting it threw away the
    // only ANT-vs-BLE signal in the log for no privacy gain. Only `id=` is an identifier.
    private val SAVED_IDS = Regex("\\bid=\\S+")
    private val SERIAL = Regex("serial=\\S+")
    // Order-INDEPENDENT: the old lookahead required ` serial=` to follow `name=`, so reordering the
    // KAROODEV format string (or inserting a field) would have shipped the rider's sensor name to
    // Telegram unredacted, silently. Now it runs to the next known field or to end of line, so an
    // unrecognised layout over-redacts instead of leaking.
    private val DEVICE_NAME =
        Regex("name=.*?(?=\\s(?:serial|batt|conn|id)=|$)", RegexOption.MULTILINE)

    /** True only when a real bot token + chat id were compiled in (so callers can skip work entirely). */
    val configured: Boolean
        get() = RELAY_URL.startsWith("https://")

    sealed class SendResult(val message: String) {
        class Success(message: String) : SendResult(message)
        class Failure(message: String) : SendResult(message)
        val ok: Boolean get() = this is Success
    }

    /**
     * Replaces every device number with an alias (A, B, … then AA) instead of a blanket `•`: the real
     * ANT id never leaves, but two meters in the same log stay tellable apart — otherwise a dual-meter
     * session redacts to lines nobody can attribute. Aliases are assigned in order of first appearance
     * and are meaningless outside this process, so they cannot be reversed to a device number.
     *
     * Held for the LIFE OF THE PROCESS, not per call: a ride's log is uploaded in ~30 chunks, each
     * redacted separately, so a per-call map made the same meter `A` in one chunk and `B` in the next —
     * defeating the whole point. Concurrent because the periodic uploader and the pairing screen can
     * redact at the same time.
     */
    private val deviceAliases = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun aliasOf(number: String): String = synchronized(deviceAliases) {
        // NOT getOrPut alone: it is get-then-put (not computeIfAbsent) and `size` is read outside any
        // lock, so two concurrent redactions could assign the same letter to two different meters. This
        // runs a handful of times per upload, not per line, so the monitor costs nothing.
        deviceAliases.getOrPut(number) {
            val n = deviceAliases.size
            ('A' + n % 26).toString().repeat(n / 26 + 1)
        }
    }

    private fun aliasDeviceNumbers(text: String): String = text
        .replace(ANT_DEVICE) { "dev=" + aliasOf(it.groupValues[1]) }
        .replace(HASH_DEVICE) { "#" + aliasOf(it.groupValues[1]) }

    /** Strips GPS coordinates + sensor serials + saved-device names so no location/identity leaves. */
    fun redactForUpload(content: String): String = aliasDeviceNumbers(
        content
            .replace(CLASSIFY_COORDS, "classifyAt(•,•)")
            .replace(TILE, "tile •")
            .replace(CALIBRATION_START, "$1 (•)")
            .replace(IDENTITY_PAYLOAD, "$1•")
            .replace(COORD, "$1=•")
            .replace(DEVICE_NAME, "name=•")
            .replace(SERIAL, "serial=•")
            .replace(SAVED_IDS, "id=•")
            .replace(FS_PATH, "•")
    )

    /**
     * Sends [content] (redacted) as a Telegram document named [fileName], with [caption] as the message
     * text, to the hardcoded developer chat. Returns a [SendResult].
     */
    suspend fun sendLogFile(
        content: String,
        fileName: String,
        caption: String,
        karooSystem: KarooSystemService,
    ): SendResult {
        if (!configured) {
            Timber.w("LogReporter: relay URL not set in local.properties — skipping log send")
            return SendResult.Failure("Logging credentials not configured in this build.")
        }
        val redacted = redactForUpload(content)
        if (redacted.isBlank()) return SendResult.Failure("Nothing to send (log is empty).")
        return sendDocument(redacted.toByteArray(Charsets.UTF_8), "text/plain; charset=UTF-8", fileName, caption, karooSystem)
    }

    /** POSTs [content] as a Telegram document. Callers MUST pass already-redacted content. */
    private suspend fun sendDocument(
        content: ByteArray,
        contentType: String,
        fileName: String,
        caption: String,
        karooSystem: KarooSystemService,
    ): SendResult {
        return try {
            val boundary = "KPowerBoundary_${System.currentTimeMillis()}"
            val body = buildMultipart(boundary, fileName, content, contentType, caption)
            val kb = body.size / 1024
            Timber.d("LogReporter: sending %d KB to Telegram…", kb)

            val response: HttpResponseState.Complete? = withTimeoutOrNull(TIMEOUT_MS) {
                karooSystem.httpRequest(
                    "POST",
                    RELAY_URL,
                    mapOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                    body,
                )
            }
            if (response == null) {
                Timber.w("LogReporter: timeout (%d KB)", kb)
                return SendResult.Failure("Timeout after ${TIMEOUT_MS / 1000}s — $kb KB. Karoo Companion may be offline.")
            }
            val respBody = response.body?.toString(Charsets.UTF_8) ?: ""
            val ok = response.statusCode in 200..299 && respBody.contains("\"ok\":true")
            if (ok) {
                Timber.i("LogReporter: log delivered ✓ (%d KB)", kb)
                SendResult.Success("Sent ✓ ($kb KB)")
            } else {
                val tgDesc = Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(respBody)?.groupValues?.getOrNull(1)
                val hint = when (response.statusCode) {
                    401, 404 -> "Relay rejected the bot token or chat id (check the Worker secrets)."
                    403 -> "Relay doesn't accept this app (check ALLOWED_APPS)."
                    400 -> tgDesc ?: "Bad request."
                    413 -> "Chunk too big for the relay."
                    429 -> "Rate-limited by Telegram. Wait a minute."
                    in 500..599 -> "Telegram server error (try again)."
                    else -> tgDesc ?: "HTTP ${response.statusCode}"
                }
                Timber.w("LogReporter: delivery failed — HTTP %d: %s", response.statusCode, respBody.take(200))
                SendResult.Failure("Send failed: $hint")
            }
        } catch (e: Exception) {
            Timber.e(e, "LogReporter: error during send")
            SendResult.Failure("Send error: ${e.javaClass.simpleName} — ${e.message ?: "no message"}")
        }
    }

    /** Hard UTF-8 byte cap per document (host MakeHttpRequest binder limit ~100 KB); split bigger logs. */
    private const val MAX_DOC_BYTES = 85_000

    /**
     * Send [text] (redacted) to the developer chat, split into line-boundary chunks each under
     * [MAX_DOC_BYTES] so no single multipart body exceeds the host's binder limit. Used for off-ride
     * pairing/calibration diagnostics (the in-ride uploader in KpowerExtension has its own byte-offset
     * streaming). Returns the first failure, or a success summarising the chunk count.
     */
    suspend fun sendTextChunked(
        text: String,
        fileNamePrefix: String,
        captionPrefix: String,
        karooSystem: KarooSystemService,
    ): SendResult {
        if (!configured) return SendResult.Failure("Logging credentials not configured in this build.")
        val redacted = redactForUpload(text)
        if (redacted.isBlank()) return SendResult.Failure("Nothing to send (log is empty).")
        var pos = 0
        var seq = 0
        while (pos < redacted.length) {
            val hardEnd = minOf(pos + MAX_DOC_BYTES, redacted.length)
            val nl = redacted.lastIndexOf('\n', hardEnd - 1)
            var end = if (nl > pos) nl + 1 else hardEnd
            // Shrink until the chunk's UTF-8 size fits (multi-byte chars expand).
            while (end > pos + 1 && redacted.substring(pos, end).toByteArray(Charsets.UTF_8).size > MAX_DOC_BYTES) {
                end = pos + (end - pos) / 2
            }
            val chunk = redacted.substring(pos, end)
            val fileName = "${fileNamePrefix}_p${"%02d".format(seq)}.log"
            val res = sendLogFile(chunk, fileName, "$captionPrefix (part ${seq + 1})", karooSystem)
            if (!res.ok) return res
            pos = end; seq++
        }
        return SendResult.Success("Sent ✓ ($seq part(s))")
    }

    /**
     * Redact [text], gzip it (logs compress ~10x, so a long ride is tens of POSTs instead of hundreds)
     * and send it as `<prefix>.log.gz.partNN` slices under [MAX_DOC_BYTES]. Rebuild on the dev side
     * with `cat <prefix>.log.gz.part* | gunzip`. Each slice is retried once (Bluetooth hiccup / 429)
     * before giving up. [onPart] reports (sent, total) for a progress line.
     */
    suspend fun sendGzipChunked(
        text: String,
        fileNamePrefix: String,
        captionPrefix: String,
        karooSystem: KarooSystemService,
        onPart: (Int, Int) -> Unit = { _, _ -> },
    ): SendResult {
        if (!configured) return SendResult.Failure("Logging credentials not configured in this build.")
        val redacted = redactForUpload(text)
        if (redacted.isBlank()) return SendResult.Failure("Nothing to send (log is empty).")
        val gz = java.io.ByteArrayOutputStream().also { bos ->
            java.util.zip.GZIPOutputStream(bos).use { it.write(redacted.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        val total = (gz.size + MAX_DOC_BYTES - 1) / MAX_DOC_BYTES
        for (i in 0 until total) {
            val slice = gz.copyOfRange(i * MAX_DOC_BYTES, minOf((i + 1) * MAX_DOC_BYTES, gz.size))
            val name = "$fileNamePrefix.log.gz.part${"%02d".format(i)}"
            val caption = "$captionPrefix (part ${i + 1}/$total)"
            var res = sendDocument(slice, "application/octet-stream", name, caption, karooSystem)
            if (!res.ok) {
                kotlinx.coroutines.delay(5_000)
                res = sendDocument(slice, "application/octet-stream", name, caption, karooSystem)
            }
            if (!res.ok) return res
            onPart(i + 1, total)
        }
        return SendResult.Success("Sent ✓ ($total part(s))")
    }

    /** Builds a `multipart/form-data` body for the Telegram Bot API `sendDocument` endpoint. */
    private fun buildMultipart(
        boundary: String, fileName: String, fileContent: ByteArray, contentType: String, caption: String,
    ): ByteArray {
        val crlf = "\r\n"
        val head = buildString {
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"app\"$crlf$crlf")
            append(APP_TAG); append(crlf)
            if (caption.isNotBlank()) {
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"caption\"$crlf$crlf")
                append(caption); append(crlf)
            }
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"$crlf")
            append("Content-Type: $contentType$crlf$crlf")
        }.toByteArray(Charsets.UTF_8)
        return head + fileContent + "$crlf--$boundary--$crlf".toByteArray(Charsets.UTF_8)
    }
}

package com.enderthor.kpower.extension

import com.enderthor.kpower.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Sends the KPower diagnostic log to the developer via a Telegram bot — only when the rider has the
 * (off-by-default) diagnostic log enabled. Ported from KGhost's LogReporter.
 *
 * Privacy:
 *  - **Credentials never in source / never in any settings screen**: BOT_TOKEN and CHAT_ID come from
 *    [BuildConfig], injected from `local.properties` (gitignored) at compile time. The log can only ever
 *    go to the developer's own chat; absent keys make this a no-op.
 *  - **No GPS coordinates or sensor identifiers leave the device**: [redactForUpload] strips every
 *    coordinate token (lat/lng/lon/latitude/longitude — incl. inside `GpsCoordinates(...)`) AND sensor
 *    serial numbers + the Karoo's saved-device names from the uploaded copy. The on-device file keeps
 *    full detail.
 *
 * Overhead: stateless. If the caller never invokes [sendLogFile] there is no background work; a send is a
 * single multipart POST run on the caller's IO coroutine.
 */
object LogReporter {

    // From local.properties at compile time: calib.bot_token=<@BotFather token>, calib.chat_id=<dev chat>.
    private val BOT_TOKEN: String get() = BuildConfig.CALIB_BOT_TOKEN
    private val CHAT_ID: String get() = BuildConfig.CALIB_CHAT_ID

    private const val API_BASE = "https://api.telegram.org/bot"
    private const val TIMEOUT_MS = 60_000L

    // Any coordinate token (`lat=`, `lng=`, `lon=` — note GpsCoordinates.toString() uses `lon=` —,
    // `latitude=`, `longitude=`) → `=•`. Then sensor serials and the Karoo saved-device names.
    private val COORD = Regex("(lat|lng|lon|latitude|longitude)=-?\\d+(\\.\\d+)?")
    private val CLASSIFY_COORDS = Regex("classifyAt\\([^\\r\\n)]*\\)")
    private val CALIBRATION_START = Regex("(CALIBRATION START #\\d+) \\([^\\r\\n)]*\\)")
    private val IDENTITY_PAYLOAD = Regex("(PAGE 0x5[01] payload=)[0-9A-Fa-f ]+")
    private val ANT_DEVICE = Regex("\\bdev=(\\d+)")
    private val HASH_DEVICE = Regex("#(\\d+)")
    private val SAVED_IDS = Regex("\\b(id|conn)=\\S+")
    private val SERIAL = Regex("serial=\\S+")
    private val DEVICE_NAME = Regex("name=.*?(?= serial=)")   // the KAROODEV "saved id=… name=X serial=…" line

    /** True only when a real bot token + chat id were compiled in (so callers can skip work entirely). */
    val configured: Boolean
        get() = BOT_TOKEN.isNotBlank() && !BOT_TOKEN.startsWith("REPLACE") &&
            CHAT_ID.isNotBlank() && !CHAT_ID.startsWith("REPLACE")

    sealed class SendResult(val message: String) {
        class Success(message: String) : SendResult(message)
        class Failure(message: String) : SendResult(message)
        val ok: Boolean get() = this is Success
    }

    /**
     * Replaces every device number with a per-file alias (A, B, … then AA) instead of a blanket `•`:
     * the real ANT id never leaves, but two meters in the same log stay tellable apart — otherwise a
     * dual-meter session redacts to lines nobody can attribute. Aliases are assigned in order of first
     * appearance and are meaningless outside this one file.
     */
    private fun aliasDeviceNumbers(text: String): String {
        val alias = HashMap<String, String>()
        fun aliasOf(number: String) = alias.getOrPut(number) {
            ('A' + alias.size % 26).toString().repeat(alias.size / 26 + 1)
        }
        return text
            .replace(ANT_DEVICE) { "dev=" + aliasOf(it.groupValues[1]) }
            .replace(HASH_DEVICE) { "#" + aliasOf(it.groupValues[1]) }
    }

    /** Strips GPS coordinates + sensor serials + saved-device names so no location/identity leaves. */
    fun redactForUpload(content: String): String = aliasDeviceNumbers(
        content
            .replace(CLASSIFY_COORDS, "classifyAt(•,•)")
            .replace(CALIBRATION_START, "$1 (•)")
            .replace(IDENTITY_PAYLOAD, "$1•")
            .replace(COORD, "$1=•")
            .replace(DEVICE_NAME, "name=•")
            .replace(SERIAL, "serial=•")
            .replace(SAVED_IDS, "$1=•")
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
            Timber.w("LogReporter: credentials not set in local.properties — skipping log send")
            return SendResult.Failure("Logging credentials not configured in this build.")
        }
        val redacted = redactForUpload(content)
        if (redacted.isBlank()) return SendResult.Failure("Nothing to send (log is empty).")

        return try {
            val boundary = "KPowerBoundary_${System.currentTimeMillis()}"
            val body = buildMultipart(boundary, fileName, redacted, caption)
            val kb = body.size / 1024
            Timber.d("LogReporter: sending %d KB to Telegram…", kb)

            val response: HttpResponseState.Complete? = withTimeoutOrNull(TIMEOUT_MS) {
                karooSystem.httpRequest(
                    "POST",
                    "$API_BASE$BOT_TOKEN/sendDocument",
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
                    401 -> "Bot token rejected (check CALIB_BOT_TOKEN)."
                    400 -> tgDesc ?: "Bad request (check chat id)."
                    413 -> "File too big (Telegram limit is 50 MB)."
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

    /** Builds a `multipart/form-data` body for the Telegram Bot API `sendDocument` endpoint. */
    private fun buildMultipart(boundary: String, fileName: String, fileContent: String, caption: String): ByteArray {
        val crlf = "\r\n"
        return buildString {
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"chat_id\"$crlf$crlf")
            append(CHAT_ID); append(crlf)
            if (caption.isNotBlank()) {
                append("--$boundary$crlf")
                append("Content-Disposition: form-data; name=\"caption\"$crlf$crlf")
                append(caption); append(crlf)
            }
            append("--$boundary$crlf")
            append("Content-Disposition: form-data; name=\"document\"; filename=\"$fileName\"$crlf")
            append("Content-Type: text/plain; charset=UTF-8$crlf$crlf")
            append(fileContent); append(crlf)
            append("--$boundary--$crlf")
        }.toByteArray(Charsets.UTF_8)
    }
}

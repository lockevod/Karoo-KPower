package com.enderthor.kpower.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

import com.enderthor.kpower.R
import com.enderthor.kpower.ant.AntPowerManager
import com.enderthor.kpower.ant.CalibrationResult
import com.enderthor.kpower.ant.SavedMeter
import com.enderthor.kpower.ant.calibrateMeterRaw
import com.enderthor.kpower.ant.isAutoMeterLabel
import com.enderthor.kpower.BuildConfig
import com.enderthor.kpower.extension.karooNameForAnt
import com.enderthor.kpower.extension.savedDevicesFlow
import com.enderthor.kpower.extension.getOrCreateInstallId
import com.enderthor.kpower.extension.LogReporter
import timber.log.Timber
import com.enderthor.kpower.extension.FileLogTree
import com.enderthor.kpower.extension.antMetersFlow
import com.enderthor.kpower.extension.batteryAlertFlow
import com.enderthor.kpower.extension.comparisonModeFlow
import com.enderthor.kpower.extension.diagnosticLogFlow
import com.enderthor.kpower.extension.updateAntMeters
import com.enderthor.kpower.extension.saveBatteryAlert
import com.enderthor.kpower.extension.saveComparisonMode
import com.enderthor.kpower.extension.saveDiagnosticLog
import com.enderthor.kpower.extension.saveMeterScreenActive


// Re-stamp the meter-screen "radio active" signal this often WHILE ACTIVELY SCANNING. Must be
// ≤ METER_SCREEN_ACTIVE_BACKSTOP_MS / 2 so a single missed beat can't expire the window mid-scan.
private const val METER_SCREEN_HEARTBEAT_MS = 45_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // AntPowerManager + KarooSystemService lifetimes are tied to this composable.
    val antManager = remember { AntPowerManager(ctx) }
    val karooSystem = remember { KarooSystemService(ctx) }
    DisposableEffect(Unit) {
        karooSystem.connect {}
        // Stamp ONCE on enter: frees the service's off-ride meter channel with lead time before the first
        // scan (no churn — a single write). Refreshed on each scan tap (onToggleScan) and kept alive by the
        // scanning heartbeat below; goes stale within the backstop if you just browse without scanning.
        scope.launch { saveMeterScreenActive(ctx, true) }
        onDispose {
            antManager.close(); karooSystem.disconnect()
            // Clear on a scope that OUTLIVES this composable (rememberCoroutineScope is cancelled by now),
            // so the service re-opens the meter channel once we leave. Best-effort: the stamp also
            // auto-expires via the backstop if this write never lands (e.g. process kill).
            CoroutineScope(Dispatchers.IO).launch { saveMeterScreenActive(ctx, false) }
        }
    }
    // The Karoo's paired sensors — used to show the REAL name in the scan list (the Karoo already knows
    // it), instead of MultiDeviceSearch's generic "Device: <number>". Remembered so it isn't re-subscribed
    // on every recomposition.
    val karooDevicesFlow = remember { karooSystem.savedDevicesFlow().map { it.devices } }
    val karooDevices by karooDevicesFlow.collectAsState(initial = emptyList())
    // Names resolve fast the way the Karoo does it: when the wildcard scan finds a device, AntPowerManager
    // briefly opens that device's OWN bidirectional channel and ACTIVELY requests the 0x50 manufacturer page
    // (Request Data Page), exposing it on AntDeviceInfo.resolvedName. All raw — no antpluginlib/PCC, so no
    // cross-stack radio contention (that was the old USER_CANCELLED). The UI just reads resolvedName.

    // Collect all state here (Composable context) so antScanItems (LazyListScope, not
    // @Composable) can receive plain values — no nested @Composable calls inside the
    // LazyListScope extension.
    val comparisonMode by ctx.comparisonModeFlow().collectAsState(initial = false)
    val diagnosticLog by ctx.diagnosticLogFlow().collectAsState(initial = false)
    val batteryAlert by ctx.batteryAlertFlow().collectAsState(initial = false)
    val detected by antManager.detectedDevices.collectAsState()
    val saved by ctx.antMetersFlow().collectAsState(initial = emptyList())
    // Scan state comes from the manager: a scan SESSION auto-stops after one search window + identify,
    // so the button must follow the manager, not a local toggle.
    val scanning by antManager.scanning.collectAsState()

    // Heartbeat ONLY while actively scanning: refreshes the "radio active" stamp every ~45 s so a scan that
    // runs longer than the backstop can't expire the window mid-scan (which would let the service re-grab
    // the radio). Gated on `scanning` so merely browsing the saved list writes NOTHING — a write re-emits
    // the whole DataStore (incl. a meters JSON decode in the extension) for nothing. When scanning stops the
    // loop ends → the stamp goes stale within the backstop and the meter reconnects. Interval ≤ backstop/2
    // so one missed beat can't expire the window. (The Karoo's own sensorservice doesn't release channels to
    // scan at all — it relies on search-priority preemption + retry; this is our belt-and-suspenders.)
    LaunchedEffect(scanning) {
        if (scanning) {
            while (true) {
                saveMeterScreenActive(ctx, true)
                delay(METER_SCREEN_HEARTBEAT_MS)
            }
        }
    }

    // Byte offset into the diagnostic log captured at scan-start, so the scan-stop upload sends only
    // what this pairing session added (not the whole file).
    var scanLogOffset by remember { mutableStateOf(0L) }

    // Mirror the diagnostic-log toggle onto the file logger from the settings side too: ANT scan/pair/
    // calibration activity must be captured even when no ride is recording (so a rider's pairing problem
    // can be diagnosed). The extension service drives the same flag; this is an idempotent safety net for
    // when the user is in settings. Never turns it OFF here (the service owns flush-and-close on turn-off).
    LaunchedEffect(diagnosticLog) { if (diagnosticLog) FileLogTree.enabled = true }

    // Meter currently being renamed (null = no dialog).
    var renaming by remember { mutableStateOf<SavedMeter?>(null) }
    // Calibration dialog: the meter being calibrated, whether it's in progress, and the last result.
    var calibrating by remember { mutableStateOf<SavedMeter?>(null) }
    var calibrationRunning by remember { mutableStateOf(false) }
    var calibrationResult by remember { mutableStateOf<CalibrationResult?>(null) }

    // Persist a resolved name (and battery) onto a saved meter that still carries an auto label, as the
    // scan resolves the name. Keyed on `detected` only (NOT `saved`): the trigger is a name filling in;
    // updateAntMeters reads the CURRENT persisted list internally, so keying on `saved` would just
    // self-restart the effect on every meter toggle/edit.
    LaunchedEffect(detected) {
        // Keyed on `detected` (it changes when an identify resolves); the short name is read live from the
        // manager. One transaction for all meters; updateAntMeters skips the commit when nothing changed.
        updateAntMeters(ctx) { meters ->
            meters.map { m ->
                // SHORT name (model/brand) so the "KPW <label>" sensor name fits the Karoo Sensors screen.
                // Fall back to the FULL name if the short mirror hasn't populated yet (the two flows are
                // updated by separate bridge collectors, so the short one can briefly lag the `detected`
                // change that triggers this effect) — a real name beats staying stuck on the "#id" placeholder.
                val short = antManager.manufacturerShortFlow(m.deviceNumber).value
                    ?: antManager.manufacturerFlow(m.deviceNumber).value
                    ?: return@map m
                if (m.userNamed || !isAutoMeterLabel(m.label, m.deviceNumber)) m else m.copy(label = short)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_comparison)) }) },
        content = { padding ->
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // ANT+ scan UI first (the main content of this tab) — same LazyColumn, no nested scroll.
                antScanItems(
                    saved = saved,
                    detected = detected,
                    scanning = scanning,
                    karooNameFor = { dn ->
                        // Real name: paired Karoo name → resolved PCC name from the scan duty-cycle.
                        // Fall back to the bare number ONLY once an identify attempt has finished without a
                        // name; while still pending, return null so the row shows "Identifying…".
                        val d = detected.firstOrNull { it.deviceNumber == dn }
                        // Full "Brand Model" (e.g. "Garmin Rally 200") from the 0x50 page.
                        karooDevices.karooNameForAnt(dn) ?: d?.resolvedName
                            ?: if (d?.identifyTried == true) "#$dn" else null
                    },
                    onToggleScan = {
                        if (scanning) {
                            antManager.stopScan()
                            Timber.i("===== ANT SCAN STOP =====")
                            // Off-ride pairing diagnostics: upload the scan-session log so a rider's
                            // pairing problem can be diagnosed even with no ride recording. No-op when
                            // diagnostic logging is off or no Telegram credentials are compiled in.
                            uploadPairingLog(ctx, karooSystem, diagnosticLog, "scan", scanLogOffset)
                        } else {
                            // The radio-active stamp is written by LaunchedEffect(scanning)'s first pass (it
                            // fires the moment scanning flips true), so don't ALSO write it here — that was two
                            // full DataStore writes (+ two meters-JSON decodes in the service) per scan tap.
                            scanLogOffset = pairingLogOffset()   // upload only what this scan session adds
                            Timber.i("===== ANT SCAN START =====")
                            antManager.startScan()
                        }
                    },
                    onAdd = { dev ->
                        // Adding one meter stops the scan: the raw channel (for live battery/brand) and
                        // MultiDeviceSearch can't coexist, and you normally add one then look at it.
                        // Scan again to add another.
                        // Adding also stops the scan, so upload the scan session here too (else it's lost).
                        val wasScanning = scanning
                        antManager.stopScan()
                        if (wasScanning) uploadPairingLog(ctx, karooSystem, diagnosticLog, "scan", scanLogOffset)
                        // Atomic transform off the CURRENT persisted list (not the stale `saved`
                        // snapshot), so a concurrent write can't be clobbered.
                        scope.launch {
                            updateAntMeters(ctx) { current ->
                                // All saved meters share slot 0: only one can be ACTIVE at a time, and
                                // that one drives the single field set + pm1_* FIT fields. A newly added
                                // meter is enabled ONLY if no meter is currently active, so adding to the
                                // garage never silently creates a second active meter.
                                if (current.size >= MAX_METERS || current.any { it.deviceNumber == dev.deviceNumber }) current
                                // Save the REAL name if we already have it (Karoo paired name → resolved
                                // 0x50 name), not the bare "Power #<id>" placeholder. If none yet, the
                                // placeholder stays an auto label and the brand auto-detect fills it later.
                                else {
                                    val best = karooDevices.karooNameForAnt(dev.deviceNumber)
                                        ?: dev.resolvedName ?: dev.name
                                    current + SavedMeter(dev.deviceNumber, best, slot = 0, enabled = current.none { it.enabled })
                                }
                            }
                        }
                        // The wildcard background scan resolves name+battery live (parsed from the 0x50/0x52
                        // pages); the persist effect below copies them onto the saved meter. If added before
                        // the name arrived, the recorded meter's own raw channel resolves it once enabled.
                    },
                    onDelete = { m ->
                        scope.launch {
                            updateAntMeters(ctx) { current -> current.filterNot { it.deviceNumber == m.deviceNumber } }
                        }
                    },
                    onToggleEnabled = { m, en ->
                        // Only ONE real meter may be active at a time (two enabled meters would
                        // record conflicting power/dynamics into the same FIT slot). Enabling one
                        // therefore disables every other; disabling just clears that one.
                        scope.launch {
                            updateAntMeters(ctx) { current ->
                                current.map {
                                    when {
                                        it.deviceNumber == m.deviceNumber -> it.copy(enabled = en)
                                        en -> it.copy(enabled = false)
                                        else -> it
                                    }
                                }
                            }
                        }
                    },
                    onRename = { renaming = it },
                    onCalibrate = { m ->
                        // Calibration is done on OUR OWN raw bidirectional channel (page 0x01), the same
                        // way the Karoo does it — no antpluginlib/PCC, so no cross-stack radio contention
                        // (that was why it used to work only sometimes). Stop the scan first to free a
                        // channel; a brief settle lets the scan channel finish releasing.
                        // If a scan was running, upload ITS session too — calibrating stops the scan, so
                        // without this the scan log (incl. the meter's identify) would never be sent.
                        val wasScanning = scanning
                        antManager.stopScan()
                        if (wasScanning) uploadPairingLog(ctx, karooSystem, diagnosticLog, "scan", scanLogOffset)
                        calibrating = m
                        calibrationResult = null
                        calibrationRunning = true
                        val logFrom = pairingLogOffset()   // capture pairing log from here (if logging on)
                        Timber.i("===== CALIBRATION START #%d (%s) =====", m.deviceNumber, m.label)
                        scope.launch {
                            // finally guarantees the dialog never gets stuck on the spinner (Close
                            // disabled + dismiss blocked) if calibration throws.
                            try {
                                delay(800)
                                calibrationResult = calibrateMeterRaw(ctx, m.deviceNumber)
                                    .also { Timber.i("raw calibrate #%d result=%s", m.deviceNumber, it) }
                            } finally {
                                calibrationRunning = false
                                // Off-ride pairing diagnostics: upload the calibration log so problems can
                                // be diagnosed even though no ride is recording. No-op unless logging is on.
                                uploadPairingLog(ctx, karooSystem, diagnosticLog, "calibration", logFrom)
                            }
                        }
                    },
                )

                // Short cycling-dynamics note, right after the ANT meters block. Dynamics from an
                // enabled meter are written to the FIT automatically — no opt-in toggle needed.
                item {
                    Text(
                        text = stringResource(R.string.dynamics_auto_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }

                // Comparison toggle (near the bottom): just enables writing the ESTIMATE to the FIT.
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(5.dp),
                        shape = RoundedCornerShape(corner = CornerSize(10.dp))
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = comparisonMode,
                                    onCheckedChange = { enabled ->
                                        scope.launch { saveComparisonMode(ctx, enabled) }
                                    }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.comparison_mode_label))
                            }
                            Text(
                                text = stringResource(R.string.comparison_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Battery-alert toggle: one-time in-ride alert on low, and again on critical (max 2/ride).
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(5.dp),
                        shape = RoundedCornerShape(corner = CornerSize(10.dp))
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = batteryAlert,
                                    onCheckedChange = { enabled ->
                                        scope.launch { saveBatteryAlert(ctx, enabled) }
                                    }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.battery_alert_label))
                            }
                            Text(
                                text = stringResource(R.string.battery_alert_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Last item: diagnostic-logging toggle (kept at the very bottom).
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(5.dp),
                        shape = RoundedCornerShape(corner = CornerSize(10.dp))
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = diagnosticLog,
                                    onCheckedChange = { enabled ->
                                        scope.launch { saveDiagnosticLog(ctx, enabled) }
                                    }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(stringResource(R.string.diagnostic_log_label))
                            }
                            Text(
                                text = stringResource(R.string.diagnostic_log_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.diagnostic_log_path_prefix) + FileLogTree.pathHint(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Rename a saved meter. ANT+ power meters usually advertise only a device number, so
            // let the rider give it a friendly name (shown as "KPW <name>" when pairing in the Karoo).
            renaming?.let { meter ->
                var draft by remember(meter.deviceNumber) { mutableStateOf(meter.label) }
                AlertDialog(
                    onDismissRequest = { renaming = null },
                    title = { Text(stringResource(R.string.ant_rename)) },
                    text = {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            label = { Text(stringResource(R.string.cfg_name)) },
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val newLabel = draft.trim().ifEmpty { meter.label }
                            scope.launch {
                                updateAntMeters(ctx) { current ->
                                    current.map {
                                        if (it.deviceNumber == meter.deviceNumber)
                                            it.copy(label = newLabel, userNamed = true) else it
                                    }
                                }
                            }
                            renaming = null
                        }) { Text(stringResource(R.string.btn_save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.btn_cancel)) }
                    },
                )
            }

            // Manual (zero-offset) calibration over the PCC — keep cranks still, not during a ride.
            calibrating?.let { meter ->
                AlertDialog(
                    onDismissRequest = { if (!calibrationRunning) calibrating = null },
                    title = { Text(stringResource(R.string.calibrate_title)) },
                    text = {
                        Column {
                            Text(meter.label, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            if (calibrationRunning) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(stringResource(R.string.calibrate_running))
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.calibrate_instruction),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    when (val r = calibrationResult) {
                                        is CalibrationResult.Success ->
                                            if (r.zeroOffset != null) stringResource(R.string.calibrate_success, r.zeroOffset)
                                            else stringResource(R.string.calibrate_success_no_value)
                                        CalibrationResult.Failed -> stringResource(R.string.calibrate_failed)
                                        is CalibrationResult.Unreachable -> stringResource(R.string.calibrate_unreachable)
                                        CalibrationResult.Timeout -> stringResource(R.string.calibrate_timeout)
                                        null -> ""
                                    }
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            enabled = !calibrationRunning,
                            onClick = { calibrating = null },
                        ) { Text(stringResource(R.string.btn_close)) }
                    },
                )
            }
        }
    )
}

/** Current byte length of the diagnostic log file (0 if none) — the start offset for a session upload. */
private fun pairingLogOffset(): Long =
    FileLogTree.currentLogFile()?.let { runCatching { it.length() }.getOrDefault(0L) } ?: 0L

/**
 * Off-ride pairing/calibration diagnostics: upload the part of the diagnostic log added since
 * [fromByte] to the developer's Telegram, so a rider's pairing problem can be diagnosed even though no
 * ride is recording (the in-ride uploader only runs while recording). Fire-and-forget on its own IO
 * scope (it must outlive the click handler / the composable). No-op when diagnostic logging is off or
 * no Telegram credentials are compiled into this build. GPS/identity are stripped by [LogReporter].
 */
private fun uploadPairingLog(
    ctx: Context,
    karooSystem: KarooSystemService,
    enabled: Boolean,
    prefix: String,
    fromByte: Long,
) {
    if (!enabled || !LogReporter.configured) return
    CoroutineScope(Dispatchers.IO).launch {
        FileLogTree.flushNow()   // SUSPEND until the whole buffer is on disk, then read a complete tail
        val file = FileLogTree.currentLogFile() ?: return@launch
        var fileLen = 0L
        val text = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val len = raf.length(); fileLen = len
                // If the log ROTATED (5 MB cap) or a ride swapped the file since scan-start, the captured
                // start offset can exceed the new (shorter) file → reading from it would give nothing. In
                // that case read the whole current file from 0 (it's small just after a rotation).
                val from = if (fromByte in 0L..len) fromByte else 0L
                if (len <= from) return@use ""
                raf.seek(from)
                val buf = ByteArray((len - from).toInt())
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        }.getOrNull()
        Timber.i("pairing log %s: from=%d fileLen=%d bytes=%d lines=%d", prefix,
            fromByte, fileLen, text?.toByteArray(Charsets.UTF_8)?.size ?: 0, text?.count { it == '\n' } ?: 0)
        if (text.isNullOrBlank()) return@launch
        val id = runCatching { ctx.getOrCreateInstallId() }.getOrNull() ?: "anon"
        val ver = BuildConfig.VERSION_NAME
        val res = LogReporter.sendTextChunked(
            text = text,
            fileNamePrefix = "kpower_${prefix}_v${ver}_$id",
            captionPrefix = "KPower $prefix log\nAnon tag: $id | v$ver",
            karooSystem = karooSystem,
        )
        Timber.i("pairing log upload (%s): %s", prefix, res.message)
    }
}

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import com.enderthor.kpower.R
import com.enderthor.kpower.ant.AntPowerManager
import com.enderthor.kpower.ant.CalibrationResult
import com.enderthor.kpower.ant.SavedMeter
import com.enderthor.kpower.ant.calibrateMeterViaPcc
import com.enderthor.kpower.ant.identifyMeterViaPcc
import com.enderthor.kpower.ant.isAutoMeterLabel
import com.enderthor.kpower.extension.karooNameForAnt
import com.enderthor.kpower.extension.savedDevicesFlow
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
        onDispose { antManager.close(); karooSystem.disconnect() }
    }
    // The Karoo's paired sensors — used to show the REAL name in the scan list (the Karoo already knows
    // it), instead of MultiDeviceSearch's generic "Device: <number>". Remembered so it isn't re-subscribed
    // on every recomposition.
    val karooDevicesFlow = remember { karooSystem.savedDevicesFlow().map { it.devices } }
    val karooDevices by karooDevicesFlow.collectAsState(initial = emptyList())
    // Names READ live via the antlib PCC for detected devices the Karoo has NOT paired (SavedDevices has
    // no name). Resolved concurrently with the search, like the Karoo's pairing screen.
    val identifiedNames = remember { mutableStateMapOf<Int, String>() }
    // Devices whose identify finished WITHOUT a name (asleep / no common page within the timeout): we
    // fall back to showing the bare number for these instead of "Identifying…" forever.
    val identifyFailed = remember { mutableStateMapOf<Int, Boolean>() }
    // Bound concurrent PCC identify connections so a populated scan can't exhaust the ANT radio channels.
    val identifySem = remember { Semaphore(3) }

    // Collect all state here (Composable context) so antScanItems (LazyListScope, not
    // @Composable) can receive plain values — no nested @Composable calls inside the
    // LazyListScope extension.
    val comparisonMode by ctx.comparisonModeFlow().collectAsState(initial = false)
    val diagnosticLog by ctx.diagnosticLogFlow().collectAsState(initial = false)
    val batteryAlert by ctx.batteryAlertFlow().collectAsState(initial = false)
    val detected by antManager.detectedDevices.collectAsState()
    val saved by ctx.antMetersFlow().collectAsState(initial = emptyList())
    var scanning by remember { mutableStateOf(false) }

    // Meter currently being renamed (null = no dialog).
    var renaming by remember { mutableStateOf<SavedMeter?>(null) }
    // Calibration dialog: the meter being calibrated, whether it's in progress, and the last result.
    var calibrating by remember { mutableStateOf<SavedMeter?>(null) }
    var calibrationRunning by remember { mutableStateOf(false) }
    var calibrationResult by remember { mutableStateOf<CalibrationResult?>(null) }

    // Resolve each device's name+battery the SAME way the Karoo does: keep MultiDeviceSearch running AND,
    // as each device appears, open antlib's BikePower PCC for it and requestCommonDataPage → the
    // manufacturer/model arrive in ~1 s while the search keeps finding others. The PCC multiplexes on the
    // ANT+ Plugins Service alongside the search (this is exactly what the Karoo's pairing does); the
    // earlier USER_CANCELLED was OUR re-keying cancelling the PCC, plus the missing Plugins-Service
    // <queries> entry — both fixed.
    //
    // Each device gets its OWN child coroutine (launch), so finding a NEW device never cancels an
    // in-flight identify. Keyed ONLY on `calibrating`: a calibration needs the device's PCC free, so when
    // it starts we cancel these (releasing the channels); otherwise this runs continuously (keyed off
    // `detected` via snapshotFlow inside, NOT as an effect key, so battery writes can't churn it).
    LaunchedEffect(calibrating) {
        if (calibrating != null) return@LaunchedEffect
        val inFlight = HashSet<Int>()
        snapshotFlow { detected.map { it.deviceNumber } }.collect { dns ->
            for (dn in dns) {
                // Skip in-flight, already-named, Karoo-named, and devices that already failed this
                // session (re-armed by starting a new scan, which clears identifyFailed).
                if (dn in inFlight || identifiedNames.containsKey(dn) || identifyFailed.containsKey(dn)) continue
                if (karooDevices.karooNameForAnt(dn) != null) continue
                inFlight.add(dn)
                launch {
                    // Cap concurrent PCC connections (the ANT radio has few channels; the search uses one
                    // too) so a populated scan can't exhaust the radio. Bounded timeout so a silent/asleep
                    // device releases its PCC instead of pinning a channel. Returns the name (or null).
                    val name = identifySem.withPermit {
                        withTimeoutOrNull(10_000) {
                            identifyMeterViaPcc(
                                ctx, dn,
                                onBattery = { code ->
                                    // Battery callback is on a binder thread; updateAntMeters needs a
                                    // coroutine anyway, so launch on the Main scope.
                                    if (code != null) scope.launch {
                                        updateAntMeters(ctx) { meters ->
                                            meters.map { if (it.deviceNumber == dn && it.lastBatteryCode != code) it.copy(lastBatteryCode = code) else it }
                                        }
                                    }
                                },
                            )
                        }
                    }
                    inFlight.remove(dn)
                    // This child runs on the LaunchedEffect's Main context, so these SnapshotStateMap
                    // writes are Main-confined — no marshalling, no number↔name flip. Resolve atomically:
                    // a name clears the failed flag; only mark failed when no name AND none already known.
                    if (name != null) { identifiedNames[dn] = name; identifyFailed.remove(dn) }
                    else if (!identifiedNames.containsKey(dn)) identifyFailed[dn] = true
                }
            }
        }
    }

    // Persist a resolved name onto a saved meter that still carries an auto label. Separate from the
    // identify loop so it can re-run on name/saved changes (including a just-added meter) WITHOUT
    // re-keying — and thus cancelling — the in-flight PCC connection above. Keyed on the resolved-name
    // COUNT (cheap) rather than a per-recomposition map copy.
    LaunchedEffect(saved, identifiedNames.size) {
        saved.forEach { m ->
            val name = identifiedNames[m.deviceNumber] ?: return@forEach
            if (!m.userNamed && isAutoMeterLabel(m.label, m.deviceNumber)) {
                updateAntMeters(ctx) { meters ->
                    meters.map {
                        if (it.deviceNumber == m.deviceNumber && !it.userNamed && isAutoMeterLabel(it.label, it.deviceNumber))
                            it.copy(label = name) else it
                    }
                }
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
                        // Real name (paired Karoo name → live PCC name). Fallback to the bare number ONLY
                        // once identify has finished without a name; while still in-flight, return null so
                        // the row shows "Identifying…".
                        karooDevices.karooNameForAnt(dn) ?: identifiedNames[dn]
                            ?: if (identifyFailed[dn] == true) "#$dn" else null
                    },
                    onToggleScan = {
                        if (scanning) { antManager.stopScan(); scanning = false }
                        else {
                            // Fresh scan: clear cached names + failed flags so a previously-asleep or
                            // since-renamed meter is re-identified (and the maps don't grow unbounded).
                            identifiedNames.clear()
                            identifyFailed.clear()
                            antManager.startScan(); scanning = true
                        }
                    },
                    onAdd = { dev ->
                        // Adding one meter stops the scan: the raw channel (for live battery/brand) and
                        // MultiDeviceSearch can't coexist, and you normally add one then look at it.
                        // Scan again to add another.
                        antManager.stopScan(); scanning = false
                        // Atomic transform off the CURRENT persisted list (not the stale `saved`
                        // snapshot), so a concurrent write can't be clobbered.
                        scope.launch {
                            updateAntMeters(ctx) { current ->
                                // All saved meters share slot 0: only one can be ACTIVE at a time, and
                                // that one drives the single field set + pm1_* FIT fields. A newly added
                                // meter is enabled ONLY if no meter is currently active, so adding to the
                                // garage never silently creates a second active meter.
                                if (current.size >= MAX_METERS || current.any { it.deviceNumber == dev.deviceNumber }) current
                                else current + SavedMeter(dev.deviceNumber, dev.name, slot = 0, enabled = current.none { it.enabled })
                            }
                        }
                        // Scan stopped → the off-scan identify effect now resolves this meter's name +
                        // battery via the PCC and persists them onto its label (no extra call needed).
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
                        // Calibration opens a BikePower PCC on the same ANT+ Plugins Service. Stop the scan
                        // and let the off-scan identify effect release its PCC — setting `calibrating`
                        // re-keys that effect and cancels it — before we request access, or two PCCs to
                        // the same device collide (observed: SEARCH_TIMEOUT / Unreachable).
                        if (scanning) { antManager.stopScan(); scanning = false }
                        calibrating = m
                        calibrationResult = null
                        calibrationRunning = true
                        scope.launch {
                            // finally guarantees the dialog never gets stuck on the spinner (Close
                            // disabled + dismiss blocked) if calibration throws.
                            try {
                                // Brief settle so an identify PCC that was mid-connect is fully released
                                // (the effect cancellation that frees it is async) before we request access.
                                delay(800)
                                calibrationResult = calibrateMeterViaPcc(ctx, m.deviceNumber)
                                    .also { Timber.d("PCC calibrate #%d result=%s", m.deviceNumber, it) }
                            } finally {
                                calibrationRunning = false
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

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

import kotlinx.coroutines.launch

import com.enderthor.kpower.R
import com.enderthor.kpower.ant.AntPowerManager
import com.enderthor.kpower.ant.SavedMeter
import com.enderthor.kpower.extension.FileLogTree
import com.enderthor.kpower.extension.antMetersFlow
import com.enderthor.kpower.extension.comparisonModeFlow
import com.enderthor.kpower.extension.diagnosticLogFlow
import com.enderthor.kpower.extension.updateAntMeters
import com.enderthor.kpower.extension.saveComparisonMode
import com.enderthor.kpower.extension.saveDiagnosticLog


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // AntPowerManager lifetime is tied to this composable.
    val antManager = remember { AntPowerManager(ctx) }
    DisposableEffect(Unit) {
        onDispose { antManager.disconnectAll() }
    }

    // Collect all state here (Composable context) so antScanItems (LazyListScope, not
    // @Composable) can receive plain values — no nested @Composable calls inside the
    // LazyListScope extension.
    val comparisonMode by ctx.comparisonModeFlow().collectAsState(initial = false)
    val diagnosticLog by ctx.diagnosticLogFlow().collectAsState(initial = false)
    val detected by antManager.detectedDevices.collectAsState()
    val saved by ctx.antMetersFlow().collectAsState(initial = emptyList())
    var scanning by remember { mutableStateOf(false) }
    // Meter currently being renamed (null = no dialog).
    var renaming by remember { mutableStateOf<SavedMeter?>(null) }

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
                    manager = antManager,
                    saved = saved,
                    detected = detected,
                    scanning = scanning,
                    onToggleScan = {
                        if (scanning) { antManager.stopScan(); scanning = false }
                        else { antManager.startScan(); scanning = true }
                    },
                    onAdd = { dev ->
                        // Atomic transform off the CURRENT persisted list (not the stale `saved`
                        // snapshot), so a concurrent write can't be clobbered.
                        scope.launch {
                            updateAntMeters(ctx) { current ->
                                val usedSlots = current.map { it.slot }.toSet()
                                val freeSlot = (0 until MAX_METERS).firstOrNull { it !in usedSlots }
                                if (current.size >= MAX_METERS ||
                                    current.any { it.deviceNumber == dev.deviceNumber } ||
                                    freeSlot == null
                                ) current
                                else current + SavedMeter(dev.deviceNumber, dev.name, freeSlot)
                            }
                        }
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
        }
    )
}

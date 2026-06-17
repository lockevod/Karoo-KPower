package com.enderthor.kpower.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.launch

import com.enderthor.kpower.ant.AntPowerManager
import com.enderthor.kpower.ant.SavedMeter
import com.enderthor.kpower.extension.antMetersFlow
import com.enderthor.kpower.extension.comparisonModeFlow
import com.enderthor.kpower.extension.recordDynamicsFlow
import com.enderthor.kpower.extension.saveAntMeters
import com.enderthor.kpower.extension.saveComparisonMode
import com.enderthor.kpower.extension.saveRecordDynamics


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
    val recordDynamics by ctx.recordDynamicsFlow().collectAsState(initial = false)
    val detected by antManager.detectedDevices.collectAsState()
    val saved by ctx.antMetersFlow().collectAsState(initial = emptyList())
    var scanning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Comparison") }) },
        content = { padding ->
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // First item: comparison-mode toggle card.
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
                                Text("Comparison mode")
                            }
                            Text(
                                text = "Exposes 4 estimated-power data fields and writes them to the FIT " +
                                    "(est_power, est_power_3s, est_np, est_avg). For comparing against a real " +
                                    "power meter. Off by default — increases battery/CPU and FIT size.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Second item: record-cycling-dynamics toggle card.
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
                                    checked = recordDynamics,
                                    onCheckedChange = { enabled ->
                                        scope.launch { saveRecordDynamics(ctx, enabled) }
                                    }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Record cycling dynamics")
                            }
                            Text(
                                text = "Reads advanced pedaling metrics (power phase, PCO, balance, " +
                                    "torque effectiveness…) from the recorded ANT+ meter and writes them " +
                                    "to the FIT. The Karoo does not record these. Needs a recorded meter.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Remaining items: ANT+ scan UI — same LazyColumn, no nested scroll.
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
                        if (saved.size >= MAX_METERS) return@antScanItems
                        if (saved.any { it.deviceNumber == dev.deviceNumber }) return@antScanItems
                        val usedSlots = saved.map { it.slot }.toSet()
                        val freeSlot = (0 until MAX_METERS).firstOrNull { it !in usedSlots }
                            ?: return@antScanItems
                        val next = saved + SavedMeter(dev.deviceNumber, dev.name, freeSlot)
                        scope.launch { saveAntMeters(ctx, next) }
                    },
                    onDelete = { m ->
                        val next = saved.filterNot { it.deviceNumber == m.deviceNumber }
                        scope.launch { saveAntMeters(ctx, next) }
                    },
                )
            }
        }
    )
}

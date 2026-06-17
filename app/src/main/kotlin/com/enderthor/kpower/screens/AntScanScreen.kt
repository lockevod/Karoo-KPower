package com.enderthor.kpower.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import com.enderthor.kpower.ant.AntDeviceInfo
import com.enderthor.kpower.ant.AntPowerManager
import com.enderthor.kpower.ant.SavedMeter
import com.enderthor.kpower.extension.antMetersFlow
import com.enderthor.kpower.extension.saveAntMeters
import kotlinx.coroutines.launch

private const val MAX_METERS = 2

@Composable
fun AntScanScreen(manager: AntPowerManager) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val detected by manager.detectedDevices.collectAsState()
    val saved by ctx.antMetersFlow().collectAsState(initial = emptyList())

    DisposableEffect(Unit) {
        manager.startScan()
        onDispose { manager.stopScan() }
    }

    val atCap = saved.size >= MAX_METERS
    // Only show detected devices that are not already saved.
    val available = detected.filterNot { dev -> saved.any { it.deviceNumber == dev.deviceNumber } }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("ANT+ power meters", style = MaterialTheme.typography.titleMedium)
        Text(
            "Record up to $MAX_METERS meters alongside the estimate.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn {
            // Section 1 — Recorded meters (the saved list).
            item {
                Text("Recorded meters", style = MaterialTheme.typography.titleSmall)
            }
            if (saved.isEmpty()) {
                item {
                    Text(
                        "No meters recorded yet — pick one below.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            } else {
                items(saved) { m: SavedMeter ->
                    Row(
                        Modifier.fillMaxWidth().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${m.label}  (#${m.deviceNumber})",
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                // Remove by deviceNumber; do NOT reindex slots so the
                                // remaining meters keep their stable slot assignment.
                                val next = saved.filterNot { it.deviceNumber == m.deviceNumber }
                                scope.launch { saveAntMeters(ctx, next) }
                            },
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }

            // Section 2 — Available (scanning…), detected devices not already saved.
            item {
                Text("Available (scanning…)", style = MaterialTheme.typography.titleSmall)
            }
            if (atCap) {
                item {
                    Text(
                        "Maximum $MAX_METERS meters",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            } else if (available.isEmpty()) {
                item {
                    Text(
                        "Scanning…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            } else {
                items(available) { dev: AntDeviceInfo ->
                    Row(
                        Modifier.fillMaxWidth().padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${dev.name}  (#${dev.deviceNumber})",
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = !atCap,
                            onClick = {
                                // Ignore if already at the cap; assign the lowest free slot.
                                if (saved.size >= MAX_METERS) return@TextButton
                                if (saved.any { it.deviceNumber == dev.deviceNumber }) return@TextButton
                                val usedSlots = saved.map { it.slot }.toSet()
                                val freeSlot = (0 until MAX_METERS).firstOrNull { it !in usedSlots }
                                    ?: return@TextButton
                                val next = saved + SavedMeter(dev.deviceNumber, dev.name, freeSlot)
                                scope.launch { saveAntMeters(ctx, next) }
                            },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                }
            }
        }
    }
}

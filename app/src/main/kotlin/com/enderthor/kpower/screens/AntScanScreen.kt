package com.enderthor.kpower.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enderthor.kpower.ant.AntDeviceInfo
import com.enderthor.kpower.ant.AntPowerManager
import com.enderthor.kpower.ant.SavedMeter

const val MAX_METERS = 1

/**
 * Adds all ANT+ scan UI items into the caller's [LazyListScope].
 * No nested LazyColumn — all state is collected by the caller (@Composable context)
 * and passed in as plain values.
 *
 * [manager]     The AntPowerManager created + disposed by ComparisonScreen.
 * [saved]       Snapshot of persisted meters.
 * [detected]    Snapshot of currently-detected devices from the scan.
 * [scanning]    Whether a scan is currently running.
 * [onToggleScan] Called when the Scan/Stop button is tapped.
 * [onAdd]       Called when the user taps Add on a detected device.
 * [onDelete]    Called when the user taps Delete on a saved meter.
 */
fun LazyListScope.antScanItems(
    manager: AntPowerManager,
    saved: List<SavedMeter>,
    detected: List<AntDeviceInfo>,
    scanning: Boolean,
    onToggleScan: () -> Unit,
    onAdd: (AntDeviceInfo) -> Unit,
    onDelete: (SavedMeter) -> Unit,
) {
    val atCap = saved.size >= MAX_METERS
    val available = detected.filterNot { dev -> saved.any { it.deviceNumber == dev.deviceNumber } }

    item {
        Spacer(Modifier.height(8.dp))
        Text(
            "ANT+ power meters",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    item {
        Text(
            "Record up to $MAX_METERS meters alongside the estimate.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
    }

    // Section 1 — Recorded meters (the saved list).
    item {
        Text(
            "Recorded meters",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (saved.isEmpty()) {
        item {
            Text(
                "No meters recorded yet — pick one below.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    } else {
        items(saved) { m: SavedMeter ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${m.label}  (#${m.deviceNumber})",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onDelete(m) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove")
                }
            }
        }
    }

    item { Spacer(Modifier.height(12.dp)) }

    // Section 2 — Available, detected devices not already saved (manual scan).
    item {
        Text(
            "Available",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    item {
        Button(
            onClick = onToggleScan,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(if (scanning) "Stop" else "Scan")
        }
    }
    item {
        Text(
            "Don't scan while recording a ride — it can disrupt the meter you're recording.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (atCap) {
        item {
            Text(
                "Maximum $MAX_METERS meters",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    } else if (available.isEmpty()) {
        item {
            Text(
                if (scanning) "Scanning…" else "Tap Scan to find meters.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    } else {
        items(available) { dev: AntDeviceInfo ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${dev.name}  (#${dev.deviceNumber})",
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !atCap,
                    onClick = { onAdd(dev) },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }
    }
}

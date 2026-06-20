package com.enderthor.kpower.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.enderthor.kpower.R
import com.enderthor.kpower.ant.AntDeviceInfo
import com.enderthor.kpower.ant.AntPowerManager
import com.enderthor.kpower.ant.BatteryLevel
import com.enderthor.kpower.ant.SavedMeter
import com.enderthor.kpower.ant.batteryLevelOf

// Several meters may be SAVED (a garage to switch between), but only ONE may be ACTIVE (enabled) at a
// time — the active one drives the single set of real-power/dynamics fields and the pm1_* FIT fields.
const val MAX_METERS = 5

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
 * [onToggleEnabled] Called when the per-meter Record switch is toggled.
 * [onRename]    Called when the user taps the rename (edit) icon on a saved meter.
 */
fun LazyListScope.antScanItems(
    manager: AntPowerManager,
    saved: List<SavedMeter>,
    detected: List<AntDeviceInfo>,
    scanning: Boolean,
    onToggleScan: () -> Unit,
    onAdd: (AntDeviceInfo) -> Unit,
    onDelete: (SavedMeter) -> Unit,
    onToggleEnabled: (SavedMeter, Boolean) -> Unit,
    onRename: (SavedMeter) -> Unit,
) {
    val atCap = saved.size >= MAX_METERS
    val available = detected.filterNot { dev -> saved.any { it.deviceNumber == dev.deviceNumber } }

    item {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ant_meters_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    item {
        Text(
            stringResource(R.string.ant_meters_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
    }

    // Section 1 — Recorded meters (the saved list).
    item {
        Text(
            stringResource(R.string.ant_recorded_meters),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    item {
        Text(
            stringResource(R.string.meter_double_pair_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    if (saved.isEmpty()) {
        item {
            Text(
                stringResource(R.string.ant_no_meters),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    } else {
        items(saved) { m: SavedMeter ->
            // Battery + brand come from the raw 0x52/0x50 pages, which only arrive while the meter's
            // channel is open — ComparisonScreen acquires it for saved meters while this screen shows.
            val battery by manager.batteryFlow(m.deviceNumber).collectAsState()
            val brand by manager.manufacturerFlow(m.deviceNumber).collectAsState()
            var expanded by remember(m.deviceNumber) { mutableStateOf(false) }

            Column {
                // Main row: record switch · name/status · battery (always visible) · expand chevron.
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = m.enabled,
                        onCheckedChange = { newValue -> onToggleEnabled(m, newValue) },
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "#${m.deviceNumber} · " + stringResource(
                                if (m.enabled) R.string.meter_status_recording else R.string.meter_status_off
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BatteryIcon(battery)
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.meter_details),
                        )
                    }
                }

                // Detail panel (the extensible "actions" area). Brand + rename/remove today; Calibrate
                // and other per-meter actions land here later.
                if (expanded) {
                    Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, bottom = 8.dp)) {
                        Text(
                            stringResource(R.string.meter_brand) + ": " +
                                (brand ?: stringResource(R.string.meter_connecting)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onRename(m) }) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.ant_rename))
                            }
                            TextButton(onClick = { onDelete(m) }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.ant_remove))
                            }
                        }
                        Text(
                            stringResource(R.string.meter_calibration_soon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // Divider between saved meters and the (always-visible) scan section.
    item {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
        Spacer(Modifier.height(12.dp))
    }

    // Section 2 — Available, detected devices not already saved (manual scan).
    item {
        Text(
            stringResource(R.string.ant_available),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    item {
        Button(
            onClick = onToggleScan,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(if (scanning) stringResource(R.string.ant_stop) else stringResource(R.string.ant_scan))
        }
    }
    item {
        Text(
            stringResource(R.string.ant_scan_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
    if (atCap) {
        item {
            Text(
                stringResource(R.string.ant_max_meters, MAX_METERS),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    } else if (available.isEmpty()) {
        item {
            Text(
                if (scanning) stringResource(R.string.ant_scanning) else stringResource(R.string.ant_tap_scan),
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
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ant_add))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.ant_add))
                }
            }
        }
    }
}

/** Battery state from the ANT+ 0x52 code as a tinted icon (no %, see [batteryLevelOf]).
 *  OK=green-full, low=amber-full, critical=red-alert, unknown=grey "?" until the page arrives. */
@Composable
private fun BatteryIcon(code: Int?, modifier: Modifier = Modifier) {
    val (drawable, colorRes, descRes) = when (batteryLevelOf(code)) {
        BatteryLevel.OK -> Triple(R.drawable.ic_battery_full, R.color.battery_ok, R.string.battery_ok)
        BatteryLevel.LOW -> Triple(R.drawable.ic_battery_full, R.color.battery_low, R.string.battery_low)
        BatteryLevel.CRITICAL -> Triple(R.drawable.ic_battery_alert, R.color.battery_critical, R.string.battery_critical)
        BatteryLevel.UNKNOWN -> Triple(R.drawable.ic_battery_unknown, R.color.battery_unknown, R.string.battery_unknown)
    }
    Icon(
        painter = painterResource(drawable),
        contentDescription = stringResource(descRes),
        tint = colorResource(colorRes),
        modifier = modifier.size(24.dp),
    )
}

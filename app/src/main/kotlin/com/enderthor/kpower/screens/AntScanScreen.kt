package com.enderthor.kpower.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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

private const val MAX_METERS = 3

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

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text("ANT+ power meters", style = MaterialTheme.typography.titleMedium)
        Text(
            "Select up to $MAX_METERS meters to record alongside the estimate.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn {
            items(detected) { dev: AntDeviceInfo ->
                val isSaved = saved.any { it.deviceNumber == dev.deviceNumber }
                Row(
                    Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isSaved,
                        onCheckedChange = { checked ->
                            val next = if (checked) {
                                if (isSaved) saved
                                else {
                                    val usedSlots = saved.map { it.slot }.toSet()
                                    val freeSlot = (0 until MAX_METERS).firstOrNull { it !in usedSlots }
                                    if (freeSlot == null) saved  // already at cap
                                    else saved + SavedMeter(dev.deviceNumber, dev.name, freeSlot)
                                }
                            } else {
                                saved.filterNot { it.deviceNumber == dev.deviceNumber }
                            }
                            scope.launch { saveAntMeters(ctx, next) }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${dev.name}  (#${dev.deviceNumber})")
                }
            }
            if (detected.isEmpty()) item { Text("Scanning…") }
        }
    }
}

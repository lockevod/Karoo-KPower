package com.enderthor.kpower.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.launch

import com.enderthor.kpower.ant.AntPowerManager
import com.enderthor.kpower.extension.comparisonModeFlow
import com.enderthor.kpower.extension.saveComparisonMode


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComparisonScreen() {
    val ctx = LocalContext.current
    val antManager = remember { AntPowerManager(ctx) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Comparison / Real meters") }) },
        content = { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                val cmScope = rememberCoroutineScope()
                val comparisonMode by ctx.comparisonModeFlow().collectAsState(initial = false)

                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(5.dp),
                    shape = RoundedCornerShape(corner = CornerSize(10.dp))
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = comparisonMode,
                                onCheckedChange = { enabled ->
                                    cmScope.launch { saveComparisonMode(ctx, enabled) }
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

                AntScanScreen(antManager)
            }
        }
    )
}

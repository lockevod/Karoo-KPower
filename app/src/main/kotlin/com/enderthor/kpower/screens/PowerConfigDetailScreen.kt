package com.enderthor.kpower.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.enderthor.kpower.data.BikePosition
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.TreadType
import com.enderthor.kpower.extension.consumerFlow
import com.enderthor.kpower.extension.toDoubleLocale
import com.enderthor.kpower.vdevice.estimateCrr
import com.enderthor.kpower.vdevice.estimateFrontalArea
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(isCreating: Boolean, configdata: ConfigData, onSubmit: (updatedConfigData: ConfigData?) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val karooSystem = remember { KarooSystemService(ctx) }
    LaunchedEffect(Unit) {
        karooSystem.connect {}
    }

    var title by remember { mutableStateOf(configdata.name) }
    var bikeMass by remember { mutableStateOf(configdata.bikeMass) }
    var rollingResistanceCoefficient by remember { mutableStateOf(configdata.rollingResistanceCoefficient) }
    var dragCoefficient by remember { mutableStateOf(configdata.dragCoefficient) }
    var isActive by remember { mutableStateOf(configdata.isActive) }
    var powerLoss by remember { mutableStateOf(configdata.powerLoss) }
    var frontalArea by remember { mutableStateOf(configdata.frontalArea) }
    var headwind by remember { mutableStateOf(configdata.headwindconf) }
    var apikey by remember { mutableStateOf(configdata.apikey) }
    var isOpenWeather by remember { mutableStateOf(configdata.isOpenWeather) }
    var ftp by remember { mutableStateOf(configdata.ftp) }
    var surface by remember { mutableStateOf(configdata.surface) }
    var isforcepower by remember { mutableStateOf(configdata.isforcepower) }

    var bikePosition by remember { mutableStateOf(configdata.bikePosition) }
    var riderHeight by remember { mutableStateOf(configdata.riderHeight) }
    var tyreWidth by remember { mutableStateOf(configdata.tyreWidth) }
    var tyrePressure by remember { mutableStateOf(configdata.tyrePressure) }
    var treadType by remember { mutableStateOf(configdata.treadType) }
    var useProfileFtp by remember { mutableStateOf(configdata.useProfileFtp) }
    var simpleMode by remember { mutableStateOf(configdata.simpleMode) }
    var useKarooTemp by remember { mutableStateOf(configdata.useKarooTemp) }

    var riderWeightKg by remember { mutableStateOf(0.0) }

    fun recomputeCrr() {
        val w = tyreWidth.toDoubleLocale()
        val p = tyrePressure.toDoubleLocale()
        if (w > 0 && p > 0) {
            rollingResistanceCoefficient = String.format(java.util.Locale.US, "%.4f", estimateCrr(w, p, treadType))
        }
    }

    fun recomputeArea() {
        val h = riderHeight.toDoubleLocale()
        if (h > 0 && riderWeightKg > 0) {
            frontalArea = String.format(java.util.Locale.US, "%.3f", estimateFrontalArea(h, riderWeightKg, bikePosition))
        }
    }

    fun applyPreset(p: BikePosition) {
        bikePosition = p
        dragCoefficient = String.format(java.util.Locale.US, "%.2f", p.cd)
        surface = p.defaultSurface
        tyreWidth = p.defaultTyreWidth
        tyrePressure = p.defaultTyrePressure
        treadType = p.defaultTread
        recomputeCrr()
        recomputeArea()
    }

    // El peso del ciclista llega del perfil de Karoo de forma asíncrona. Cuando llega,
    // recalcula el área frontal (es no-op si la altura está vacía, p.ej. modo Avanzado),
    // para no dejar el área sin calcular si el usuario tecleó la altura antes.
    LaunchedEffect(Unit) {
        karooSystem.consumerFlow<UserProfile>().collect {
            riderWeightKg = it.weight.toDouble()
            recomputeArea()
        }
    }

    fun getUpdatedConfigData(): ConfigData = ConfigData(
        configdata.id, title, isActive, bikeMass, rollingResistanceCoefficient, dragCoefficient,
        frontalArea, powerLoss, headwind, isOpenWeather, apikey, ftp, surface, isforcepower,
        bikePosition, riderHeight, tyreWidth, tyrePressure, treadType, useProfileFtp, simpleMode, useKarooTemp
    )

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {
        TopAppBar(title = { Text(if (isCreating) "Create Power Config" else "Edit Power Config") })
        Column(modifier = Modifier
            .padding(5.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            // Campos "de entrada" que DERIVAN Crr/Cd/área (preset, altura, neumático):
            // solo en modo Simple. En Avanzado se editan los valores manuales directamente
            // (abajo, bajo `if (!simpleMode)`), sin que nada los recalcule.
            if (simpleMode) {
                apply {
                    val positionOptions = BikePosition.entries.toList().map { DropdownOption(it.name, it.name) }
                    val selected by remember(bikePosition) {
                        mutableStateOf(positionOptions.first { it.id == bikePosition.name })
                    }
                    KarooKeyDropdown(remotekey = "Position", options = positionOptions, selectedOption = selected) { opt ->
                        applyPreset(BikePosition.valueOf(opt.id))
                    }
                }

                OutlinedTextField(value = riderHeight, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { riderHeight = it; recomputeArea() },
                    label = { Text("Rider height") }, suffix = { Text("cm") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )

                OutlinedTextField(value = tyreWidth, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { tyreWidth = it; recomputeCrr() },
                    label = { Text("Tyre width") }, suffix = { Text("mm") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )

                OutlinedTextField(value = tyrePressure, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { tyrePressure = it; recomputeCrr() },
                    label = { Text("Tyre pressure") }, suffix = { Text("bar") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )

                apply {
                    val treadOptions = TreadType.entries.toList().map { DropdownOption(it.name, it.name) }
                    val selectedTread by remember(treadType) {
                        mutableStateOf(treadOptions.first { it.id == treadType.name })
                    }
                    KarooKeyDropdown(remotekey = "Tread", options = treadOptions, selectedOption = selectedTread) { opt ->
                        treadType = TreadType.valueOf(opt.id); recomputeCrr()
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useProfileFtp, onCheckedChange = { useProfileFtp = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text("Use FTP from Karoo profile?")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = simpleMode, onCheckedChange = { simpleMode = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text("Simple mode (hide advanced)")
            }

            OutlinedTextField(value = bikeMass, modifier = Modifier.fillMaxWidth(),
                onValueChange = { bikeMass = it },
                label = { Text("Bike Mass") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            apply {
                val dropdownOptions = KarooSurface.entries.toList()
                    .map { unit -> DropdownOption(unit.factor.toString(), unit.surface) }
                val dropdownInitialSelection by remember(surface) {
                    mutableStateOf(dropdownOptions.find { option -> option.id == surface.factor.toString() }!!)
                }
                KarooKeyDropdown(
                    remotekey = "Surface", options = dropdownOptions, selectedOption = dropdownInitialSelection
                ) { selectedOption ->
                    surface =
                        KarooSurface.entries.find { unit -> unit.factor.toString() == selectedOption.id }!!
                }
            }

            if (!simpleMode) {
                OutlinedTextField(value = rollingResistanceCoefficient, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { rollingResistanceCoefficient = it },
                    label = { Text("Crr") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                OutlinedTextField(value = dragCoefficient, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { dragCoefficient = it },
                    label = { Text("Cdr") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                OutlinedTextField(value = frontalArea, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { frontalArea = it },
                    label = { Text("Frontal Area") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    suffix = { Text("m2") },
                )

                OutlinedTextField(value = powerLoss, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { powerLoss = it },
                    label = { Text("Power Loss") },
                    suffix = { Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useKarooTemp, onCheckedChange = { useKarooTemp = it })
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Use Karoo temperature sensor as fallback?")
                }
            }

            OutlinedTextField(value = ftp, modifier = Modifier.fillMaxWidth(),
                onValueChange = { ftp = it },
                label = { Text("FTP") },
                suffix = { Text("W") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            OutlinedTextField(value = apikey.toString(), modifier = Modifier.fillMaxWidth(),
                onValueChange = { apikey = it },
                label = { Text("API OpenWeather") },
                singleLine = true,
                enabled = isOpenWeather
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isOpenWeather, onCheckedChange = {
                    isOpenWeather = it
                   // if (it) isActive = false
                })
                Spacer(modifier = Modifier.width(10.dp))
                Text("OpenMeteo or OpenWeather(checked)?")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = isforcepower, onCheckedChange = {
                    isforcepower = it
                })
                Spacer(modifier = Modifier.width(10.dp))
                Text("Ignore low cadence (always calculate power)?")
            }

            FilledTonalButton(modifier = Modifier
                .fillMaxWidth()
                .height(50.dp), onClick = {
                onSubmit(getUpdatedConfigData())
            }) {
                Icon(Icons.Default.Done, contentDescription = "Save Power Config")
                Spacer(modifier = Modifier.width(5.dp))
                Text("Save")
            }

            FilledTonalButton(modifier = Modifier
                .fillMaxWidth()
                .height(50.dp), onClick = {
                onCancel()
            }) {
                Icon(Icons.Default.Close, contentDescription = "Cancel Editing")
                Spacer(modifier = Modifier.width(5.dp))
                Text("Cancel")
            }
        }
    }
}

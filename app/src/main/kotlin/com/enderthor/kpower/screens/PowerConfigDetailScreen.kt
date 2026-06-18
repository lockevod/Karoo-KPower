package com.enderthor.kpower.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.enderthor.kpower.R
import com.enderthor.kpower.data.BikePosition
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.TreadType
import com.enderthor.kpower.extension.antMetersFlow
import com.enderthor.kpower.extension.consumerFlow
import com.enderthor.kpower.extension.isHeadwindInstalled
import com.enderthor.kpower.extension.knownProfilesFlow
import com.enderthor.kpower.extension.toDoubleLocale
import com.enderthor.kpower.vdevice.estimateCrr
import com.enderthor.kpower.vdevice.estimateFrontalArea
import com.enderthor.kpower.vdevice.tyreWidthToMm
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
    var tubeless by remember { mutableStateOf(configdata.tubeless) }
    var preferHeadwind by remember { mutableStateOf(configdata.preferHeadwind) }
    var useRouteSurface by remember { mutableStateOf(configdata.useRouteSurface) }
    var karooProfileId by remember { mutableStateOf(configdata.karooProfileId) }
    val headwindInstalled = remember { ctx.isHeadwindInstalled() }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val detailCtx = androidx.compose.ui.platform.LocalContext.current
    val knownProfiles by detailCtx.knownProfilesFlow().collectAsState(initial = emptyList())

    var riderWeightKg by remember { mutableStateOf(0.0) }
    var riderFtp by remember { mutableStateOf(0) }

    fun recomputeCrr() {
        val w = tyreWidth.toDoubleLocale()
        val p = tyrePressure.toDoubleLocale()
        if (w > 0 && p > 0) {
            rollingResistanceCoefficient = String.format(java.util.Locale.US, "%.4f", estimateCrr(tyreWidthToMm(w), p, treadType, tubeless))
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
            riderFtp = it.ftp
            recomputeArea()
        }
    }

    fun getUpdatedConfigData(): ConfigData {
        // The OpenWeather provider control is hidden in Simple mode and when Headwind is the active
        // weather source. Don't persist a stranded isOpenWeather=true the rider can no longer see or
        // turn off (it would keep hitting OpenWeather with an uneditable key); fall back to OpenMeteo.
        val openWeatherVisible = !simpleMode && !(preferHeadwind && headwindInstalled)
        val effectiveOpenWeather = isOpenWeather && openWeatherVisible
        return ConfigData(
        configdata.id, title, isActive, bikeMass, rollingResistanceCoefficient, dragCoefficient,
        frontalArea, powerLoss, headwind, effectiveOpenWeather, apikey, ftp, surface, isforcepower,
        bikePosition, riderHeight, tyreWidth, tyrePressure, treadType, useProfileFtp, simpleMode, useKarooTemp, tubeless,
        preferHeadwind, useRouteSurface,
        karooProfileId = karooProfileId,
        )
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {
        TopAppBar(title = { Text(if (isCreating) stringResource(R.string.cfg_title_create) else stringResource(R.string.cfg_title_edit)) })
        Column(modifier = Modifier
            .padding(5.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.section_bike), style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.cfg_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            apply {
                val profileOptions = listOf(DropdownOption("", stringResource(R.string.dropdown_none))) +
                    knownProfiles.map { DropdownOption(it.id, it.name) }
                val selectedProfile by remember(karooProfileId, knownProfiles) {
                    mutableStateOf(profileOptions.find { it.id == (karooProfileId ?: "") } ?: profileOptions.first())
                }
                KarooKeyDropdown(remotekey = stringResource(R.string.dropdown_link_profile), options = profileOptions, selectedOption = selectedProfile) { opt ->
                    karooProfileId = if (opt.id.isEmpty()) null else opt.id
                }
            }

            Text(
                text = stringResource(R.string.profile_power_source_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Always shown (not only when empty): KPower learns profiles via the Karoo's
            // ride-profile stream, so a profile only appears here after it's been opened/ridden once.
            Text(
                text = stringResource(R.string.no_profiles_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.profile_link_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Campos "de entrada" que DERIVAN Crr/Cd/área (preset, altura, neumático):
            // solo en modo Simple. En Avanzado se editan los valores manuales directamente
            // (abajo, bajo `if (!simpleMode)`), sin que nada los recalcule.
            if (simpleMode) {
                apply {
                    val positionOptions = BikePosition.entries.toList().map { DropdownOption(it.name, it.label) }
                    val selected by remember(bikePosition) {
                        mutableStateOf(positionOptions.first { it.id == bikePosition.name })
                    }
                    KarooKeyDropdown(remotekey = stringResource(R.string.dropdown_position), options = positionOptions, selectedOption = selected) { opt ->
                        applyPreset(BikePosition.valueOf(opt.id))
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = simpleMode, onCheckedChange = { simpleMode = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.simple_mode_label))
            }

            OutlinedTextField(value = bikeMass, modifier = Modifier.fillMaxWidth(),
                onValueChange = { bikeMass = it },
                label = { Text(stringResource(R.string.cfg_bike_mass)) }, suffix = { Text("kg") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Text(stringResource(R.string.section_tyres_surface), style = MaterialTheme.typography.titleSmall)

            if (simpleMode) {
                OutlinedTextField(value = riderHeight, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { riderHeight = it; recomputeArea() },
                    label = { Text(stringResource(R.string.cfg_rider_height)) }, suffix = { Text("cm") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )

                OutlinedTextField(value = tyreWidth, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { tyreWidth = it; recomputeCrr() },
                    label = { Text(stringResource(R.string.cfg_tyre_width)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )

                OutlinedTextField(value = tyrePressure, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { tyrePressure = it; recomputeCrr() },
                    label = { Text(stringResource(R.string.cfg_tyre_pressure)) }, suffix = { Text("bar") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )

                Text(
                    text = stringResource(R.string.rear_tyre_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                apply {
                    val treadOptions = TreadType.entries.toList().map { DropdownOption(it.name, it.label) }
                    val selectedTread by remember(treadType) {
                        mutableStateOf(treadOptions.first { it.id == treadType.name })
                    }
                    KarooKeyDropdown(remotekey = stringResource(R.string.dropdown_tread), options = treadOptions, selectedOption = selectedTread) { opt ->
                        treadType = TreadType.valueOf(opt.id); recomputeCrr()
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = tubeless, onCheckedChange = { tubeless = it; recomputeCrr() })
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.tubeless_label))
                }
            }

            apply {
                val dropdownOptions = KarooSurface.entries.toList()
                    .map { unit -> DropdownOption(unit.factor.toString(), unit.surface) }
                val dropdownInitialSelection by remember(surface) {
                    mutableStateOf(dropdownOptions.find { option -> option.id == surface.factor.toString() }!!)
                }
                KarooKeyDropdown(
                    remotekey = if (useRouteSurface) stringResource(R.string.dropdown_default_surface) else stringResource(R.string.dropdown_surface),
                    options = dropdownOptions, selectedOption = dropdownInitialSelection
                ) { selectedOption ->
                    surface =
                        KarooSurface.entries.find { unit -> unit.factor.toString() == selectedOption.id }!!
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useRouteSurface, onCheckedChange = { useRouteSurface = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.auto_surface_label))
            }

            if (useRouteSurface) {
                Text(
                    text = stringResource(R.string.auto_surface_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(stringResource(R.string.section_rider_ftp), style = MaterialTheme.typography.titleSmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useProfileFtp, onCheckedChange = { useProfileFtp = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.use_profile_ftp_label))
            }

            OutlinedTextField(
                value = if (useProfileFtp && riderFtp > 0) riderFtp.toString() else ftp,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { if (!useProfileFtp) ftp = it },
                label = { Text(if (useProfileFtp) stringResource(R.string.cfg_ftp_from_profile) else stringResource(R.string.cfg_ftp)) },
                suffix = { Text("W") },
                enabled = !useProfileFtp,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            Text(stringResource(R.string.section_weather), style = MaterialTheme.typography.titleSmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = preferHeadwind, onCheckedChange = { preferHeadwind = it })
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.use_headwind_label))
            }

            if (preferHeadwind) {
                Text(
                    text = if (headwindInstalled)
                        stringResource(R.string.headwind_detected_hint)
                    else
                        stringResource(R.string.headwind_not_installed_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Weather-provider switch + API key are expert knobs hidden in Simple mode, and
            // are also hidden when Headwind is installed and selected (it supplies weather).
            if (!simpleMode && !(preferHeadwind && headwindInstalled)) {
                Text(stringResource(R.string.weather_provider_label), style = MaterialTheme.typography.bodySmall)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isOpenWeather, onCheckedChange = {
                        isOpenWeather = it
                       // if (it) isActive = false
                    })
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.weather_provider_switch))
                }

                OutlinedTextField(value = apikey.toString(), modifier = Modifier.fillMaxWidth(),
                    onValueChange = { apikey = it },
                    label = { Text(stringResource(R.string.cfg_api_openweather)) },
                    singleLine = true,
                    enabled = isOpenWeather
                )
            }

            if (!simpleMode) {
                Text(stringResource(R.string.section_advanced), style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(value = rollingResistanceCoefficient, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { rollingResistanceCoefficient = it },
                    label = { Text("Crr") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                OutlinedTextField(value = dragCoefficient, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { dragCoefficient = it },
                    label = { Text("Cd (aero)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                OutlinedTextField(value = frontalArea, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { frontalArea = it },
                    label = { Text(stringResource(R.string.cfg_frontal_area)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    suffix = { Text("m2") },
                )

                OutlinedTextField(value = powerLoss, modifier = Modifier.fillMaxWidth(),
                    onValueChange = { powerLoss = it },
                    label = { Text(stringResource(R.string.cfg_power_loss)) },
                    suffix = { Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useKarooTemp, onCheckedChange = { useKarooTemp = it })
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.use_karoo_temp_label))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isforcepower, onCheckedChange = {
                        isforcepower = it
                    })
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.ignore_low_cadence_label))
                }
            }

            FilledTonalButton(modifier = Modifier
                .fillMaxWidth()
                .height(50.dp), onClick = {
                onSubmit(getUpdatedConfigData())
            }) {
                Icon(Icons.Default.Done, contentDescription = stringResource(R.string.btn_save))
                Spacer(modifier = Modifier.width(5.dp))
                Text(stringResource(R.string.btn_save))
            }

            FilledTonalButton(modifier = Modifier
                .fillMaxWidth()
                .height(50.dp), onClick = {
                onCancel()
            }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.btn_cancel))
                Spacer(modifier = Modifier.width(5.dp))
                Text(stringResource(R.string.btn_cancel))
            }

            // Borrar bici: solo al editar una existente. onSubmit(null) lo interpreta la ruta
            // configData/{id} como "elimina esta config" (no aparece al crear una nueva).
            if (!isCreating) {
                FilledTonalButton(modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.btn_delete_bike))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(stringResource(R.string.btn_delete_bike))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_bike_confirm_title)) },
            text = { Text(stringResource(R.string.delete_bike_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onSubmit(null)
                }) {
                    Text(stringResource(R.string.btn_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

package com.enderthor.kpower.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.enderthor.kpower.R
import com.enderthor.kpower.data.BikePosition
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.bikeDotColors
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.TreadType
import com.enderthor.kpower.data.HeadwindWindUnit
import com.enderthor.kpower.extension.antMetersFlow
import com.enderthor.kpower.extension.consumerFlow
import com.enderthor.kpower.extension.isHeadwindInstalled
import com.enderthor.kpower.extension.knownProfilesFlow
import com.enderthor.kpower.extension.signedIntFilter
import com.enderthor.kpower.extension.toDoubleLocale
import com.enderthor.kpower.vdevice.estimateCrr
import com.enderthor.kpower.vdevice.estimateFrontalArea
import com.enderthor.kpower.vdevice.tyreWidthToMm
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(configdata: ConfigData, onUpdate: (ConfigData) -> Unit, onDelete: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val karooSystem = remember { KarooSystemService(ctx) }
    DisposableEffect(Unit) {
        karooSystem.connect {}
        onDispose { karooSystem.disconnect() }
    }

    var title by remember(configdata.id) { mutableStateOf(configdata.name) }
    var bikeMass by remember(configdata.id) { mutableStateOf(configdata.bikeMass) }
    var rollingResistanceCoefficient by remember(configdata.id) { mutableStateOf(configdata.rollingResistanceCoefficient) }
    var dragCoefficient by remember(configdata.id) { mutableStateOf(configdata.dragCoefficient) }
    var isActive by remember(configdata.id) { mutableStateOf(configdata.isActive) }
    var powerLoss by remember(configdata.id) { mutableStateOf(configdata.powerLoss) }
    var frontalArea by remember(configdata.id) { mutableStateOf(configdata.frontalArea) }
    var headwind by remember(configdata.id) { mutableStateOf(configdata.headwindconf) }
    var ftp by remember(configdata.id) { mutableStateOf(configdata.ftp) }
    var surface by remember(configdata.id) { mutableStateOf(configdata.surface) }
    var isforcepower by remember(configdata.id) { mutableStateOf(configdata.isforcepower) }

    var bikePosition by remember(configdata.id) { mutableStateOf(configdata.bikePosition) }
    var riderHeight by remember(configdata.id) { mutableStateOf(configdata.riderHeight) }
    var tyreWidth by remember(configdata.id) { mutableStateOf(configdata.tyreWidth) }
    var tyrePressure by remember(configdata.id) { mutableStateOf(configdata.tyrePressure) }
    var treadType by remember(configdata.id) { mutableStateOf(configdata.treadType) }
    var useProfileFtp by remember(configdata.id) { mutableStateOf(configdata.useProfileFtp) }
    var simpleMode by remember(configdata.id) { mutableStateOf(configdata.simpleMode) }
    var useKarooTemp by remember(configdata.id) { mutableStateOf(configdata.useKarooTemp) }
    var tubeless by remember(configdata.id) { mutableStateOf(configdata.tubeless) }
    var preferHeadwind by remember(configdata.id) { mutableStateOf(configdata.preferHeadwind) }
    var headwindWindUnit by remember(configdata.id) { mutableStateOf(configdata.headwindWindUnit) }
    var useRouteSurface by remember(configdata.id) { mutableStateOf(configdata.useRouteSurface) }
    var karooProfileId by remember(configdata.id) { mutableStateOf(configdata.karooProfileId) }
    var dotColor by remember(configdata.id) { mutableStateOf(configdata.dotColorArgb) }
    var estPowerFactorPct by remember(configdata.id) { mutableStateOf(configdata.estPowerFactorPct) }
    var estPowerOffsetW by remember(configdata.id) { mutableStateOf(configdata.estPowerOffsetW) }
    val headwindInstalled = remember { ctx.isHeadwindInstalled() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val deleted = remember(configdata.id) { mutableStateOf(false) }

    val detailCtx = androidx.compose.ui.platform.LocalContext.current
    val knownProfiles by detailCtx.knownProfilesFlow().collectAsState(initial = emptyList())

    var riderWeightKg by remember { mutableStateOf(0.0) }
    var riderFtp by remember { mutableStateOf(0) }

    fun recomputeCrr() {
        val w = tyreWidth.toDoubleLocale()
        val p = tyrePressure.toDoubleLocale()
        if (w > 0 && p > 0) {
            // System mass (rider + bike) feeds the load-dependent reference pressure; fall back to ~85 kg
            // total when the rider weight isn't known yet (no Karoo profile).
            val riderKg = riderWeightKg.takeIf { it > 0.0 } ?: 75.0
            val bikeKg = bikeMass.toDoubleLocale().takeIf { it > 0.0 } ?: 10.0
            rollingResistanceCoefficient = String.format(java.util.Locale.US, "%.4f", estimateCrr(tyreWidthToMm(w), p, treadType, tubeless, riderKg + bikeKg))
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
            // Simple mode only: re-derive area AND Crr now that the rider weight is known (the Crr
            // reference pressure is load-dependent, so without this the load term stayed inert until a
            // tyre field was touched). Gated on simpleMode so it never clobbers advanced/calibrated values.
            if (simpleMode) { recomputeArea(); recomputeCrr() }
        }
    }

    fun getUpdatedConfigData(): ConfigData {
        return ConfigData(
        configdata.id, title, isActive, bikeMass, rollingResistanceCoefficient, dragCoefficient,
        frontalArea, powerLoss, headwind, ftp, surface, isforcepower,
        bikePosition, riderHeight, tyreWidth, tyrePressure, treadType, useProfileFtp, simpleMode, useKarooTemp, tubeless,
        preferHeadwind, useRouteSurface,
        karooProfileId = karooProfileId,
        dotColorArgb = dotColor,
        headwindWindUnit = headwindWindUnit,
        estPowerFactorPct = estPowerFactorPct,
        estPowerOffsetW = estPowerOffsetW,
        )
    }

    val latestConfig = rememberUpdatedState(getUpdatedConfigData())
    DisposableEffect(configdata.id) {
        onDispose {
            if (!deleted.value) onUpdate(latestConfig.value)
        }
    }
    BackHandler {
        onUpdate(getUpdatedConfigData())
        onBack()
    }

    // Auto-save: persist every edit after a short debounce (no Save/Cancel buttons). snapshotFlow
    // re-runs getUpdatedConfigData() whenever any edited field changes; collectLatest cancels the
    // pending delay on each new change, so only the settled value is written (not one save per
    // keystroke). The first (initial, unchanged) emission is skipped so opening the editor doesn't
    // trigger a needless write.
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { getUpdatedConfigData() }
            .collectLatest { cfg ->
                if (first) { first = false; return@collectLatest }
                delay(400)
                onUpdate(cfg)
            }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.cfg_title_edit)) },
            navigationIcon = {
                IconButton(onClick = {
                    // Flush the latest edit synchronously BEFORE navigating: the auto-save debounce
                    // (400 ms) would otherwise be cancelled by leaving composition, losing an edit made
                    // just before tapping Back. This runs only on the back path — delete uses onDelete,
                    // so a just-deleted bike can't be resurrected here.
                    onUpdate(getUpdatedConfigData())
                    onBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.btn_back))
                }
            },
        )
        Column(modifier = Modifier
            .padding(5.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.section_bike), style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.cfg_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)

            // Per-bike dot colour: a row of selectable swatches from the curated palette. The
            // chosen colour is what the bikes list shows as the bike's dot. Horizontally scrollable
            // so the full palette is reachable on the narrow Karoo screen.
            Text(stringResource(R.string.cfg_color), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                bikeDotColors.forEach { argb ->
                    val selected = dotColor == argb
                    Surface(
                        shape = CircleShape,
                        color = Color(argb),
                        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { dotColor = argb },
                        content = {}
                    )
                }
            }

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

            // Este campo es la ÚNICA vía por la que el equipo entra en el modelo: el peso del
            // ciclista lo toma KPower del perfil del Karoo (PowerEstimationEngine.kt:232), que es
            // de Hammerhead y el usuario rellenó pensando en su peso corporal. Sin el desglose,
            // todo el mundo pone el peso de catálogo de la bici y se deja bidones, herramientas,
            // casco, ropa y mochila: un sesgo sistemático, universal y siempre en el mismo
            // sentido (falta masa -> el estimador va corto). Se pide el número correcto en vez de
            // sumar una constante oculta, que mentiría sobre lo que el rider escribió.
            OutlinedTextField(value = bikeMass, modifier = Modifier.fillMaxWidth(),
                onValueChange = { bikeMass = it },
                label = { Text(stringResource(R.string.cfg_bike_mass)) }, suffix = { Text("kg") },
                supportingText = { Text(stringResource(R.string.cfg_bike_mass_hint)) },
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
                // Wind-unit selector: Headwind emits wind in the unit you picked there. AUTO assumes its
                // default (Karoo units); set it explicitly if you changed it, so KPower converts to m/s.
                if (headwindInstalled) {
                    val windUnitOptions = HeadwindWindUnit.entries.toList().map { DropdownOption(it.name, it.label) }
                    val selectedWindUnit by remember(headwindWindUnit) {
                        mutableStateOf(windUnitOptions.first { it.id == headwindWindUnit.name })
                    }
                    KarooKeyDropdown(remotekey = stringResource(R.string.headwind_wind_unit_label), options = windUnitOptions, selectedOption = selectedWindUnit) { opt ->
                        headwindWindUnit = HeadwindWindUnit.valueOf(opt.id)
                    }
                }
            }

            // Weather provider is Open-Meteo (free, no API key) — no provider/key controls needed.
            // (Field calibration is a DEV tuning aid written to the diagnostic log, not a UI feature.)

            // Manual estimate correction — shown in BOTH simple and advanced (it's not an advanced-only
            // coefficient, it's a user-facing tweak). Both 0 = identity (see PowerOffset.applyPowerOffset).
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.offset_section), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.offset_formula_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = estPowerFactorPct, modifier = Modifier.weight(1f),
                    onValueChange = { estPowerFactorPct = signedIntFilter(it) },
                    label = { Text(stringResource(R.string.offset_factor_pct)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = estPowerOffsetW, modifier = Modifier.weight(1f),
                    onValueChange = { estPowerOffsetW = signedIntFilter(it) },
                    label = { Text(stringResource(R.string.offset_watts)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
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

            // No Save/Cancel: edits auto-save (see the LaunchedEffect above), the back arrow returns.
            // Delete is always available — it's also how a just-created bike is discarded.
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

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_bike_confirm_title)) },
            text = { Text(stringResource(R.string.delete_bike_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    deleted.value = true
                    onDelete()
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

package com.enderthor.kpower.screens



import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.enderthor.kpower.R
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.previewConfigData
import com.enderthor.kpower.extension.exportBikesConfig
import com.enderthor.kpower.extension.importBikesConfig
import com.enderthor.kpower.extension.loadPreferencesFlow
import com.enderthor.kpower.extension.savePreferences

import timber.log.Timber



@Composable
fun ConfigDataAppNavHost(modifier: Modifier = Modifier, navController: NavHostController = rememberNavController()){
    val scope = rememberCoroutineScope()
    val configDatas = remember {
        mutableStateListOf<ConfigData>()
    }

    val ctx = LocalContext.current


/*
    LaunchedEffect(Unit) {
        ctx.dataStore.data.distinctUntilChanged().collect { t ->
            configDatas.clear()
            try {
                val entries = Json.decodeFromString<MutableList<ConfigData>>(
                    t[preferencesKey] ?: defaultConfigData
                ).map { configData ->
                    configData.copy(surface = configData.surface)
                }
                configDatas.addAll(entries)
            } catch(e: Throwable){
                Timber.tag("kpower").e(e, "Failed to read preferences PCM")
            }
        }
    }*/

    LaunchedEffect(Unit) {
        ctx.loadPreferencesFlow().collect{
            configDatas.clear()
            configDatas.addAll(it)
        }
    }


    NavHost(modifier = modifier, navController = navController, startDestination = "configDatas") {
        composable(route = "configData/{id}", arguments = listOf(
            navArgument("id") {
                type = NavType.IntType
                nullable = false
            }
        )) { stack ->
            val configDataId = stack.arguments?.getInt("id")
            val configData = configDatas.find { it.id  == configDataId}

            val ctx = LocalContext.current

            configData?.let { r ->
                // Auto-save: the editor pushes every change through onUpdate (no Save/Cancel). The
                // bike already exists in the list (the "+" creates it up front), so onUpdate replaces
                // it by id + persists; onBack is pure navigation; onDelete discards it (also how a
                // just-created bike is thrown away). Resolve the entry by id, never by captured index
                // or object identity — each auto-save swaps in a NEW ConfigData, so a stale object
                // wouldn't match for remove() and a fixed index could drift.
                DetailScreen(
                    configdata = r,
                    onUpdate = { updated ->
                        val idx = configDatas.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) configDatas[idx] = updated
                        scope.launch { savePreferences(ctx, configDatas) }
                    },
                    onDelete = {
                        configDatas.removeAll { it.id == r.id }
                        if (r.isActive && configDatas.isNotEmpty() && configDatas.none { it.isActive }) {
                            configDatas[0] = configDatas[0].copy(isActive = true)
                        }
                        scope.launch { savePreferences(ctx, configDatas) }
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
        composable(route = "configDatas") {
            MainScreen(
                configDatas,
                onImport = { imported ->
                    // Replace the whole list with the imported bikes (a restore/transfer). Reassign
                    // sequential unique ids — a hand-merged file could carry duplicate ids, which would
                    // make the editor (resolves by id) target the wrong bike — and keep EXACTLY one
                    // active (drop extra actives; if none, make the first active).
                    var activeSeen = false
                    val normalized = imported.mapIndexed { i, c ->
                        val active = c.isActive && !activeSeen
                        if (active) activeSeen = true
                        c.copy(id = i, isActive = active)
                    }.let { list ->
                        if (list.isNotEmpty() && list.none { it.isActive })
                            list.mapIndexed { i, c -> if (i == 0) c.copy(isActive = true) else c }
                        else list
                    }
                    configDatas.clear()
                    configDatas.addAll(normalized)
                    scope.launch { savePreferences(ctx, configDatas) }
                },
                onNavigateToConfigData = { configdata -> navController.navigate(route = "configData/${configdata.id}") },
                onNavigateToCreate = {
                    // Crear al instante: new bike from the template with a unique id (first bike =
                    // active), persisted immediately, then open its editor. No separate "create"
                    // screen — creating IS editing an auto-saved bike; discard = Delete.
                    val newConfigData = previewConfigData.first().copy(
                        id = (configDatas.maxOfOrNull { it.id } ?: 0) + 1,
                        isActive = configDatas.isEmpty(),
                    )
                    configDatas.add(newConfigData)
                    scope.launch { savePreferences(ctx, configDatas) }
                    navController.navigate(route = "configData/${newConfigData.id}")
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    configDatas: MutableList<ConfigData>,
    onNavigateToConfigData: (r: ConfigData) -> Unit,
    onNavigateToCreate: () -> Unit = {},
    onImport: (List<ConfigData>) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_bikes)) },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.menu_more))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_export)) },
                            onClick = {
                                menuExpanded = false
                                val snapshot = configDatas.toList()
                                scope.launch {
                                    val path = withContext(Dispatchers.IO) { ctx.exportBikesConfig(snapshot).absolutePath }
                                    Toast.makeText(ctx, ctx.getString(R.string.export_done, path), Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_import)) },
                            onClick = {
                                menuExpanded = false
                                scope.launch {
                                    val imported = withContext(Dispatchers.IO) { ctx.importBikesConfig() }
                                    if (imported != null) {
                                        onImport(imported)
                                        Toast.makeText(ctx, ctx.getString(R.string.import_done, imported.size), Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(ctx, ctx.getString(R.string.import_failed), Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        )
                    }
                },
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreate) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.bikes_add))
            }
        },
        content = {
            Column(
                Modifier
                    .padding(it)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.background)) {

                configDatas.forEach { configData ->
                    Card(Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .alpha(if (configData.isActive) 1f else 0.6f)
                        .clickable { onNavigateToConfigData(configData) }
                        .padding(5.dp), shape = RoundedCornerShape(corner = CornerSize(10.dp))
                    ) {
                        Row(
                            Modifier
                                .height(60.dp)
                                .padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = Color(configData.dotColorArgb),
                                modifier = Modifier
                                    .height(60.dp)
                                    .shadow(5.dp, CircleShape)
                                    .width(40.dp), content = {})

                            Spacer(modifier = Modifier.width(10.dp))

                            Text(configData.name)

                            Spacer(Modifier.weight(1.0f))

                        }
                    }
                }

                if (configDatas.isEmpty()) Text(modifier = Modifier.padding(5.dp), text = stringResource(R.string.bikes_empty))

            }
        }
    )
}

@Preview(name = "karoo", device = "spec:width=480px,height=800px,dpi=300")
@Composable
private fun PreviewTabLayout() {
    ConfigDataAppNavHost()
}
package com.enderthor.kpower.screens


import android.content.Context
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

import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.enderthor.kpower.activity.dataStore
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.defaultConfigData

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber


val preferencesKey = stringPreferencesKey("configdata")

suspend fun saveconfigs(context: Context, configDatas: MutableList<ConfigData>) {
    context.dataStore.edit { t ->
        t[preferencesKey] = Json.encodeToString(configDatas)
    }
}

@Composable
fun ConfigDataAppNavHost(modifier: Modifier = Modifier, navController: NavHostController = rememberNavController()){
    val scope = rememberCoroutineScope()
    val configDatas = remember {
        mutableStateListOf<ConfigData>()
    }

    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        ctx.dataStore.data.distinctUntilChanged().collect { t ->
            configDatas.clear()
            try {
                val entries = Json.decodeFromString<MutableList<ConfigData>>(
                    t[preferencesKey] ?: defaultConfigData
                ).map { configData ->
                    configData.copy(surface = configData.surface ?: KarooSurface.STANDARD)
                }
                configDatas.addAll(entries)
            } catch(e: Throwable){
                Timber.tag("kpower").e(e, "Failed to read preferences PCM")
            }
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
            val configDataIndex = configDatas.indexOf(configData)

            val ctx = LocalContext.current

            configData?.let { r ->
                DetailScreen(false, r, { updatedConfigData ->
                    if (updatedConfigData != null) {
                        configDatas[configDataIndex] = updatedConfigData
                    } else {
                        configDatas.remove(r)
                    }

                    scope.launch {
                        saveconfigs(ctx, configDatas)
                    }
                    navController.popBackStack()
                }, { navController.popBackStack() })
            }
        }
        composable(route = "create") {

            val newConfigData = Json.decodeFromString<ConfigData>(defaultConfigData)
            val ctx = LocalContext.current

            DetailScreen(true, newConfigData, { updatedConfigData ->
                updatedConfigData?.let { r ->
                    configDatas.add(r)
                }

                scope.launch {
                    saveconfigs(ctx, configDatas)
                }

                navController.popBackStack()
            }, { navController.popBackStack() })
        }
        composable(route = "configDatas") {
            MainScreen(configDatas, { configdata -> navController.navigate(route = "configdata/${configdata.id}") })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(configDatas: MutableList<ConfigData>, onNavigateToConfigData: (r: ConfigData) -> Unit) {

    Scaffold(
        topBar = { TopAppBar(title = {Text("Config")}) },
        floatingActionButtonPosition = FabPosition.End,

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
                            Surface(shape = CircleShape, color = Color.Gray,
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

                if (configDatas.isEmpty()) Text(modifier = Modifier.padding(5.dp), text = "No configuration added.")

            }
        }
    )
}

@Preview(name = "karoo", device = "spec:width=480px,height=800px,dpi=300")
@Composable
private fun PreviewTabLayout() {
    ConfigDataAppNavHost()
}
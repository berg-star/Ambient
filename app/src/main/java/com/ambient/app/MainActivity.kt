package com.ambient.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.edit
import com.ambient.app.data.environment.qweather.AndroidEnvironmentRepository
import com.ambient.app.data.history.RoomHistoryRepository
import com.ambient.app.data.history.HistoryCsvExporter
import com.ambient.app.ui.history.HistoryExportViewModel
import com.ambient.app.ui.screens.HistoryExportDestinationHandler
import com.ambient.app.ui.history.HistoryViewModel
import com.ambient.app.ui.screens.SnapshotSaveControl
import com.ambient.app.data.location.AndroidAreaLocator
import com.ambient.app.ui.screens.WeatherSettingsCard
import com.ambient.app.data.sensor.AndroidSensorRepository
import com.ambient.app.data.orientation.AndroidOrientationRepository
import com.ambient.app.data.location.AndroidLocationSpeedRepository
import com.ambient.app.ui.location.SpeedViewModel
import com.ambient.app.ui.screens.LocationSpeedCard
import com.ambient.app.ui.components.InstrumentSymbol
import com.ambient.app.ui.overview.EnvironmentViewModel
import com.ambient.app.ui.screens.EnvironmentScreen
import com.ambient.app.ui.screens.HistoryScreen
import com.ambient.app.ui.screens.SensorsScreen
import com.ambient.app.ui.screens.SettingsScreen
import com.ambient.app.ui.sensors.SensorsViewModel
import com.ambient.app.ui.theme.AmbientTheme
import com.ambient.app.ui.theme.AmbientBackdrop
import com.ambient.app.ui.theme.ThemeMode
import com.ambient.app.ui.theme.ThemeStyle
import com.ambient.app.ui.theme.isDark
import com.ambient.app.ui.sky.SkyViewModel
import com.ambient.app.ui.screens.SkyScreen
import com.ambient.app.ui.precipitation.PrecipitationViewModel
import com.ambient.app.ui.screens.PrecipitationScreen
import com.ambient.app.ui.screens.EnvironmentDetailsScreen
import com.ambient.app.ui.screens.ObservationScreen
import com.ambient.app.ui.observation.ObservationViewModel
import com.ambient.app.data.observation.AndroidObservationRepository
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences = remember { getSharedPreferences("ambient_settings", Context.MODE_PRIVATE) }
            var themeStyle by remember { mutableStateOf(ThemeStyle.FLOWING_GLASS) }
            var themeMode by remember { mutableStateOf(ThemeMode.DARK) }
            LaunchedEffect(Unit) {
                val savedAppearance = withContext(Dispatchers.IO) {
                    val style = runCatching {
                        ThemeStyle.valueOf(preferences.getString("theme_style", "FLOWING_GLASS") ?: "FLOWING_GLASS")
                    }.getOrDefault(ThemeStyle.FLOWING_GLASS)
                    val mode = runCatching {
                        ThemeMode.valueOf(preferences.getString("theme", style.preferredMode.name) ?: style.preferredMode.name)
                    }.getOrDefault(style.preferredMode)
                    style to mode
                }
                themeStyle = savedAppearance.first
                themeMode = savedAppearance.second
            }
            val dark = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AmbientTheme(mode = themeMode, style = themeStyle) {
                AmbientApp(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        preferences.edit { putString("theme", mode.name) }
                    },
                    themeStyle = themeStyle,
                    onThemeStyleChange = { style ->
                        themeStyle = style
                        themeMode = style.preferredMode
                        preferences.edit {
                            putString("theme_style", style.name)
                            putString("theme", style.preferredMode.name)
                        }
                    },
                )
            }
        }
    }
}

private enum class Destination(val label: String, val icon: String) {
    OVERVIEW("总览", "humidity"), SENSORS("探测", "pressure"), HISTORY("记录", "history"),
}

/** Three top-level destinations retain their selection across recreation. Settings has back navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmbientApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val environmentRepository = remember { AndroidEnvironmentRepository(context) }
    val overview: EnvironmentViewModel = viewModel(factory = remember {
        ambientFactory { EnvironmentViewModel(environmentRepository, AndroidAreaLocator(context)) }
    })
    val sky: SkyViewModel = viewModel(factory = remember { ambientFactory { SkyViewModel(environmentRepository) } })
    val precipitation: PrecipitationViewModel = viewModel(factory = remember { ambientFactory { PrecipitationViewModel(environmentRepository) } })
    val historyRepository = remember { RoomHistoryRepository(context) }
    val history: HistoryViewModel = viewModel(factory = remember {
        ambientFactory { HistoryViewModel(historyRepository) }
    })
    val export: HistoryExportViewModel = viewModel(factory = remember {
        ambientFactory { HistoryExportViewModel(HistoryCsvExporter(context, historyRepository)) }
    })
    HistoryExportDestinationHandler(export)
    val sensors: SensorsViewModel = viewModel(factory = remember {
        ambientFactory { SensorsViewModel(AndroidSensorRepository(context), AndroidOrientationRepository(context)) }
    })
    val observation: ObservationViewModel = viewModel(factory = remember {
        ambientFactory { ObservationViewModel(AndroidObservationRepository(context)) }
    })
    val speed: SpeedViewModel = viewModel(factory = remember {
        ambientFactory { SpeedViewModel(AndroidLocationSpeedRepository(context)) }
    })
    var destination by rememberSaveable { mutableStateOf(Destination.OVERVIEW.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSky by rememberSaveable { mutableStateOf(false) }
    var showPrecipitation by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var showObservation by rememberSaveable { mutableStateOf(false) }
    val selected = Destination.valueOf(destination)
    BackHandler(showSettings || showSky || showPrecipitation || showDetails || showObservation || selected != Destination.OVERVIEW) {
        if (showSettings) showSettings = false else if (showSky) showSky = false
        else if (showPrecipitation) showPrecipitation = false else if (showDetails) showDetails = false
        else if (showObservation) showObservation = false else destination = Destination.OVERVIEW.name
    }
    AmbientBackdrop(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = { Text(if (showSettings) "设置" else if (showObservation) "观察模式" else if (showSky) "日光与天空" else if (showPrecipitation) "降水预报" else if (showDetails) "环境详情" else "Ambient", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (showSettings) TextButton(onClick = { showSettings = false }) { Text("返回") }
                    else if (showSky) TextButton(onClick = { showSky = false }) { Text("返回") }
                    else if (showPrecipitation) TextButton(onClick = { showPrecipitation = false }) { Text("返回") }
                    else if (showDetails) TextButton(onClick = { showDetails = false }) { Text("返回") }
                    else if (showObservation) TextButton(onClick = { showObservation = false }) { Text("返回") }
                },
                actions = {
                    if (!showSettings) TextButton(onClick = { showSettings = true }) { Text("设置") }
                },
            )
        },
        bottomBar = {
            if (!showSettings && !showSky && !showPrecipitation && !showDetails && !showObservation) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 0.dp,
            ) {
                Destination.entries.forEach { target ->
                    NavigationBarItem(
                        selected = selected == target,
                        onClick = { destination = target.name },
                        icon = { InstrumentSymbol(target.icon, color = if (selected == target)
                            MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) },
                        label = { Text(target.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val contentModifier = Modifier.widthIn(max = 700.dp).fillMaxWidth()
            if (showSettings) SettingsScreen(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                themeStyle = themeStyle,
                onThemeStyleChange = onThemeStyleChange,
                modifier = contentModifier,
                weatherContent = { WeatherSettingsCard(overview) },
            )
            else if (showObservation) ObservationScreen(observation, contentModifier)
            else if (showDetails) {
                val detailsState by overview.state.collectAsStateWithLifecycle()
                EnvironmentDetailsScreen(detailsState, overview::refresh, contentModifier)
            }
            else if (showPrecipitation) {
                val rainState by precipitation.state.collectAsStateWithLifecycle()
                LifecycleResumeEffect(precipitation) {
                    precipitation.reset()
                    precipitation.load()
                    onPauseOrDispose { precipitation.stop() }
                }
                PrecipitationScreen(rainState, { precipitation.load(force = true) }, contentModifier)
            }
            else if (showSky) {
                val skyState by sky.state.collectAsStateWithLifecycle()
                val weatherState by overview.state.collectAsStateWithLifecycle()
                LifecycleResumeEffect(sky) {
                    sky.reset()
                    sky.load()
                    onPauseOrDispose { sky.stop() }
                }
                val matchingWeather = weatherState.snapshot?.takeIf {
                    !it.isDemo && it.areaLabel == skyState.result.snapshot?.area?.label
                }
                SkyScreen(skyState, { sky.load(force = true) }, {
                    showSky = false; destination = Destination.SENSORS.name
                }, contentModifier, uv = matchingWeather?.metrics?.firstOrNull { it.id == "uv" },
                    uvTime = matchingWeather?.updatedAt, uvCached = weatherState.isCached)
            }
            else when (selected) {
                Destination.OVERVIEW -> {
                    val state by overview.state.collectAsStateWithLifecycle()
                    LifecycleResumeEffect(overview) {
                        onPauseOrDispose { overview.cancelLocation() }
                    }
                    EnvironmentScreen(state, overview::refresh, { destination = Destination.SENSORS.name }, contentModifier,
                        overview = overview, onSettings = { showSettings = true },
                        saveContent = { SnapshotSaveControl(history, state) { destination = Destination.HISTORY.name } },
                        onOpenSky = { showSky = true }, onOpenPrecipitation = { showPrecipitation = true },
                        onOpenDetails = { showDetails = true })
                }
                Destination.SENSORS -> {
                    LifecycleResumeEffect(speed) {
                        speed.resume()
                        onPauseOrDispose { speed.pause() }
                    }
                    SensorsScreen(sensors, contentModifier, onOpenObservation = { showObservation = true }) { LocationSpeedCard(speed) }
                }
                Destination.HISTORY -> HistoryScreen(history, export, { destination = Destination.OVERVIEW.name }, contentModifier)
            }
        }
    }
    }
}

private fun <T : ViewModel> ambientFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

package com.enderthor.kpower.extension

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey


import com.enderthor.kpower.BuildConfig
import com.enderthor.kpower.activity.dataStore
import com.enderthor.kpower.data.GpsCoordinates
import com.enderthor.kpower.data.KnownProfile
import com.enderthor.kpower.data.OpenMeteoCurrentWeatherResponse
import com.enderthor.kpower.data.HeadwindStats
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.OpenMeteoData
import com.enderthor.kpower.data.RETRY_CHECK_STREAMS
import com.enderthor.kpower.data.STREAM_TIMEOUT
import com.enderthor.kpower.data.WEATHER_STREAM_FUTURE_SKEW_MS
import com.enderthor.kpower.data.WEATHER_STREAM_MAX_AGE_MS
import com.enderthor.kpower.data.StreamData
import com.enderthor.kpower.data.WAIT_STREAMS_LONG
import com.enderthor.kpower.data.WAIT_STREAMS_MEDIUM
import com.enderthor.kpower.data.WAIT_STREAMS_SHORT
import com.enderthor.kpower.data.defaultConfigData


import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.KarooEvent
import io.hammerhead.karooext.models.OnHttpResponse
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RideProfile
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState



import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine


import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow



import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull


import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round
import kotlin.time.Duration.Companion.milliseconds

import kotlin.time.Duration.Companion.seconds

sealed class HeadingResponse {
    data object NoGps: HeadingResponse()
    data object NoWeatherData: HeadingResponse()
    data class Value(val diff: Double): HeadingResponse()
}


val jsonWithUnknownKeys = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

val currentDataKey = stringPreferencesKey("current")
val statsKey = stringPreferencesKey("stats")
val lastKnownPositionKey = stringPreferencesKey("lastKnownPosition")

val preferencesKey = stringPreferencesKey("configdata")
val comparisonModeKey = booleanPreferencesKey("comparisonMode")
val antMetersKey = stringPreferencesKey("antPowerMeters")
val calibrationKey = stringPreferencesKey("fieldCalibration")

suspend fun saveComparisonMode(context: Context, enabled: Boolean) {
    context.dataStore.edit { it[comparisonModeKey] = enabled }
}

/** Toggle global (off por defecto): expone campos custom + escribe FIT de comparación. */
fun Context.comparisonModeFlow(): Flow<Boolean> =
    dataStore.data.map { it[comparisonModeKey] ?: false }.distinctUntilChanged()

val diagnosticLogKey = booleanPreferencesKey("diagnosticLog")

suspend fun saveDiagnosticLog(context: Context, enabled: Boolean) {
    context.dataStore.edit { it[diagnosticLogKey] = enabled }
}

/** Toggle global (off por defecto): escribe logs de diagnóstico a fichero (FileLogTree). */
fun Context.diagnosticLogFlow(): Flow<Boolean> =
    dataStore.data.map { it[diagnosticLogKey] ?: false }.distinctUntilChanged()

val batteryAlertKey = booleanPreferencesKey("batteryAlert")

suspend fun saveBatteryAlert(context: Context, enabled: Boolean) {
    context.dataStore.edit { it[batteryAlertKey] = enabled }
}

/** Toggle global (off por defecto): dispara un InRideAlert cuando la batería del meter grabado baja
 *  (LOW) y otro si pasa a crítica (CRITICAL), como mucho uno por nivel y ride. */
fun Context.batteryAlertFlow(): Flow<Boolean> =
    dataStore.data.map { it[batteryAlertKey] ?: false }.distinctUntilChanged()

/**
 * Atomic read-modify-write of the saved meters: decode the current value, apply [transform], and
 * re-encode — all INSIDE one dataStore.edit {} transaction. Use this for every mutation (add,
 * delete, rename, enable-toggle, brand auto-detect) so two concurrent writers (e.g. the background
 * brand-detect collector and a manual rename) can't clobber each other with a stale snapshot.
 */
suspend fun updateAntMeters(
    context: Context,
    transform: (List<com.enderthor.kpower.ant.SavedMeter>) -> List<com.enderthor.kpower.ant.SavedMeter>,
) {
    context.dataStore.edit { prefs ->
        val current = runCatching {
            jsonWithUnknownKeys.decodeFromString<List<com.enderthor.kpower.ant.SavedMeter>>(prefs[antMetersKey] ?: "[]")
        }.getOrDefault(emptyList())
        val updated = transform(current)
        // Skip the write (and its flow re-emission) when nothing changed — the brand/model auto-detect
        // re-applies the same name on every reconnect/screen revisit, which would otherwise churn the
        // DataStore and recompose every collector for no reason. (SavedMeter is a data class → eq.)
        if (updated != current) prefs[antMetersKey] = Json.encodeToString(updated)
    }
}

fun Context.antMetersFlow(): Flow<List<com.enderthor.kpower.ant.SavedMeter>> =
    dataStore.data.map { json ->
        try {
            jsonWithUnknownKeys.decodeFromString<List<com.enderthor.kpower.ant.SavedMeter>>(json[antMetersKey] ?: "[]")
        } catch (e: Throwable) {
            Timber.e(e, "Failed to read antMeters")
            emptyList()
        }
    }.distinctUntilChanged()

val knownProfilesKey = stringPreferencesKey("knownProfiles")

suspend fun saveKnownProfiles(context: Context, profiles: List<KnownProfile>) {
    context.dataStore.edit { it[knownProfilesKey] = Json.encodeToString(profiles) }
}

fun Context.knownProfilesFlow(): Flow<List<KnownProfile>> =
    dataStore.data.map { json ->
        try {
            jsonWithUnknownKeys.decodeFromString<List<KnownProfile>>(json[knownProfilesKey] ?: "[]")
        } catch (e: Throwable) {
            Timber.e(e, "Failed to read knownProfiles")
            emptyList()
        }
    }.distinctUntilChanged()

suspend fun savePreferences(context: Context, configDatas: List<ConfigData>) {
    context.dataStore.edit { t ->
        t[preferencesKey] = Json.encodeToString(configDatas)
    }
}

suspend fun saveStats(context: Context, stats: HeadwindStats) {
    context.dataStore.edit { t ->
        t[statsKey] = Json.encodeToString(stats)
    }
}

suspend fun saveCalibration(context: Context, s: com.enderthor.kpower.data.CalibrationSuggestion) {
    context.dataStore.edit { it[calibrationKey] = Json.encodeToString(s) }
}

suspend fun clearCalibration(context: Context) {
    context.dataStore.edit { it.remove(calibrationKey) }
}

/** Latest field-calibration suggestion (or null). */
fun Context.calibrationFlow(): Flow<com.enderthor.kpower.data.CalibrationSuggestion?> =
    dataStore.data.map { prefs ->
        prefs[calibrationKey]?.let { runCatching { jsonWithUnknownKeys.decodeFromString<com.enderthor.kpower.data.CalibrationSuggestion>(it) }.getOrNull() }
    }

suspend fun saveCurrentData(context: Context, forecast: OpenMeteoCurrentWeatherResponse) {
    context.dataStore.edit { t ->
        t[currentDataKey] = Json.encodeToString(forecast)
    }
}

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> {
    return callbackFlow {
        // trySend + CONFLATED: the callback runs on the Karoo binder thread, so it must NOT block
        // (trySendBlocking on a RENDEZVOUS channel would stall that foreign thread on a slow collector).
        // For a streaming value, latest-wins (conflate) is the right drop policy.
        val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySend(event.state)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }.buffer(Channel.CONFLATED)
}

fun Context.streamCurrentWeatherData(): Flow<OpenMeteoCurrentWeatherResponse> {
    return dataStore.data.map { settingsJson ->
        try {
            val data = settingsJson[currentDataKey]
            data?.let { d -> jsonWithUnknownKeys.decodeFromString<OpenMeteoCurrentWeatherResponse>(d) }
        } catch (e: Throwable) {
            Timber.e("Failed to stream current weather data $e")
            null
        }
    }.filterNotNull().distinctUntilChanged().filter {
        // A missing/zero observation timestamp can't be aged — ACCEPT it rather than silently starving
        // the estimator to ISA defaults (the refresh loop owns actual freshness).
        if (it.current.time <= 0L) return@filter true
        // Otherwise accept weather whose observation time is recent-ish (generous: the API time lags
        // fetch time) and not implausibly in the future (clock skew). Log drops — they're silent ISA.
        val ageMs = System.currentTimeMillis() - it.current.time * 1000
        val ok = ageMs in -WEATHER_STREAM_FUTURE_SKEW_MS..WEATHER_STREAM_MAX_AGE_MS
        if (!ok) Timber.w("Dropping weather sample: age=${ageMs}ms (out of [-${WEATHER_STREAM_FUTURE_SKEW_MS}, ${WEATHER_STREAM_MAX_AGE_MS}])")
        ok
    }
}

fun Context.streamStats(): Flow<HeadwindStats> {
    return dataStore.data.map { statsJson ->
        try {
            jsonWithUnknownKeys.decodeFromString<HeadwindStats>(
                statsJson[statsKey] ?: HeadwindStats.defaultStats
            )
        } catch(e: Throwable){
            Timber.e("Failed to read stats $e")
            jsonWithUnknownKeys.decodeFromString<HeadwindStats>(HeadwindStats.defaultStats)
        }
    }.distinctUntilChanged()
}
fun Context.loadPreferencesFlow(): Flow<List<ConfigData>> {
    return dataStore.data.map { settingsJson ->
        try {
            jsonWithUnknownKeys.decodeFromString<List<ConfigData>>(
                settingsJson[preferencesKey] ?: defaultConfigData
            )
        } catch(e: Throwable){
            Timber.tag("kpower").e(e, "Failed to read preferences Flow Extension")
            jsonWithUnknownKeys.decodeFromString<List<ConfigData>>(defaultConfigData)
        }
    }.distinctUntilChanged()
}

// ── Bikes config export/import ────────────────────────────────────────────────────────────────────
// A single fixed JSON file in the app's external files dir (same place as the diagnostic logs), so it
// can be pulled/pushed with Hammerhead Companion or adb to move bikes between devices. Matches the
// established Karoo file pattern — no document picker (which the Karoo may lack) is needed.
fun Context.bikesConfigFile(): java.io.File =
    java.io.File(getExternalFilesDir(null) ?: filesDir, "kpower_bikes.json")

/** Write the bikes list to the export file; returns it (for showing its path). IO — call off-main. */
fun Context.exportBikesConfig(configs: List<ConfigData>): java.io.File {
    val f = bikesConfigFile()
    f.writeText(Json.encodeToString(configs))
    return f
}

/** Read + parse the export file. null if it's missing, empty, or malformed. IO — call off-main. */
fun Context.importBikesConfig(): List<ConfigData>? {
    val f = bikesConfigFile()
    if (!f.exists()) return null
    return try {
        jsonWithUnknownKeys.decodeFromString<List<ConfigData>>(f.readText()).takeIf { it.isNotEmpty() }
    } catch (e: Throwable) {
        Timber.tag("kpower").e(e, "Failed to import bikes config")
        null
    }
}



fun Context.parseWeatherResponse(responseString: String): OpenMeteoCurrentWeatherResponse {
    return try {
        jsonWithUnknownKeys.decodeFromString<OpenMeteoCurrentWeatherResponse>(responseString)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid response format parse weather", e)
    }
}


@OptIn(FlowPreview::class)
suspend fun KarooSystemService.makeOpenMeteoHttpRequest(gpsCoordinates: GpsCoordinates): HttpResponseState.Complete {
    return callbackFlow {

        // Open-Meteo is the sole weather provider (free, no API key). Surface pressure (not sea-level)
        // and m/s wind are requested so the estimator can use them directly.
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${gpsCoordinates.lat}&longitude=${gpsCoordinates.lon}&current=wind_speed_10m,wind_direction_10m,temperature_2m,surface_pressure&timeformat=unixtime&wind_speed_unit=ms"

        if (BuildConfig.DEBUG) Timber.d("Http request to %s", url)

        val listenerId = addConsumer(
            OnHttpResponse.MakeHttpRequest(
                "GET",
                url= url,
                waitForConnection = false,
            ),
        ) { event: OnHttpResponse ->
            if (event.state is HttpResponseState.Complete){
                trySend(event.state as HttpResponseState.Complete)
                close()
            }
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }.buffer(Channel.CONFLATED).timeout(20.seconds).catch { e: Throwable ->
        if (e is TimeoutCancellationException){
            emit(HttpResponseState.Complete(500, mapOf(), null, "Timeout"))
        } else {
            throw e
        }
    }.single()
}

const val HEADWIND_PACKAGE = "de.timklge.karooheadwind"

/** Headwind instalado en el Karoo. Requiere <package> en <queries> (Android 11+). */
fun Context.isHeadwindInstalled(): Boolean = try {
    packageManager.getPackageInfo(HEADWIND_PACKAGE, 0)
    true
} catch (e: PackageManager.NameNotFoundException) {
    false
} catch (e: Throwable) {
    Timber.e(e, "isHeadwindInstalled check failed")
    false
}

/**
 * Toma un snapshot de la meteo publicada por Headwind por el stream system de karoo-ext
 * (temperatura, presión, viento y dirección) y lo mapea al mismo modelo que la API propia,
 * para que todo aguas abajo (streamCurrentWeatherData, headwindFlow) funcione sin cambios.
 *
 * Unidades: temperatura (°C) y presión (hPa) salen crudas de Headwind. El viento, en cambio,
 * sale convertido a la unidad de viento que el usuario eligió en Headwind; asumimos su DEFAULT
 * (km/h métrico / mph imperial, derivable del perfil del Karoo) y lo reconvertimos a m/s. Si el
 * usuario cambió a mano esa unidad en Headwind a m/s o nudos, el viento saldrá mal (la UI avisa).
 *
 * Devuelve null si Headwind no emite datos completos antes del timeout → el caller hace fallback
 * a su propia API.
 */
suspend fun KarooSystemService.fetchHeadwindWeatherSnapshot(
    gps: GpsCoordinates,
    isImperial: Boolean,
    windUnit: com.enderthor.kpower.data.HeadwindWindUnit,
): OpenMeteoCurrentWeatherResponse? {
    // Nombre de la extensión karoo-ext de Headwind (DataTypeImpl("karoo-headwind", ...)),
    // distinto de su package Android (de.timklge.karooheadwind).
    fun id(typeId: String) = DataType.dataTypeId("karoo-headwind", typeId)
    return try {
        withTimeoutOrNull(STREAM_TIMEOUT) {
            combine(
                streamDataFlow(id("temperature")),
                streamDataFlow(id("surfacePressure")),
                streamDataFlow(id("windSpeed")),
                streamDataFlow(id("windDirection")),
            ) { temp, press, wind, dir ->
                if (temp is StreamState.Streaming && press is StreamState.Streaming &&
                    wind is StreamState.Streaming && dir is StreamState.Streaming
                ) {
                    val windRaw = wind.dataPoint.singleValue ?: 0.0
                    val windMs = windUnit.toMetersPerSecond(windRaw, isImperial)
                    OpenMeteoCurrentWeatherResponse(
                        current = OpenMeteoData(
                            time = System.currentTimeMillis() / 1000,
                            interval = 0,
                            windSpeed = windMs,
                            windDirection = dir.dataPoint.singleValue ?: 0.0,
                            temperature = temp.dataPoint.singleValue,
                            surfacePressure = press.dataPoint.singleValue,
                        ),
                        latitude = gps.lat,
                        longitude = gps.lon,
                        timezone = "",
                        elevation = 0.0,
                        utfOffsetSeconds = 0,
                    )
                } else null
            }.filterNotNull().first()
        }
    } catch (e: Throwable) {
        Timber.e(e, "fetchHeadwindWeatherSnapshot failed")
        null
    }
}

fun KarooSystemService.getRelativeHeadingFlow(context: Context): Flow<HeadingResponse> {
    val currentWeatherData = context.streamCurrentWeatherData()

    return getHeadingFlow(context)
        .combine(currentWeatherData) { bearing, data -> bearing to data }
        .map { (bearing, data) ->
            when (bearing) {
                is HeadingResponse.Value -> {
                    val windBearing = data.current.windDirection + 180
                    val diff = signedAngleDifference(bearing.diff, windBearing)
                    HeadingResponse.Value(diff)
                }

                is HeadingResponse.NoGps -> HeadingResponse.NoGps
                is HeadingResponse.NoWeatherData -> HeadingResponse.NoWeatherData
                else -> bearing
            }
        }
}


@SuppressLint("SuspiciousIndentation")
fun KarooSystemService.getHeadingFlow(context: Context): Flow<HeadingResponse> {
    // return flowOf(HeadingResponse.Value(20.0))

    return getGpsCoordinateFlow(context)
        .map { coords ->
            val heading = coords?.bearing
            heading?.let { HeadingResponse.Value(it) } ?: HeadingResponse.NoGps
        }
        .distinctUntilChanged()
}

fun signedAngleDifference(angle1: Double, angle2: Double): Double {
    // Normalise into [0,360): Kotlin's % keeps the dividend's sign, so a negative bearing (e.g. wind
    // direction math) would otherwise skew the result. (+360)%360 makes it non-negative.
    val a1 = ((angle1 % 360) + 360) % 360
    val a2 = ((angle2 % 360) + 360) % 360
    var diff = abs(a1 - a2)

    val sign = if (a1 < a2) {
        if (diff > 180.0) -1 else 1
    } else {
        if (diff > 180.0) 1 else -1
    }

    if (diff > 180.0) {
        diff = 360.0 - diff
    }

    return sign * diff
}


fun <T> concatenate(vararg flows: Flow<T>) = flow {
    for (flow in flows) {
        emitAll(flow)
    }
}
fun<T> Flow<T>.dropNullsIfNullEncountered(): Flow<T?> = flow {
    var hadValue = false

    collect { value ->
        if (!hadValue) {
            emit(value)
            if (value != null) hadValue = true
        } else {
            if (value != null) emit(value)
        }
    }
}


@OptIn(FlowPreview::class)
fun KarooSystemService.getGpsCoordinateFlow(context: Context): Flow<GpsCoordinates?> {

    val initialFlow = flow {
        val lastKnownPosition = context.getLastKnownPosition()

        emit(lastKnownPosition)
    }

    val gpsFlow = streamLocation()
        .filter { it.orientation != null }
        .map { GpsCoordinates(it.lat, it.lng, it.orientation) }

    val concatenatedFlow = concatenate(initialFlow, gpsFlow)

    return concatenatedFlow.dropNullsIfNullEncountered()
}

suspend fun KarooSystemService.updateLastKnownGps(context: Context) {
    getGpsCoordinateFlow(context)
        .filterNotNull()
        .throttle(60 * 1_000) // Only update last known gps position once every minute
        .collect { gps ->
            saveLastKnownPosition(context, gps)
        }
}

suspend fun Context.getLastKnownPosition(): GpsCoordinates? {
    val settingsJson = dataStore.data.first()

    try {
        val lastKnownPositionString = settingsJson[lastKnownPositionKey] ?: return null
        val lastKnownPosition = jsonWithUnknownKeys.decodeFromString<GpsCoordinates>(
            lastKnownPositionString
        )

        return lastKnownPosition
    } catch(e: Throwable){
       Timber.e( "Failed to read last known position $e")
        return null
    }
}

fun KarooSystemService.streamLocation(): Flow<OnLocationChanged> {
    return callbackFlow {
        // trySend + CONFLATED — don't block the binder thread; latest position wins. See streamDataFlow.
        val listenerId = addConsumer { event: OnLocationChanged ->
            trySend(event)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }.buffer(Channel.CONFLATED)
}

fun<T> Flow<T>.throttle(timeout: Long): Flow<T> = flow {
    var lastEmissionTime = 0L

    collect { value ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmissionTime >= timeout) {
            emit(value)
            lastEmissionTime = currentTime
        }
    }
}

suspend fun saveLastKnownPosition(context: Context, gpsCoordinates: GpsCoordinates) {
    Timber.i("Saving last known position: $gpsCoordinates")

    try {
        context.dataStore.edit { t ->
            t[lastKnownPositionKey] = Json.encodeToString(gpsCoordinates)
        }
    } catch(e: Throwable){
       Timber.e( "Failed to save last known position $e")
    }

}
inline fun <reified T : KarooEvent> KarooSystemService.consumerFlow(): Flow<T> {
    // buffer(UNLIMITED) + trySend: the callback runs on the Karoo's binder thread, so it must NOT block
    // (trySendBlocking on the default RENDEZVOUS channel would stall that foreign thread on a slow
    // collector — e.g. a DataStore write). The unlimited buffer makes trySend always succeed (no dropped
    // RideState transition) without ever blocking. Events here are low-rate, so the buffer stays tiny.
    return callbackFlow {
        val listenerId = addConsumer<T> {
            trySend(it)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }.buffer(Channel.UNLIMITED)
}

fun KarooSystemService.streamRideProfile(): Flow<RideProfile> =
    consumerFlow<ActiveRideProfile>().map { it.profile }

/** The Karoo's saved/paired sensors (name + connection type + battery + serial). SavedDevices needs
 *  an explicit Params, so it can't use the generic consumerFlow<T>() (no-params) helper. */
fun KarooSystemService.savedDevicesFlow(): Flow<SavedDevices> = callbackFlow {
    // trySend + buffer (never block the binder thread); see consumerFlow for the rationale.
    val listenerId = addConsumer(SavedDevices.Params) { event: SavedDevices -> trySend(event) }
    awaitClose { removeConsumer(listenerId) }
}.buffer(Channel.UNLIMITED)

/** True when a Karoo SavedDevice is (very likely) the ANT meter [dn]: an ANT connection whose id or
 *  serial carries the device number as a standalone token. A BLE/other device that merely contains the
 *  digits is excluded. The exact id format varies by firmware (the KAROODEV diag log captures it). */
fun SavedDevices.SavedDevice.matchesAntNumber(dn: Int): Boolean {
    val conn = connectionType ?: ""
    if (conn.isNotBlank() && !conn.contains("ant", ignoreCase = true)) return false
    val n = dn.toString()
    val serial = details.serialNumber ?: ""
    if (serial == n) return true
    val token = Regex("(^|\\D)" + Regex.escape(n) + "(\\D|$)")
    return token.containsMatchIn(id ?: "") || token.containsMatchIn(serial)
}

/** The Karoo's friendly name for ANT device [dn] among these saved devices, or null if unknown. */
fun List<SavedDevices.SavedDevice>.karooNameForAnt(dn: Int): String? =
    firstOrNull { it.matchesAntNumber(dn) }?.name?.trim()?.takeIf { it.isNotEmpty() }



/**
 * Stream-state monitor con timeout y back-off exponencial.
 *
 * - applyDistinct=false para SPEED (necesario para detección de GPS-stale aguas abajo:
 *   ver `speedStreamWithStaleness`). Para slope/elevation/cadence se filtran duplicados.
 * - noCheck=true se salta el back-off y delega directo en streamDataFlow.
 */
@OptIn(FlowPreview::class)
fun KarooSystemService.streamDataMonitorFlow(
    dataTypeID: String,
    noCheck: Boolean = false,
    applyDistinct: Boolean = true
): Flow<StreamState> = flow {

    if (noCheck) {
        streamDataFlow(dataTypeID).collect { emit(it) }
        return@flow
    }

    var retryAttempt = 0

    val initialState = StreamState.Streaming(
        DataPoint(
            dataTypeId = dataTypeID,
            values = mapOf(DataType.Field.SINGLE to 0.0)
        )
    )

    emit(initialState)

    while (currentCoroutineContext().isActive) {
        try {
            val base = streamDataFlow(dataTypeID)
            val source = if (applyDistinct) base.distinctUntilChanged() else base
            source
                .timeout(STREAM_TIMEOUT.milliseconds)
                .collect { state ->
                    when (state) {
                        is StreamState.Idle -> {
                            if (dataTypeID == DataType.Type.SPEED) emit(initialState)
                            delay(WAIT_STREAMS_SHORT)
                        }
                        is StreamState.NotAvailable -> {
                            emit(initialState)
                            delay(WAIT_STREAMS_SHORT * 2)
                        }
                        is StreamState.Searching -> {
                            emit(initialState)
                            delay(WAIT_STREAMS_SHORT / 2)
                        }
                        else -> {
                            retryAttempt = 0
                            emit(state)
                        }
                    }
                }

        } catch (e: Exception) {
            when (e) {
                is TimeoutCancellationException -> {
                    if (retryAttempt++ < RETRY_CHECK_STREAMS) {
                        val backoffDelay = (1000L * (1 shl retryAttempt))
                            .coerceAtMost(WAIT_STREAMS_MEDIUM)
                        delay(backoffDelay)
                    } else {
                        retryAttempt = 0
                        delay(WAIT_STREAMS_LONG)
                    }
                }
                is CancellationException -> { /* propagated by collect, ignore */ }
                else -> {
                    Timber.e(e, "Error en stream $dataTypeID")
                    delay(WAIT_STREAMS_LONG)
                }
            }
        }
    }
}

/**
 * Speed stream con detección de GPS-stale.
 *
 * Cuando el GPS se pierde el SDK reemite el ÚLTIMO valor conocido en vez de cero.
 * Si el valor no cambia durante [staleThresholdMs] y es > 0, lo tratamos como
 * stale y emitimos 0.0 para que el cálculo de potencia no produzca lecturas
 * fantasma en túneles/puentes.
 *
 * NOTA: no aplicar distinctUntilChanged aguas arriba (rompería la detección,
 * por eso `streamDataMonitorFlow` se llama con applyDistinct=false para SPEED).
 */
fun KarooSystemService.speedStreamWithStaleness(
    staleThresholdMs: Long = 5_000L
): Flow<StreamState> = flow {
    var lastValue = Double.NaN
    var lastChangeMs = 0L

    streamDataMonitorFlow(DataType.Type.SPEED, applyDistinct = false).collect { state ->
        if (state is StreamState.Streaming) {
            val v = state.dataPoint.singleValue ?: 0.0
            val now = System.currentTimeMillis()
            if (v != lastValue || lastChangeMs == 0L) {
                lastChangeMs = now
                lastValue = v
            }
            val stale = (now - lastChangeMs) > staleThresholdMs && v > 0.0
            if (stale) {
                emit(
                    StreamState.Streaming(
                        DataPoint(DataType.Type.SPEED, mapOf(DataType.Field.SINGLE to 0.0))
                    )
                )
            } else {
                emit(state)
            }
        } else {
            lastValue = Double.NaN
            lastChangeMs = 0L
            emit(state)
        }
    }
}



/**
 * Viento frontal efectivo (m/s, negativo = cola) a partir del heading GPS y la meteo.
 * Se cuantiza a 0.1 m/s + distinctUntilChanged para no despertar el combine() del
 * estimador con micro-variaciones de rumbo: solo emite cuando el viento efectivo
 * cambia de verdad (cambio de rumbo apreciable o meteo nueva).
 */
fun KarooSystemService.headwindFlow(context: Context): Flow<StreamState> =
    getRelativeHeadingFlow(context)
        .combine(context.streamCurrentWeatherData()) { value, data -> StreamData(value, data) }
        .filter { it.weatherResponse != null }
        .map { streamData ->
            val windSpeed = streamData.weatherResponse?.current?.windSpeed ?: 0.0
            val windDirection = (streamData.headingResponse as? HeadingResponse.Value)?.diff ?: 0.0
            round(cos((windDirection + 180) * Math.PI / 180.0) * windSpeed * 10.0) / 10.0
        }
        .distinctUntilChanged()
        .map { headwindSpeed ->
            StreamState.Streaming(
                DataPoint("headwindspeed", mapOf(DataType.Field.SINGLE to headwindSpeed))
            ) as StreamState
        }
        .onStart {
            emit(StreamState.Streaming(
                DataPoint("headwindspeed", mapOf(DataType.Field.SINGLE to 0.0))
            ))
        }
        .catch { e ->
            Timber.e(e, "Error en headwindFlow")
            emit(StreamState.Streaming(
                DataPoint("headwindspeed", mapOf(DataType.Field.SINGLE to 0.0))
            ))
        }


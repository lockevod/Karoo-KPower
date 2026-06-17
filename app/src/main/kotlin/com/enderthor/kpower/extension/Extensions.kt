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
import com.enderthor.kpower.data.OpenWeatherCurrentWeatherResponse
import com.enderthor.kpower.data.HeadwindStats
import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.OpenMeteoData
import com.enderthor.kpower.data.RETRY_CHECK_STREAMS
import com.enderthor.kpower.data.STREAM_TIMEOUT
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
import io.hammerhead.karooext.models.StreamState



import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
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

suspend fun saveComparisonMode(context: Context, enabled: Boolean) {
    context.dataStore.edit { it[comparisonModeKey] = enabled }
}

/** Toggle global (off por defecto): expone campos custom + escribe FIT de comparación. */
fun Context.comparisonModeFlow(): Flow<Boolean> =
    dataStore.data.map { it[comparisonModeKey] ?: false }.distinctUntilChanged()

val recordDynamicsKey = booleanPreferencesKey("recordDynamics")

suspend fun saveRecordDynamics(context: Context, enabled: Boolean) {
    context.dataStore.edit { it[recordDynamicsKey] = enabled }
}

fun Context.recordDynamicsFlow(): Flow<Boolean> =
    dataStore.data.map { it[recordDynamicsKey] ?: false }.distinctUntilChanged()

suspend fun saveAntMeters(context: Context, meters: List<com.enderthor.kpower.ant.SavedMeter>) {
    context.dataStore.edit { it[antMetersKey] = Json.encodeToString(meters) }
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

suspend fun savePreferences(context: Context, configDatas: MutableList<ConfigData>) {
    context.dataStore.edit { t ->
        t[preferencesKey] = Json.encodeToString(configDatas)
    }
}

suspend fun saveStats(context: Context, stats: HeadwindStats) {
    context.dataStore.edit { t ->
        t[statsKey] = Json.encodeToString(stats)
    }
}

suspend fun saveCurrentData(context: Context, forecast: OpenMeteoCurrentWeatherResponse) {
    context.dataStore.edit { t ->
        t[currentDataKey] = Json.encodeToString(forecast)
    }
}

fun KarooSystemService.streamDataFlow(dataTypeId: String): Flow<StreamState> {
    return callbackFlow {
        val listenerId = addConsumer(OnStreamState.StartStreaming(dataTypeId)) { event: OnStreamState ->
            trySendBlocking(event.state)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
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
    }.filterNotNull().distinctUntilChanged().filter { it.current.time * 1000 >= System.currentTimeMillis() - (1000 * 60 * 60 ) }
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



fun Context.parseWeatherResponse(responseString: String): OpenMeteoCurrentWeatherResponse {
    val decoded = try {
        if (responseString.contains("\"current\"")) {
            jsonWithUnknownKeys.decodeFromString<OpenMeteoCurrentWeatherResponse>(responseString)
        } else {
            val weather = jsonWithUnknownKeys.decodeFromString<OpenWeatherCurrentWeatherResponse>(responseString)
            OpenMeteoCurrentWeatherResponse(
                current = OpenMeteoData(
                    windSpeed = weather.wind.speed,
                    windDirection = weather.wind.deg,
                    time = weather.time,
                    interval = 0,
                    temperature = weather.main?.temp,
                    surfacePressure = weather.main?.pressure
                ),
                latitude = weather.coord.lat,
                longitude = weather.coord.lon,
                timezone = "",
                elevation = 0.0,
                utfOffsetSeconds = 0
            )
        }
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid response format parse weather", e)

    }

    return decoded
}


@OptIn(FlowPreview::class)
suspend fun KarooSystemService.makeOpenMeteoHttpRequest(gpsCoordinates: GpsCoordinates, isOpenWeather: Boolean, api: String): HttpResponseState.Complete {
    return callbackFlow {

        val url = if(isOpenWeather && api.trim().isNotEmpty())  "https://api.openweathermap.org/data/2.5/weather?lat=${gpsCoordinates.lat}&lon=${gpsCoordinates.lon}&units=metric&appid=$api"
        else "https://api.open-meteo.com/v1/forecast?latitude=${gpsCoordinates.lat}&longitude=${gpsCoordinates.lon}&current=wind_speed_10m,wind_direction_10m,temperature_2m,surface_pressure&timeformat=unixtime&wind_speed_unit=ms"

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
    }.timeout(20.seconds).catch { e: Throwable ->
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
                    val windMs = if (isImperial) windRaw / 2.2369362920544 else windRaw / 3.6
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
    val a1 = angle1 % 360
    val a2 = angle2 % 360
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
        val listenerId = addConsumer { event: OnLocationChanged ->
            trySendBlocking(event)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
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
    return callbackFlow {
        val listenerId = addConsumer<T> {
            trySend(it)
        }
        awaitClose {
            removeConsumer(listenerId)
        }
    }
}

fun KarooSystemService.streamRideProfile(): Flow<RideProfile> =
    consumerFlow<ActiveRideProfile>().map { it.profile }



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


package com.enderthor.kpower.data


import com.enderthor.kpower.extension.HeadingResponse
import io.hammerhead.karooext.models.StreamState

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json



const val RETRY_CHECK_STREAMS = 4
const val WAIT_STREAMS_LONG = 120000L // 120 seconds
const val WAIT_STREAMS_MEDIUM = 10000L // 10 seconds
const val WAIT_STREAMS_SHORT = 3000L // 3 seconds
const val STREAM_TIMEOUT = 20000L // 20 seconds

// Weather refresh policy (Headwind pattern): fetch when rider moved >= MIN_KM
// OR the last successful fetch is >= MAX_AGE_MS old.
const val WEATHER_MIN_MOVE_KM = 3.0
const val WEATHER_MAX_AGE_MS = 30L * 60L * 1000L     // 30 minutes
const val WEATHER_CHECK_INTERVAL_MS = 60L * 1000L    // tick every minute
const val WEATHER_RETRY_DELAY_MS = 5L * 60L * 1000L  // 5 min after a failure

data class StreamData(val headingResponse: HeadingResponse, val weatherResponse: OpenMeteoCurrentWeatherResponse?)

data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

@Serializable
data class RealKarooValues(
    val speed: StreamState? = null,
    val slope: StreamState? = null,
    val elevation: StreamState? = null,
    val cadence: StreamState? = null,
    val headwind: StreamState? = null,
    val pressure: StreamState? = null,
    val userWeight: StreamState? = null,
)

enum class KarooSurface(
    val surface: String,
    val factor: Double,
) {
    ASPHALT("Asphalt/Concrete", 0.75),
    STANDARD("Standard/Mix/Gravel", 0.93),
    GRAVEL("Mountain Mix", 1.05),
    SAND("Mountain Off Road/Sand", 2.20),
}

enum class TreadType(val baseCrr: Double) {
    SLICK(0.005),
    SEMI_SLICK(0.008),
    KNOBBY(0.012),
}

enum class BikePosition(
    val areaScale: Double,
    val cd: Double,
    val defaultSurface: KarooSurface,
    val defaultTyreWidth: String,
    val defaultTyrePressure: String,
    val defaultTread: TreadType,
) {
    ROAD_HOODS(1.09, 0.80, KarooSurface.ASPHALT, "28", "5.0", TreadType.SLICK),
    ROAD_DROPS(1.00, 0.80, KarooSurface.ASPHALT, "28", "5.0", TreadType.SLICK),
    TT(0.84, 0.72, KarooSurface.ASPHALT, "25", "6.0", TreadType.SLICK),
    GRAVEL(1.12, 0.85, KarooSurface.STANDARD, "40", "3.0", TreadType.SEMI_SLICK),
    MTB(1.45, 0.90, KarooSurface.GRAVEL, "54", "2.0", TreadType.KNOBBY),
}


@Serializable
data class ConfigData(
    val id: Int,
    val name: String,
    val isActive: Boolean,
    val bikeMass: String ,
    val rollingResistanceCoefficient: String,
    val dragCoefficient: String,
    val frontalArea: String,
    val powerLoss: String ,
    val headwindconf: String,
    val isOpenWeather: Boolean,
    val apikey: String,
    val ftp: String ,
    val surface: KarooSurface = KarooSurface.STANDARD,
    val isforcepower: Boolean = false,
    val bikePosition: BikePosition = BikePosition.ROAD_HOODS,
    val riderHeight: String = "",
    val tyreWidth: String = "28",
    val tyrePressure: String = "5.0",
    val treadType: TreadType = TreadType.SLICK,
    // Defaults pensados para RETROCOMPATIBILIDAD: una config antigua (sin estos
    // campos en el JSON) se deserializa con useProfileFtp=false (sigue usando el
    // FTP que el usuario configuró) y simpleMode=false (se muestran TODOS los campos
    // como antes, sin esconder Crr/Cd/área). Las configs NUEVAS se crean desde
    // previewConfigData, que sí activa simpleMode/useProfileFtp.
    val useProfileFtp: Boolean = false,
    val simpleMode: Boolean = false,
    val useKarooTemp: Boolean = false
)


//val previewConfigData = listOf(ConfigData(0,"default", true, "14.0","0.0095","0.8","0.9","2.2","0.0", false, "","200", KarooSurface.STANDARD,false))
// Plantilla para configs NUEVAS: estrena modo simple y FTP del perfil (las configs
// antiguas migradas usan los defaults retrocompatibles del data class).
val previewConfigData = listOf(ConfigData(0,"Spark", true, "14.0","0.008","0.85","0.42","2.5","0.0", false, "","257", KarooSurface.GRAVEL, false, simpleMode = true, useProfileFtp = true))
val defaultConfigData = Json.encodeToString(previewConfigData)

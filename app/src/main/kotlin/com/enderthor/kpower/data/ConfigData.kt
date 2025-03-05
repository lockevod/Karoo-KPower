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
    ASPHALT("Asphalt/Concrete", 0.75),  //0.8
    STANDARD("Standard/Mix/Gravel", 0.93), //1.0
    GRAVEL("Mountain Mix", 1.05), //1.17
    SAND("Mountain Off Road/Sand", 2.20), //2.50
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
    val isforcepower: Boolean = false
    )


//val previewConfigData = listOf(ConfigData(0,"default", true, "14.0","0.0095","0.8","0.9","2.2","0.0", false, "","200", KarooSurface.STANDARD,false))
val previewConfigData = listOf(ConfigData(0,"Spark", true, "14.0","0.0095","0.8","0.9","2.2","0.0", true, "cde014c2fab1dbabe2387e57f3ecc051","257", KarooSurface.GRAVEL,false))
val defaultConfigData = Json.encodeToString(previewConfigData)

package com.enderthor.kpower.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


@Serializable
data class OpenMeteoData(
    val time: Long,
    val interval: Int,
    @SerialName("wind_speed_10m") val windSpeed: Double,
    @SerialName("wind_direction_10m") val windDirection: Double,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("surface_pressure") val surfacePressure: Double? = null,
)


@Serializable
data class OpenMeteoCurrentWeatherResponse(
    val current: OpenMeteoData,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val elevation: Double,
    @SerialName("utc_offset_seconds") val utfOffsetSeconds: Int
)

@Serializable
data class HeadwindStats(
    val lastSuccessfulWeatherRequest: Long? = null,
    val lastSuccessfulWeatherPosition: GpsCoordinates? = null,
    val failedWeatherRequest: Long? = null,
){
    companion object {
        val defaultStats = Json.encodeToString(HeadwindStats())
    }
}
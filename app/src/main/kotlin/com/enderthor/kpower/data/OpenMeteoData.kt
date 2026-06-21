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

/** One surface's calibrated effective Crr + its std error. crrEff null + sufficient=false when too few
 *  samples; reliable=false when the fit's relative std error is too high (poorly identified). */
@Serializable
data class SurfaceCrrSuggestion(
    val surface: String,      // KarooSurface.name
    val crrEff: Double? = null,
    val crrSe: Double? = null,
    val samples: Long = 0,
    val sufficient: Boolean = false,
    val reliable: Boolean = false,
)

/** Field-calibration suggestion (CdA + per-surface effective Crr fitted from a comparison ride's
 *  real-meter data), persisted so the UI can offer it after the ride (the extension process may be
 *  killed at ride end). [cdaSe]/[crrSe] are the coefficient std errors — the honest identifiability
 *  metric; [reliable] flags mean "well-identified and in range", gating Apply. */
@Serializable
data class CalibrationSuggestion(
    val cda: Double,
    val cdaSe: Double,
    val cdaReliable: Boolean,
    val perSurface: List<SurfaceCrrSuggestion> = emptyList(),
    val samples: Long,
    val bikeId: Int,          // the bike (ConfigData.id) active when it was computed
    val timestampMs: Long,
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
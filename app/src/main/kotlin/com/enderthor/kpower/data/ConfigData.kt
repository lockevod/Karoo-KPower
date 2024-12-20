package com.enderthor.kpower.data


import io.hammerhead.karooext.models.StreamState

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    ASPHALT("Asphalt/Concrete", 0.8),
    STANDARD("Standard/Mix/Gravel", 1.0),
    GRAVEL("Mountain Mix", 1.17),
    SAND("Mountain Off Road/Sand", 2.50),
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
    val surface: KarooSurface,
    )

val defaultConfigData = Json.encodeToString(listOf(ConfigData(0,"default", true, "14.0","0.0095","0.8","0.9","2.2","0.0", true, "cde014c2fab1dbabe2387e57f3ecc051","257", KarooSurface.STANDARD)))
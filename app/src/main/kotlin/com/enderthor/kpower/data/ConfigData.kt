package com.enderthor.kpower.data

import io.hammerhead.karooext.models.StreamState

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RealKarooValues(
    val speed: StreamState? = null ,
    val slope: StreamState? = null,
    val elevation: StreamState? = null,
    val headwind: StreamState? = null,
    val pressure: StreamState? = null,
)

@Serializable
class ConfigData(
    var id: Int,
    var name: String,
    var isActive: Boolean,
    var bikeMass: String ,
    var rollingResistanceCoefficient: String,
    var dragCoefficient: String,
    var frontalArea: String,
    var powerLoss: String ,
    var headwind: String,
    )

val defaultConfigData = Json.encodeToString(listOf(ConfigData(0,"default", true, "14.0","0.0095","0.8","0.9","2.2","0.0")))

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

// Gating del recálculo de superficie: reclasifica solo si te has movido >= MIN_MOVE_M
// y como mucho cada MIN_INTERVAL_MS (la superficie cambia despacio; el coste real es leer
// el mapfile, así que limitamos la frecuencia).
const val SURFACE_MIN_MOVE_M = 25.0
const val SURFACE_MIN_INTERVAL_MS = 7_000L
// Caducidad de la clasificación de superficie: si la última reclasificación con éxito es
// más vieja que esto (GPS perdido en túnel, o parado mucho rato), se descarta y se vuelve
// al preset en vez de arrastrar una superficie obsoleta el resto de la ruta.
const val SURFACE_MAX_AGE_MS = 120_000L
const val WEATHER_MAX_AGE_MS = 30L * 60L * 1000L     // 30 minutes
const val WEATHER_CHECK_INTERVAL_MS = 60L * 1000L    // tick every minute
const val WEATHER_RETRY_DELAY_MS = 5L * 60L * 1000L  // 5 min after a failure

data class StreamData(val headingResponse: HeadingResponse, val weatherResponse: OpenMeteoCurrentWeatherResponse?)

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

// tubelessFactor: reducción de Crr al montar tubeless vs cámara, según datos de
// bicyclerollingresistance.com (mismo neumático, 42.5 kg / ~29 km/h):
//   carretera 0.2–3.8 W (~10%), gravel media 5.1 W (~13%), MTB 10–15 W (~20%).
enum class TreadType(val baseCrr: Double, val label: String, val tubelessFactor: Double) {
    SLICK(0.005, "Road (slick)", 0.90),
    SEMI_SLICK(0.008, "Gravel (semi-slick)", 0.87),
    KNOBBY(0.012, "MTB (knobby)", 0.80),
}

enum class BikePosition(
    val areaScale: Double,
    val cd: Double,
    val defaultSurface: KarooSurface,
    val defaultTyreWidth: String,
    val defaultTyrePressure: String,
    val defaultTread: TreadType,
    val label: String,
) {
    ROAD_HOODS(1.09, 0.80, KarooSurface.ASPHALT, "28", "5.0", TreadType.SLICK, "Road – hoods"),
    ROAD_DROPS(1.00, 0.80, KarooSurface.ASPHALT, "28", "5.0", TreadType.SLICK, "Road – drops"),
    TT(0.84, 0.72, KarooSurface.ASPHALT, "25", "6.0", TreadType.SLICK, "Time trial"),
    GRAVEL(1.12, 0.85, KarooSurface.STANDARD, "40", "3.0", TreadType.SEMI_SLICK, "Gravel"),
    MTB(1.45, 0.90, KarooSurface.GRAVEL, "2.3", "2.0", TreadType.KNOBBY, "MTB"),
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
    val useKarooTemp: Boolean = false,
    val tubeless: Boolean = false,
    // Si Headwind (de.timklge.karooheadwind) está instalado, KPower toma de su stream
    // la temperatura, presión y viento en vez de pedir su propia API meteo (evita el
    // doble polling). Default true = automático; el usuario puede forzar la meteo propia
    // desde la UI. Retrocompat: una config antigua se deserializa con true (auto).
    val preferHeadwind: Boolean = true,
    // Detección de superficie en vivo leyendo los mapfiles offline (OSM surface/tracktype)
    // bajo la posición actual. Activado por defecto; si no hay mapfiles/permiso/datos,
    // degrada a "Unknown" y se usa la superficie elegida abajo. Retrocompat: una config
    // antigua se deserializa con true.
    val useRouteSurface: Boolean = true
)


//val previewConfigData = listOf(ConfigData(0,"default", true, "14.0","0.0095","0.8","0.9","2.2","0.0", false, "","200", KarooSurface.STANDARD,false))
// Plantilla para configs NUEVAS: estrena modo simple y FTP del perfil (las configs
// antiguas migradas usan los defaults retrocompatibles del data class).
val previewConfigData = listOf(ConfigData(0,"Spark", true, "14.0","0.008","0.85","0.42","2.5","0.0", false, "","257", KarooSurface.GRAVEL, false, simpleMode = true, useProfileFtp = true))
val defaultConfigData = Json.encodeToString(previewConfigData)

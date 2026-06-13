package com.enderthor.kpower.surface

import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.KarooSurface

/**
 * Mapea las tags OSM `surface`/`tracktype` a una de las 4 superficies que KPower
 * ya tiene (`KarooSurface`). Devuelve null si las tags no aportan información:
 * la decisión "way encontrado pero sin tag -> Paved" la toma quien llama, vía
 * [classifyFoundWay] (un way mapeado sin surface es asfalto por convención OSM),
 * distinto de "no hay way -> Unknown".
 */
object SurfaceTagClassifier {
    private val pavedSurfaces = setOf(
        "asphalt", "concrete", "concrete:plates", "concrete:lanes",
        "paving_stones", "chipseal", "metal"
    )
    private val compactedSurfaces = setOf("compacted", "fine_gravel")
    private val gravelSurfaces = setOf(
        "unpaved", "dirt", "ground", "gravel", "pebblestone", "cobblestone",
        "sett", "unhewn_cobblestone", "rock", "rocks", "stone", "grass_paver",
        "clay", "woodchips", "salt", "wood"
    )
    private val looseSurfaces = setOf("grass", "sand", "mud", "snow", "ice")

    /** Tags -> KarooSurface, o null si no hay información reconocible. surface gana a tracktype. */
    fun classify(surface: String?, tracktype: String?): KarooSurface? {
        val s = surface?.lowercase()?.trim()
        when (s) {
            in looseSurfaces -> return KarooSurface.SAND
            in gravelSurfaces -> return KarooSurface.GRAVEL
            in compactedSurfaces -> return KarooSurface.STANDARD
            in pavedSurfaces -> return KarooSurface.ASPHALT
        }
        return when (tracktype?.lowercase()?.trim()) {
            "grade1" -> KarooSurface.ASPHALT
            "grade2" -> KarooSurface.STANDARD
            "grade3" -> KarooSurface.GRAVEL
            "grade4", "grade5" -> KarooSurface.SAND
            else -> null
        }
    }

    /** Para un way ENCONTRADO cerca: si las tags no dicen nada, se asume Paved. */
    fun classifyFoundWay(surface: String?, tracktype: String?): KarooSurface =
        classify(surface, tracktype) ?: KarooSurface.ASPHALT
}

/**
 * Superficie a aplicar en el cálculo: si la feature está activa y hay clasificación
 * en vivo, manda la viva; si no (Unknown o feature off), se respeta el preset.
 */
fun effectiveSurface(config: ConfigData, liveSurface: KarooSurface?): KarooSurface =
    if (config.useRouteSurface) (liveSurface ?: config.surface) else config.surface

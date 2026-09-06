package com.enderthor.kpower.surface

import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.KarooSurface

/**
 * Mapea las tags OSM (`surface`/`tracktype`/`highway`) a una de las 4 superficies que
 * KPower ya tiene (`KarooSurface`). El consumidor solo debe pasar ways de carretera
 * (con `highway`); las áreas/ríos/vías no-highway se filtran antes.
 */
object SurfaceTagClassifier {
    private val pavedSurfaces = setOf(
        "asphalt", "concrete", "concrete:plates", "concrete:lanes",
        "paving_stones", "paved", "chipseal", "metal"
    )
    private val compactedSurfaces = setOf("compacted", "fine_gravel")
    private val gravelSurfaces = setOf(
        "unpaved", "dirt", "ground", "earth", "gravel", "pebblestone", "cobblestone",
        "sett", "unhewn_cobblestone", "rock", "rocks", "stone", "grass_paver",
        "clay", "woodchips", "salt", "wood"
    )
    private val looseSurfaces = setOf("grass", "sand", "mud", "snow", "ice")

    // Clases de `highway` que por convención OSM son asfalto cuando no hay tag `surface`.
    private val pavedHighways = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
        "residential", "living_street", "service", "road", "pedestrian", "cycleway",
        "busway", "motorway_link", "trunk_link", "primary_link", "secondary_link",
        "tertiary_link"
    )

    /** Tags de superficie -> KarooSurface, o null si no hay info reconocible. surface gana a tracktype. */
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

    /**
     * Clasifica un way de carretera (ya filtrado a `highway != null`). Si tiene tag de
     * superficie reconocible, manda esa. Si no, default por clase de highway:
     * carreteras pavimentadas -> ASPHALT; `track` -> STANDARD (compactado por convención);
     * resto ambiguo (path/footway/bridleway/steps/…) -> null = Unknown (se respeta el preset).
     */
    fun classifyWay(surface: String?, tracktype: String?, highway: String?): KarooSurface? {
        classify(surface, tracktype)?.let { return it }
        return when (highway?.lowercase()?.trim()) {
            in pavedHighways -> KarooSurface.ASPHALT
            "track" -> KarooSurface.STANDARD
            else -> null
        }
    }
}

/**
 * Superficie a aplicar en el cálculo: si la feature está activa y hay clasificación
 * en vivo, manda la viva; si no (Unknown o feature off), se respeta el preset.
 */
fun effectiveSurface(config: ConfigData, liveSurface: KarooSurface?): KarooSurface =
    if (config.useRouteSurface) (liveSurface ?: config.surface) else config.surface

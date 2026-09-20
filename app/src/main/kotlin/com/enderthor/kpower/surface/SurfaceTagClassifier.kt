package com.enderthor.kpower.surface

import com.enderthor.kpower.data.ConfigData
import com.enderthor.kpower.data.KarooSurface

/**
 * Mapea las tags OSM (`surface`/`tracktype`/`highway`) a una de las 4 superficies que
 * KPower ya tiene (`KarooSurface`). El consumidor solo debe pasar ways de carretera
 * (con `highway`); las áreas/ríos/vías no-highway se filtran antes.
 */
object SurfaceTagClassifier {
    // OpenAndroMaps NO usa el vocabulario OSM crudo: su tag-transform lo reduce a un juego propio
    // en el que aparecen `raw`, `smooth_paved` y `rough_paved`. Sin ellos, el mapa OAM -que es el
    // que el ciclista mantiene actualizado- pierde su etiqueta mas frecuente y cae al fallback por
    // tipo de via. Medido sobre la salida del 2026-09-20 (640 puntos, ambos mapas): 32 puntos se
    // quedaban en Unknown -> preset justo donde spain.map decia GRAVEL.
    // El destino de `raw` no es una suposicion: cruzando las MISMAS vias contra spain.map, 66 de 72
    // correspondencias son `ground` y 6 son `dirt`, y los dos ya estaban en gravelSurfaces.
    // `smooth_paved` y `rough_paved` son los dos el cubo de asphalt/concrete: pese al nombre,
    // las 7 vias que llevan `rough_paved` en la zona son todas `living_street`, calle pavimentada.
    // No se tratan como cobblestone/sett aunque el nombre lo sugiera.
    private val pavedSurfaces = setOf(
        "asphalt", "concrete", "concrete:plates", "concrete:lanes",
        "paving_stones", "paved", "chipseal", "metal",
        "smooth_paved", "rough_paved"                    // OAM
    )
    private val compactedSurfaces = setOf("compacted", "fine_gravel")
    private val gravelSurfaces = setOf(
        "unpaved", "dirt", "ground", "earth", "gravel", "pebblestone", "cobblestone",
        "sett", "unhewn_cobblestone", "rock", "rocks", "stone", "grass_paver",
        "clay", "woodchips", "salt", "wood",
        "raw"                                            // OAM
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

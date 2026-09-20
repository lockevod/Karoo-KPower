package com.enderthor.kpower.surface

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import com.enderthor.kpower.data.KarooSurface
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Tile
import org.mapsforge.map.reader.MapFile
import timber.log.Timber
import java.io.File

/**
 * Dado (lat,lon) devuelve la KarooSurface bajo el ciclista, o null = Unknown
 * (sin mapfile de la zona / fuera de vía / sin permiso) -> el llamador mantiene el preset.
 *
 * Barato por diseño:
 *  - Escaneo de mapfiles lazy y refrescado solo cada SCAN_INTERVAL_MS.
 *  - Reader MapFile abierto y cacheado (cap MAX_OPEN_READERS).
 *  - readMapData solo al cambiar de tile (zoom 16 ~ 600 m).
 *
 * Se invoca desde un único coroutine colector (sin concurrencia interna).
 */
class SurfaceConditionReader(private val context: Context) {

    private data class MapFileInfo(val file: File, val box: BoundingBox)
    private class WaySnapshot(
        val segments: Array<Array<org.mapsforge.core.model.LatLong>>,
        val surface: String?,
        val tracktype: String?,
        val highway: String?,
    )

    private var knownMapfiles: List<MapFileInfo>? = null
    private var lastScanMs = 0L
    // Cuantos .map habia en el directorio en el ultimo escaneo. Distingue un exito COMPLETO
    // (abrieron todos) de uno PARCIAL, que si hay que reintentar.
    private var lastCandidateCount = 0
    // access-order LRU (true) so trimming evicts the LEAST-RECENTLY-USED reader, not the oldest-inserted —
    // otherwise a point covered by overlapping mapfiles could evict the hot one and reopen it (header
    // parse = real I/O) every tile.
    private val openReaders = LinkedHashMap<File, MapFile>(8, 0.75f, true)
    private var cachedTileKey: Long = Long.MIN_VALUE
    private var cachedWays: List<WaySnapshot> = emptyList()

    @Synchronized fun classifyAt(lat: Double, lon: Double): KarooSurface? {
        ensureMapfiles()
        val covering = knownMapfiles?.filter { it.box.contains(lat, lon) }?.map { it.file }.orEmpty()
        if (covering.isEmpty()) return null

        val (tx, ty) = TileUtils.locationToTileXY(lat, lon, ZOOM)
        val tileKey = (tx.toLong() shl 32) or (ty.toLong() and 0xffffffffL)
        if (tileKey != cachedTileKey) {
            cachedWays = readWays(covering, tx, ty)
            cachedTileKey = tileKey
        }

        var best: WaySnapshot? = null
        var bestDist = Double.MAX_VALUE
        for (way in cachedWays) {
            for (segment in way.segments) {
                for (i in 1 until segment.size) {
                    val a = segment[i - 1]; val b = segment[i]
                    val d = GeoUtils.distancePointToSegmentMeters(
                        lat, lon, a.latitude, a.longitude, b.latitude, b.longitude
                    )
                    if (d < bestDist) { bestDist = d; best = way }
                }
            }
        }

        if (best == null || bestDist > MAX_DIST_M) return null
        return SurfaceTagClassifier.classifyWay(best.surface, best.tracktype, best.highway)
    }

    private fun ensureMapfiles() {
        // Una vez encontrados TODOS, no reescanear durante la ruta (los .map no aparecen a mitad);
        // close() resetea knownMapfiles=null, así que una reconexión sí reescanea.
        //
        // "TODOS" es la palabra que importa. Antes bastaba con que la lista no estuviese vacia, asi
        // que con dos mapas y uno fallando justo en el escaneo (lo estan reemplazando, se esta
        // descargando), el que fallo se caia de la lista y NO se reintentaba en toda la sesion: las
        // zonas cubiertas solo por el quedaban en Unknown para siempre. El reintento de 5 min no lo
        // salvaba porque solo corria con la lista vacia del todo. Un fallo transitorio se volvia
        // permanente.
        val known = knownMapfiles
        if (known != null && known.isNotEmpty() && known.size == lastCandidateCount) return
        val now = System.currentTimeMillis()
        if (known != null && now - lastScanMs < SCAN_INTERVAL_MS) return
        lastScanMs = now

        // Registrar el MOTIVO de una lista vacia. Sin esto los cuatro caminos que devuelven null
        // (sin permiso / sin directorio / punto fuera de cobertura / lejos de toda via) escriben la
        // misma linea `-> Unknown(->preset)` en el log del llamante, y una feature MUERTA es
        // indistinguible de una marcha legitimamente campo a traves: el 2026-09-12 estuvo 1.138 de
        // 1.138 muestras sin permiso y el log no lo dijo en ninguna linea. Corre una vez por
        // escaneo, no por muestra. Nunca el NOMBRE ni la RUTA: el mapfile se llama como la region
        // del ciclista y este log se sube.
        if (!hasStoragePermission()) {
            Timber.w("Surface: no storage permission -> live surface OFF, preset in use")
            knownMapfiles = emptyList(); return
        }

        val dir = File(File(Environment.getExternalStorageDirectory(), "offline"), "maps")
        if (!dir.exists() || !dir.isDirectory) {
            Timber.w("Surface: no offline map directory -> live surface OFF, preset in use")
            knownMapfiles = emptyList(); return
        }

        // Tres causas distintas producian el MISMO `0 candidates`, y por eso la feature ha muerto
        // varias veces sin que el log lo dijera. Se listan sin filtro y se separan:
        //   raw == null      -> opendir fallo. La causa tipica es DAC: falta el gid sdcard_rw que
        //                       /offline exige. Pero listFiles() TIRA el errno, asi que tambien
        //                       cabe EMFILE, ENOMEM o EIO. No se prescribe una cura: se dice lo
        //                       que se ha visto y el comprobante que lo distingue.
        //   raw vacio        -> el directorio se lee y esta vacio de verdad.
        //   raw sin .map     -> hay ficheros pero ninguno casa (.map.gz, mapas un nivel mas
        //                       abajo). Hay que mover los mapas, no tocar permisos.
        val raw = dir.listFiles()
        val files = raw.orEmpty().filter { it.isFile && it.extension.equals("map", true) }
            .toTypedArray()
        lastCandidateCount = files.size
        when {
            raw == null -> Timber.w(
                "Surface: permission granted but listFiles() failed -> check that the process " +
                    "has gid 1015 (sdcard_rw): grep ^Groups /proc/<pid>/status"
            )
            raw.isEmpty() -> Timber.w("Surface: map dir is readable but empty")
            files.isEmpty() -> Timber.w("Surface: %d entries in map dir, none .map", raw.size)
        }
        // Never log the NAME: mapfiles are named after their region ("catalunya.map") and this log is
        // uploaded, so a filename is a coarse home location. Size identifies which file (stably, unlike
        // a list index) without naming it. The THROWABLE is dropped for the same reason — mapsforge's
        // MapFileException and FileNotFoundException both embed the full path in their message, and
        // FileLogTree appends the stack trace verbatim.
        knownMapfiles = files.mapNotNull { file ->
            try {
                val mf = MapFile(file)
                try { MapFileInfo(file, mf.mapFileInfo.boundingBox) } finally { mf.close() }
            } catch (e: Exception) {
                Timber.e("Surface: cannot read mapfile (%d MB): %s", file.length() shr 20, e.javaClass.simpleName)
                null
            }
        }
        if (knownMapfiles.isNullOrEmpty()) {
            Timber.w("Surface: no readable mapfiles (%d candidates) -> preset in use", files.size)
        } else {
            val readable = knownMapfiles?.size ?: 0
            Timber.i("Surface: %d mapfile(s) readable -> live surface ON", readable)
            if (readable < files.size) {
                Timber.w(
                    "Surface: only %d of %d mapfiles opened -> retrying the rest every %d min",
                    readable, files.size, SCAN_INTERVAL_MS / 60_000L
                )
            }
        }
    }

    private fun readWays(files: List<File>, tx: Int, ty: Int): List<WaySnapshot> {
        val out = ArrayList<WaySnapshot>()
        val tile = Tile(tx, ty, ZOOM.toByte(), 256)
        for (file in files) {
            try {
                val reader = openReaders.getOrPut(file) { MapFile(file) }
                // null aqui NO es "no hay vias": mapsforge aborta la consulta entera y devuelve null
                // si un bloque supera Parameters.MAXIMUM_BUFFER_SIZE (10 MB), y solo lo cuenta por
                // java.util.logging, que no pasa por Timber ni acaba en el log que sube el ciclista.
                // Medido el 2026-09-20 sobre spain.map y el OAM de 2,2 GB en la ruta entera: 0 nulls
                // y 0 avisos, asi que NO se toca el limite. Pero si un mapa nuevo lo provoca, el
                // sintoma seria otra vez Unknown durante toda la marcha sin una sola linea. Una linea.
                val result = reader.readMapData(tile)
                if (result == null) {
                    Timber.w(
                        "Surface: readMapData returned null for mapfile (%d MB) at tile %d/%d",
                        file.length() shr 20, tx, ty
                    )
                    continue
                }
                for (way in result.ways) {
                    val tags = way.tags
                    val highway = tags.find { it.key.equals("highway", true) }?.value?.lowercase()
                        ?: continue
                    out.add(
                        WaySnapshot(
                            segments = way.latLongs,
                            surface = tags.find { it.key.equals("surface", true) }?.value?.lowercase(),
                            tracktype = tags.find { it.key.equals("tracktype", true) }?.value?.lowercase(),
                            highway = highway,
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.e("Surface: readMapData failed for mapfile (%d MB): %s", file.length() shr 20, e.javaClass.simpleName)
                runCatching { openReaders.remove(file)?.close() }
            }
        }
        trimOpenReaders()
        return out
    }

    private fun trimOpenReaders() {
        while (openReaders.size > MAX_OPEN_READERS) {
            val eldest = openReaders.entries.iterator().next()
            runCatching { eldest.value.close() }
            openReaders.remove(eldest.key)
        }
    }

    // WRITE, no READ, y NO escribimos nada: es el unico permiso que concede el gid sdcard_rw
    // que /offline exige. Ver AndroidManifest.xml antes de "simplificarlo" a READ.
    // Conceder NO exige reiniciar a mano: al cambiar los gids el sistema mata el uid
    // (killUid GIDS_CHANGED) y el proceso re-forkea ya con 1015. Comprobado en el Karoo el
    // 2026-09-20: el pid murio a los 2 s del grant y volvio con el gid puesto.
    // ponytail: valido mientras el target siga en 28 y el Karoo en API 32. En API 33+
    // WRITE_EXTERNAL_STORAGE ya no se concede y esto seria siempre false, con un log enganyoso
    // ("no storage permission"); para entonces hara falta SAF, que es la salida anotada en
    // app/build.gradle.kts. No se construye hoy para un dispositivo que no existe.
    private fun hasStoragePermission(): Boolean =
        context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized fun close() {
        openReaders.values.forEach { runCatching { it.close() } }
        openReaders.clear()
        knownMapfiles = null
        lastScanMs = 0L
        lastCandidateCount = 0
        cachedTileKey = Long.MIN_VALUE
        cachedWays = emptyList()
    }

    companion object {
        private const val ZOOM = 16
        private const val MAX_DIST_M = 30.0
        private const val SCAN_INTERVAL_MS = 5L * 60L * 1000L
        private const val MAX_OPEN_READERS = 4   // ≥ typical overlapping-mapfile count so we don't thrash
    }
}

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
    private data class WaySnapshot(
        val segments: Array<Array<org.mapsforge.core.model.LatLong>>,
        val surface: String?,
        val tracktype: String?,
    ) {
        override fun equals(other: Any?) = other is WaySnapshot && segments.contentDeepEquals(other.segments) && surface == other.surface && tracktype == other.tracktype
        override fun hashCode() = segments.contentDeepHashCode() * 31 + surface.hashCode() * 31 + tracktype.hashCode()
    }

    private var knownMapfiles: List<MapFileInfo>? = null
    private var lastScanMs = 0L
    private val openReaders = LinkedHashMap<File, MapFile>()
    private var cachedTileKey: Long = Long.MIN_VALUE
    private var cachedWays: List<WaySnapshot> = emptyList()

    fun classifyAt(lat: Double, lon: Double): KarooSurface? {
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
        return SurfaceTagClassifier.classifyFoundWay(best.surface, best.tracktype)
    }

    private fun ensureMapfiles() {
        val now = System.currentTimeMillis()
        if (knownMapfiles != null && now - lastScanMs < SCAN_INTERVAL_MS) return
        lastScanMs = now

        if (!hasReadPermission()) { knownMapfiles = emptyList(); return }

        val dir = File(File(Environment.getExternalStorageDirectory(), "offline"), "maps")
        if (!dir.exists() || !dir.isDirectory) { knownMapfiles = emptyList(); return }

        val files = dir.listFiles { f -> f.isFile && f.extension.equals("map", true) } ?: emptyArray()
        knownMapfiles = files.mapNotNull { file ->
            try {
                val mf = MapFile(file)
                try { MapFileInfo(file, mf.mapFileInfo.boundingBox) } finally { mf.close() }
            } catch (e: Exception) {
                Timber.e(e, "Surface: cannot read mapfile ${file.name}")
                null
            }
        }
    }

    private fun readWays(files: List<File>, tx: Int, ty: Int): List<WaySnapshot> {
        val out = ArrayList<WaySnapshot>()
        val tile = Tile(tx, ty, ZOOM.toByte(), 256)
        for (file in files) {
            try {
                val reader = openReaders.getOrPut(file) { MapFile(file) }
                val result = reader.readMapData(tile) ?: continue
                for (way in result.ways) {
                    val tags = way.tags
                    out.add(
                        WaySnapshot(
                            segments = way.latLongs,
                            surface = tags.find { it.key.equals("surface", true) }?.value?.lowercase(),
                            tracktype = tags.find { it.key.equals("tracktype", true) }?.value?.lowercase(),
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Surface: readMapData failed for ${file.name}")
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

    private fun hasReadPermission(): Boolean =
        context.checkCallingOrSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun close() {
        openReaders.values.forEach { runCatching { it.close() } }
        openReaders.clear()
        knownMapfiles = null
        lastScanMs = 0L
        cachedTileKey = Long.MIN_VALUE
        cachedWays = emptyList()
    }

    companion object {
        private const val ZOOM = 16
        private const val MAX_DIST_M = 30.0
        private const val SCAN_INTERVAL_MS = 5L * 60L * 1000L
        private const val MAX_OPEN_READERS = 2
    }
}

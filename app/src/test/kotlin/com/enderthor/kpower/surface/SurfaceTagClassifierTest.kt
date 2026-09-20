package com.enderthor.kpower.surface

import com.enderthor.kpower.data.KarooSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SurfaceTagClassifierTest {
    @Test fun `asphalt is paved`() =
        assertEquals(KarooSurface.ASPHALT, SurfaceTagClassifier.classify("asphalt", null))

    @Test fun `compacted maps to standard`() =
        assertEquals(KarooSurface.STANDARD, SurfaceTagClassifier.classify("compacted", null))

    @Test fun `gravel maps to gravel`() =
        assertEquals(KarooSurface.GRAVEL, SurfaceTagClassifier.classify("gravel", null))

    @Test fun `cobblestone maps to gravel`() =
        assertEquals(KarooSurface.GRAVEL, SurfaceTagClassifier.classify("cobblestone", null))

    @Test fun `sand maps to loose`() =
        assertEquals(KarooSurface.SAND, SurfaceTagClassifier.classify("sand", null))

    @Test fun `surface wins over tracktype`() =
        assertEquals(KarooSurface.ASPHALT, SurfaceTagClassifier.classify("asphalt", "grade5"))

    @Test fun `tracktype used only when surface unknown`() {
        assertEquals(KarooSurface.STANDARD, SurfaceTagClassifier.classify(null, "grade2"))
        assertEquals(KarooSurface.GRAVEL, SurfaceTagClassifier.classify(null, "grade3"))
        assertEquals(KarooSurface.SAND, SurfaceTagClassifier.classify(null, "grade5"))
        assertEquals(KarooSurface.ASPHALT, SurfaceTagClassifier.classify(null, "grade1"))
    }

    @Test fun `unrecognized tags return null`() =
        assertNull(SurfaceTagClassifier.classify("banana", "gradeX"))

    @Test fun `case and whitespace are normalized`() =
        assertEquals(KarooSurface.SAND, SurfaceTagClassifier.classify("  SAND ", null))

    @Test fun `classify returns null when nothing recognized`() {
        assertNull(SurfaceTagClassifier.classify(null, null))
        assertNull(SurfaceTagClassifier.classify("banana", "gradeX"))
    }

    @Test fun `earth maps to gravel`() =
        assertEquals(KarooSurface.GRAVEL, SurfaceTagClassifier.classify("earth", null))

    @Test fun `paved surface maps to asphalt`() =
        assertEquals(KarooSurface.ASPHALT, SurfaceTagClassifier.classify("paved", null))

    @Test fun `classifyWay surface tag wins over highway default`() =
        assertEquals(KarooSurface.GRAVEL, SurfaceTagClassifier.classifyWay("gravel", null, "track"))

    @Test fun `classifyWay tracktype wins over highway default`() =
        assertEquals(KarooSurface.SAND, SurfaceTagClassifier.classifyWay(null, "grade4", "track"))

    @Test fun `classifyWay paved highway with no surface tag is asphalt`() =
        assertEquals(KarooSurface.ASPHALT, SurfaceTagClassifier.classifyWay(null, null, "residential"))

    @Test fun `classifyWay tagless track is standard`() =
        assertEquals(KarooSurface.STANDARD, SurfaceTagClassifier.classifyWay(null, null, "track"))

    @Test fun `classifyWay ambiguous tagless highway is unknown`() {
        assertNull(SurfaceTagClassifier.classifyWay(null, null, "path"))
        assertNull(SurfaceTagClassifier.classifyWay(null, null, "footway"))
        assertNull(SurfaceTagClassifier.classifyWay(null, null, null))
    }

    // Vocabulario propio de OpenAndroMaps. El caso que importaba: `raw` sobre un `path` sin
    // tracktype caia en Unknown -> preset, y es la etiqueta mas frecuente del OAM (32 puntos de
    // la salida del 2026-09-20). El cruce contra spain.map da `ground`/`dirt` para esas vias.
    @Test fun `OAM raw maps to gravel like the ground and dirt it stands for`() =
        assertEquals(KarooSurface.GRAVEL, SurfaceTagClassifier.classify("raw", null))

    @Test fun `OAM smooth_paved maps to asphalt`() =
        assertEquals(KarooSurface.ASPHALT, SurfaceTagClassifier.classify("smooth_paved", null))

    // rough_paved es calle pavimentada pese al nombre: las que lo llevan son living_street.
    @Test fun `OAM rough_paved maps to asphalt`() =
        assertEquals(KarooSurface.ASPHALT, SurfaceTagClassifier.classify("rough_paved", null))

    // La regresion concreta: sin `raw` esto devolvia null y se usaba el preset.
    @Test fun `OAM raw on a tagless path is no longer unknown`() =
        assertEquals(KarooSurface.GRAVEL, SurfaceTagClassifier.classifyWay("raw", null, "path"))
}

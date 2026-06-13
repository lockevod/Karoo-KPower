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

    @Test fun `no surface tag means null from classify but paved from classifyFoundWay`() {
        assertNull(SurfaceTagClassifier.classify(null, null))
        assertEquals(KarooSurface.ASPHALT, SurfaceTagClassifier.classifyFoundWay(null, null))
    }

    @Test fun `case and whitespace are normalized`() =
        assertEquals(KarooSurface.SAND, SurfaceTagClassifier.classify("  SAND ", null))
}

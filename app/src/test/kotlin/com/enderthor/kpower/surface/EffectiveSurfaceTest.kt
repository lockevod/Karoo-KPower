package com.enderthor.kpower.surface

import com.enderthor.kpower.data.KarooSurface
import com.enderthor.kpower.data.previewConfigData
import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveSurfaceTest {
    private val base = previewConfigData.first().copy(surface = KarooSurface.ASPHALT)

    @Test fun `live surface overrides preset when feature on`() {
        val cfg = base.copy(useRouteSurface = true)
        assertEquals(KarooSurface.GRAVEL, effectiveSurface(cfg, KarooSurface.GRAVEL))
    }

    @Test fun `unknown keeps preset when feature on`() {
        val cfg = base.copy(useRouteSurface = true)
        assertEquals(KarooSurface.ASPHALT, effectiveSurface(cfg, null))
    }

    @Test fun `feature off always keeps preset`() {
        val cfg = base.copy(useRouteSurface = false)
        assertEquals(KarooSurface.ASPHALT, effectiveSurface(cfg, KarooSurface.GRAVEL))
    }
}

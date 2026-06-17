package com.enderthor.kpower.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveActiveConfigTest {
    private fun cfg(id: Int, profileId: String? = null, active: Boolean = false) =
        ConfigData(id, "b$id", active, "8", "0.005", "0.8", "0.4", "2", "", false, "", "200",
            karooProfileId = profileId)

    @Test fun `profile mapping wins`() {
        val list = listOf(cfg(0, active = true), cfg(1, profileId = "P2"))
        assertEquals(1, resolveActiveConfig(list, "P2")?.id)
    }
    @Test fun `falls back to isActive when no profile match`() {
        val list = listOf(cfg(0), cfg(1, active = true))
        assertEquals(1, resolveActiveConfig(list, "PX")?.id)
    }
    @Test fun `falls back to first when nothing matches`() {
        val list = listOf(cfg(0), cfg(1))
        assertEquals(0, resolveActiveConfig(list, null)?.id)
    }
    @Test fun `null on empty list`() {
        assertNull(resolveActiveConfig(emptyList(), "P1"))
    }
}

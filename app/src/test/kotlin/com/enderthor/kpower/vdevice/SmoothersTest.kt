package com.enderthor.kpower.vdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothersTest {

    // ---- GradeSmoother ----

    @Test
    fun `grade smoother dampens a single-sample grade spike`() {
        val s = GradeSmoother(tauMs = 2_000.0)
        var t = 1_000L
        s.update(5.0, t)
        t += 900L
        val spiked = s.update(10.0, t)
        assert(spiked < 7.5) { "spiked=$spiked should be well below 10" }
    }

    @Test
    fun `grade smoother follows a sustained ramp change`() {
        val s = GradeSmoother(tauMs = 2_000.0)
        var t = 1_000L
        s.update(0.0, t)
        var out = 0.0
        repeat(8) {
            t += 900L
            out = s.update(8.0, t)
        }
        assert(out > 7.5) { "out=$out should have converged near 8" }
    }

    @Test
    fun `grade smoother reseeds after a long gap`() {
        val s = GradeSmoother(maxDtMs = 10_000L)
        s.update(2.0, 1_000L)
        assertEquals(9.0, s.update(9.0, 30_000L), 1e-9)
    }

    // ---- CadenceGate ----

    @Test
    fun `gate has hysteresis around the cutoff`() {
        val g = CadenceGate(onRpm = 25.0, offRpm = 20.0)
        assertFalse(g.update(0.0, 0L))      // arranque: sin pedalear
        assertTrue(g.update(30.0, 1_000L))  // supera ON → pedaleando
        assertTrue(g.update(22.0, 2_000L))  // zona muerta → mantiene ON
        assertFalse(g.update(19.0, 3_000L)) // por debajo de OFF → parado
        assertFalse(g.update(23.0, 4_000L)) // zona muerta → mantiene OFF
        assertTrue(g.update(26.0, 5_000L))  // supera ON de nuevo
    }

    @Test
    fun `sin sensor nunca visto, manda el movimiento`() {
        val g = CadenceGate()
        // Comportamiento original intacto: si no hay sensor de cadencia, el único criterio
        // posible es el movimiento sostenido.
        assertTrue(g.updateAbsent(1_000L, movingFallback = true))
        assertFalse(g.updateAbsent(2_000L, movingFallback = false))
    }

    @Test
    fun `un corte del sensor NO convierte soltar en pedalear`() {
        val g = CadenceGate(maxHoldMs = 10_000L)
        assertTrue(g.update(80.0, 0L))                                  // pedaleando
        assertFalse(g.update(0.0, 1_000L))                              // deja de pedalear
        // El sensor se cae mientras vas soltando y la bici SIGUE rodando (bajada).
        // Con el fallback antiguo esto daba "pedaleando" y fabricaba vatios.
        assertFalse(g.updateAbsent(2_000L, movingFallback = true))
        assertFalse(g.updateAbsent(11_000L, movingFallback = true))     // dentro del hold
    }

    @Test
    fun `un corte largo vuelve al proxy de movimiento`() {
        val g = CadenceGate(maxHoldMs = 10_000L)
        assertTrue(g.update(80.0, 0L))
        assertTrue(g.updateAbsent(5_000L, movingFallback = false))      // hold: conserva ON
        assertFalse(g.updateAbsent(20_000L, movingFallback = false))    // caducado → fallback
        assertTrue(g.updateAbsent(21_000L, movingFallback = true))      // proxy de movimiento
    }

    // Los dos casos adversariales del hold, uno por sentido. Fijan el COSTE de la decisión, no
    // sólo su beneficio: acotar el error a maxHoldMs se paga con hasta 10 s de error en ambas
    // direcciones. Si alguien cambia maxHoldMs, estos tests dicen qué se está moviendo.

    @Test
    fun `corte con ultima lectura ON y el rider deja de pedalear`() {
        val g = CadenceGate(maxHoldMs = 10_000L)
        assertTrue(g.update(80.0, 0L))                                  // pedaleando fuerte
        // El sensor muere aqui y el rider deja de pedalear justo despues: durante el hold el gate
        // sigue diciendo ON. Es el precio del hold, acotado a 10 s.
        assertTrue(g.updateAbsent(1_000L, movingFallback = true))
        assertTrue(g.updateAbsent(9_000L, movingFallback = true))
        assertTrue(g.updateAbsent(11_000L, movingFallback = true))      // caducado → proxy (rueda)
    }

    @Test
    fun `corte con ultima lectura OFF y el rider arranca a pedalear`() {
        val g = CadenceGate(maxHoldMs = 10_000L)
        assertTrue(g.update(80.0, 0L))
        assertFalse(g.update(0.0, 1_000L))                              // deja de pedalear
        // El sensor muere y el rider arranca: durante el hold el gate dice OFF y se pierden
        // vatios reales. Simetrico al test anterior, y tambien acotado a 10 s.
        assertFalse(g.updateAbsent(2_000L, movingFallback = true))
        assertTrue(g.updateAbsent(12_000L, movingFallback = true))      // caducado → proxy
    }

    @Test
    fun `una fuente perdida no degrada a nunca vista`() {
        val g = CadenceGate(maxHoldMs = 1_000L)
        assertTrue(g.update(80.0, 0L))
        assertFalse(g.updateAbsent(50_000L, movingFallback = false))    // perdida hace mucho
        // Vuelve el sensor: rearma y manda la histeresis, no el proxy.
        assertFalse(g.update(0.0, 51_000L))
        assertTrue(g.update(80.0, 52_000L))
    }
}

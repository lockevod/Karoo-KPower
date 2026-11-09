package com.enderthor.kpower.extension

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prueba la `decodeDeduped` REAL de Extensions.kt (es `internal`, como `monitorStreamData`).
 *
 * Fija el razonamiento que es fácil olvidar: por qué la clave de dedup no puede avanzar tras un
 * decode fallido. Mover el `distinctUntilChanged` aguas arriba del decode ahorra reparseos, pero introduce un modo
 * de fallo nuevo: si la cadena que falló quedara memorizada, DataStore seguiría ofreciendo esa misma
 * cadena en cada escritura ajena, el dedup la descartaría, y el colector se quedaría con su valor de
 * reserva el resto de la sesión. Antes del cambio la siguiente emisión reintentaba sola.
 */
class DecodeDedupedTest {

    @Test
    fun `una cadena repetida se decodifica una sola vez`() = runBlocking {
        val decodes = mutableListOf<String>()
        val out = flowOf("A", "A", "A", "B", "B", "A")
            .decodeDeduped(decode = { decodes.add(it); it.lowercase() }, onError = { "err" })
            .toList()

        // Sólo los cambios reales llegan al decodificador...
        assertEquals(listOf("A", "B", "A"), decodes)
        // ...y aguas abajo se ve exactamente un valor por cambio real.
        assertEquals(listOf("a", "b", "a"), out)
    }

    @Test
    fun `un decode fallido se reintenta en la siguiente emision de la misma cadena`() = runBlocking {
        var failuresLeft = 1
        val decodes = mutableListOf<String>()
        val out = flowOf("A", "A", "A")
            .decodeDeduped(
                decode = {
                    decodes.add(it)
                    if (failuresLeft-- > 0) throw IllegalStateException("transitorio")
                    it.lowercase()
                },
                onError = { "fallback" },
            )
            .toList()

        // La primera "A" falla; la segunda DEBE reintentarse (no quedó memorizada como decodificada),
        // y una vez decodificada bien, la tercera ya se deduplica.
        assertEquals(listOf("A", "A"), decodes)
        assertEquals(listOf("fallback", "a"), out)
    }

    @Test
    fun `un fallo permanente no deduplica pero tampoco deja de emitir`() = runBlocking {
        val out = flowOf("mal", "mal", "mal")
            .decodeDeduped<String, String>(
                decode = { throw IllegalStateException("json roto") },
                onError = { "fallback" },
            )
            .toList()

        // Sin memorizar el fallo, cada emisión reintenta y publica el valor de reserva. Es más
        // trabajo que antes en el caso patológico, pero conserva la auto-recuperación, y una
        // configuración permanentemente corrupta no es el caso que hay que optimizar.
        assertEquals(listOf("fallback", "fallback", "fallback"), out)
    }

    @Test
    fun `null es una clave de dedup valida`() = runBlocking {
        // streamCurrentWeatherData deduplica sobre String? (la clave puede no existir todavía).
        val decodes = mutableListOf<String?>()
        val out = flowOf<String?>(null, null, "x", null)
            .decodeDeduped(decode = { decodes.add(it); it ?: "vacio" }, onError = { "err" })
            .toList()

        assertEquals(listOf(null, "x", null), decodes)
        assertEquals(listOf("vacio", "x", "vacio"), out)
    }
}

package com.enderthor.kpower.extension

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Las tres suscripciones independientes a `OnLocationChanged` se colapsaron en un solo `shareIn`
 * (ver `sharedLocation` en KpowerExtension). Compartir ACOPLA a los colectores, y ese acoplamiento
 * es el riesgo: el clasificador de superficie lee el mapfile de disco y puede tardar segundos.
 *
 * Es tentador pensar que `shareIn` hereda el `CONFLATED` del `.buffer()` de `streamLocation()`. NO
 * lo hace: la fusión exige que el flujo de aguas arriba sobreescriba `dropChannelOperators()`, y
 * `callbackFlow{}.buffer(CONFLATED)` se fusiona en otro `CallbackFlowBuilder`, que no lo hace. Sin
 * un `.conflate()` explícito el SharedFlow es un búfer FIFO de 64 con `onBufferOverflow = SUSPEND`,
 * y entonces un colector atascado hace que los demás arrastren posiciones VIEJAS (y, pasadas 64
 * muestras, los bloquea del todo).
 *
 * El primer test mide justo esa diferencia sobre la misma topología del código de producción, y
 * el segundo fija por qué NO basta con mirar si el productor sigue avanzando.
 */
class SharedLocationBackpressureTest {

    /** Reproduce la topología de `sharedLocation`: callbackFlow conflado -> shareIn [-> conflate]. */
    private fun runTopology(conflateAfterShare: Boolean): Result {
        return runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val produced = AtomicInteger(0)
                // Barrera: el productor NO arranca hasta que los dos colectores están suscritos de
                // verdad. Sin ella, `Eagerly` + `replay = 0` hace que una máquina cargada pierda las
                // primeras muestras y la aserción de frescura falle por scheduling, no por el código.
                val start = CompletableDeferred<Unit>()
                val upstream = channelFlow {
                    start.await()
                    repeat(TOTAL) { i ->
                        send(i)
                        produced.set(i + 1)
                        delay(2)
                    }
                    awaitCancellation()
                }.buffer(Channel.CONFLATED)

                // onSubscription se ejecuta cuando el suscriptor YA está registrado, que es
                // justo la señal que necesita la barrera. (subscriptionCount no sirve: está en
                // MutableSharedFlow, y shareIn devuelve SharedFlow.)
                val subscribed = AtomicInteger(0)
                val base = upstream.shareIn(scope, SharingStarted.Eagerly)
                    .onSubscription { subscribed.incrementAndGet() }
                val shared: Flow<Int> = if (conflateAfterShare) base.conflate() else base

                // Colector lento: atascado para siempre en su primera muestra, como un
                // readMapData() sobre un tile frío.
                val slowSeen = AtomicInteger(0)
                scope.launch { shared.collect { slowSeen.incrementAndGet(); delay(Long.MAX_VALUE / 2) } }

                // Colector rápido: el que debe seguir viendo posiciones FRESCAS.
                val lastFast = AtomicInteger(-1)
                scope.launch { shared.collect { lastFast.set(it) } }

                // `.conflate()` colecta `base`, así que ambos cuentan como suscriptores de `base`.
                withTimeout(10_000) { while (subscribed.get() < 2) delay(10) }
                start.complete(Unit)

                withTimeout(10_000) { while (produced.get() < TOTAL) delay(10) }
                // Espera activa a que el colector rápido alcance, en vez de un delay fijo: con
                // conflate llega enseguida, y sin conflate el bucle agota y deja el valor congelado
                // — que es justo lo que el test de control quiere observar.
                runCatching {
                    withTimeout(3_000) { while (lastFast.get() < TOTAL - FRESHNESS_SLACK) delay(10) }
                }
                Result(produced.get(), lastFast.get(), slowSeen.get())
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun `con conflate el colector rapido sigue viendo posiciones frescas pese al colector lento`() {
        val r = runTopology(conflateAfterShare = true)
        assertEquals("el colector lento debe seguir atascado en su primera muestra", 1, r.slowSeen)
        // ESTA es la propiedad que compra el .conflate() de sharedLocation: el colector rápido
        // salta a lo último, no arrastra una cola de posiciones viejas.
        assertTrue(
            "el colector rápido debe seguir cerca de la última muestra (llegó a ${r.lastFast} de $TOTAL); " +
                "si se ha quedado en ~$DEFAULT_CHANNEL_CAPACITY, el .conflate() de sharedLocation ha desaparecido",
            r.lastFast >= TOTAL - FRESHNESS_SLACK,
        )
    }

    @Test
    fun `sin conflate el colector rapido se congela en la capacidad de canal por defecto`() {
        // Test de control: fija POR QUÉ hace falta el .conflate(), y deja constancia de que
        // "el productor sigue avanzando" NO es prueba de desacoplamiento — pasa en ambos casos,
        // porque el canal conflado del propio callbackFlow absorbe al productor de todos modos.
        val r = runTopology(conflateAfterShare = false)
        assertEquals(TOTAL, r.produced)
        assertEquals(1, r.slowSeen)
        assertTrue(
            "sin conflate el colector rápido debería congelarse en el búfer de 64, no llegar a ${r.lastFast}",
            r.lastFast < TOTAL - FRESHNESS_SLACK,
        )
    }

    private data class Result(val produced: Int, val lastFast: Int, val slowSeen: Int)

    private companion object {
        const val TOTAL = 300
        const val DEFAULT_CHANNEL_CAPACITY = 64
        /** Holgura para el desfase de planificación del colector rápido, muy por debajo de los 64. */
        const val FRESHNESS_SLACK = 20
    }
}

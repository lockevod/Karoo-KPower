package com.enderthor.kpower.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import com.enderthor.kpower.vdevice.PowerEstimationEngine
import kotlin.time.Duration.Companion.seconds

const val TYPE_EST_INSTANT = "estimated-power-instant"
const val TYPE_EST_3S = "estimated-power-3s"
const val TYPE_EST_NP = "estimated-power-np"
const val TYPE_EST_AVG = "estimated-power-avg"

/**
 * Campo numérico que refleja una métrica de potencia estimada del engine. SIEMPRE disponible para
 * ponerlo en una página: mientras el campo está en pantalla ARRANCA el estimador (ref-count por
 * token), y lo suelta al quitarlo — así el coste es 100% opt-in (cero si no lo pones). Muestra
 * `---` (NotAvailable) hasta que hay primera muestra. La grabación en el FIT NO depende de esto:
 * la gobierna el toggle "Grabar estimada (FIT)" en startFit.
 */
class EstimatedPowerDataType(
    extension: String,
    typeId: String,
    private val engine: PowerEstimationEngine,
    private val metric: (PowerEstimationEngine) -> StateFlow<Double>,
) : DataTypeImpl(extension, typeId) {

    @OptIn(FlowPreview::class)
    override fun startStream(emitter: Emitter<StreamState>) {
        // Cancelamos el SCOPE (no solo el job hijo) al desmontar la vista, para no dejar
        // el SupervisorJob padre vivo acumulándose en re-suscripciones a lo largo de la marcha.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        // Token NUEVO por suscripción (no `this`): así dos páginas con el mismo campo se
        // ref-cuentan por separado y soltar una no para el engine si otra lo sigue usando.
        val token = Any()
        engine.acquire(token)
        scope.launch {
            combine(
                engine.hasSample,
                metric(engine),
            ) { hasSample, value -> hasSample to value }
                .sample(1.seconds)
                .distinctUntilChanged()
                .collect { (hasSample, value) ->
                    if (!hasSample || value.isNaN()) {
                        // The estimate is a computed value, never a "device" — while it's warming up
                        // (no sample yet) show SEARCHING rather than a "no device" NotAvailable.
                        emitter.onNext(StreamState.Searching)
                    } else {
                        emitter.onNext(
                            StreamState.Streaming(
                                DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to value))
                            )
                        )
                    }
                }
        }
        emitter.setCancellable { engine.release(token); scope.cancel() }
    }
}

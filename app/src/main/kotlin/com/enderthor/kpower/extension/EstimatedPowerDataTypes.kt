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
import kotlinx.coroutines.flow.Flow
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
 * Campo numérico que refleja una métrica de potencia estimada del engine.
 * Muestra `---` (NotAvailable) si el modo comparación está OFF o no hay muestra todavía.
 */
class EstimatedPowerDataType(
    extension: String,
    typeId: String,
    private val engine: PowerEstimationEngine,
    private val comparisonModeFlow: () -> Flow<Boolean>,
    private val metric: (PowerEstimationEngine) -> StateFlow<Double>,
) : DataTypeImpl(extension, typeId) {

    @OptIn(FlowPreview::class)
    override fun startStream(emitter: Emitter<StreamState>) {
        // Cancelamos el SCOPE (no solo el job hijo) al desmontar la vista, para no dejar
        // el SupervisorJob padre vivo acumulándose en re-suscripciones a lo largo de la marcha.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            combine(
                comparisonModeFlow(),
                engine.hasSample,
                metric(engine),
            ) { enabled, hasSample, value -> Triple(enabled, hasSample, value) }
                .sample(1.seconds)
                .distinctUntilChanged()
                .collect { (enabled, hasSample, value) ->
                    if (!enabled || !hasSample || value.isNaN()) {
                        emitter.onNext(StreamState.NotAvailable)
                    } else {
                        emitter.onNext(
                            StreamState.Streaming(
                                DataPoint(dataTypeId, values = mapOf(DataType.Field.SINGLE to value))
                            )
                        )
                    }
                }
        }
        emitter.setCancellable { scope.cancel() }
    }
}

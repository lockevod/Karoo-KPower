package com.enderthor.kpower.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

fun realFieldTypeId(slot: Int, metric: String) = "real-$metric-$slot"   // e.g. real-power-0, real-3s-0, real-np-0, real-avg-0

/** Live metric of a real ANT+ meter slot; shows `---` when comparison mode is off or no sample. */
class RealPowerDataType(
    extension: String,
    typeId: String,
    private val slot: Int,
    private val comparisonModeFlow: () -> Flow<Boolean>,
    private val savedMetersFlow: () -> Flow<List<com.enderthor.kpower.ant.SavedMeter>>,
    private val metricFlowFor: (deviceNumber: Int) -> StateFlow<Double>,
) : DataTypeImpl(extension, typeId) {

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            combine(comparisonModeFlow(), savedMetersFlow()) { enabled, meters ->
                enabled to meters.firstOrNull { it.slot == slot }?.deviceNumber
            }
                .flatMapLatest { (enabled, dn) ->
                    if (dn == null) flowOf(enabled to Double.NaN)
                    else metricFlowFor(dn).map { enabled to it }
                }
                .sample(1.seconds)
                .distinctUntilChanged()
                .collect { (enabled, value) ->
                    if (!enabled || value.isNaN()) {
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

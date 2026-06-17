package com.enderthor.kpower.extension

import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

fun realPowerTypeId(slot: Int) = "real-power-$slot"

/** Live power of a real ANT+ meter slot; shows `---` when comparison mode is off or no sample. */
class RealPowerDataType(
    extension: String,
    slot: Int,
    private val comparisonModeFlow: () -> Flow<Boolean>,
    private val powerFlow: () -> StateFlow<Double>?,
) : DataTypeImpl(extension, realPowerTypeId(slot)) {

    @OptIn(FlowPreview::class)
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            val flow = powerFlow()
            if (flow == null) {
                emitter.onNext(StreamState.NotAvailable)
                return@launch
            }
            combine(comparisonModeFlow(), flow) { enabled, value -> enabled to value }
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

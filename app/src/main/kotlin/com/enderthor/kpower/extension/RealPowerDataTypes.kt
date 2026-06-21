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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

fun realFieldTypeId(slot: Int, metric: String) = "real-$metric-$slot"   // e.g. real-power-0, real-3s-0, real-np-0, real-avg-0

/** Live metric of THE active real ANT+ meter; shows `---` when no meter is enabled (null dn) and
 *  SEARCHING when one is but has no sample yet. [activeDnFlow] is the single shared "active meter
 *  device number (or null)" flow — computed once in KpowerExtension, NOT per field (avoids N
 *  duplicated combine chains + Pair garbage; Ki2 resolves one currentDeviceId the same way). */
class RealPowerDataType(
    extension: String,
    typeId: String,
    private val activeDnFlow: () -> Flow<Int?>,
    private val metricFlowFor: (deviceNumber: Int) -> StateFlow<Double>,
) : DataTypeImpl(extension, typeId) {

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            activeDnFlow()
                .flatMapLatest { dn ->
                    // null sentinel = no enabled meter (→ NotAvailable); NaN = enabled but no value yet.
                    if (dn == null) flowOf<Double?>(null) else metricFlowFor(dn).map<Double, Double?> { it }
                }
                .sample(1.seconds)
                .distinctUntilChanged()
                .collect { value ->
                    when {
                        value == null -> emitter.onNext(StreamState.NotAvailable)
                        value.isNaN() -> emitter.onNext(StreamState.Searching)
                        else -> emitter.onNext(
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

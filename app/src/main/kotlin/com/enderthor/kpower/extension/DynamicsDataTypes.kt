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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

fun dynFieldTypeId(metric: String) = "dyn-$metric-0"   // e.g. dyn-te-0, dyn-ps-0

/**
 * Live cycling-dynamics metric of a real ANT+ meter slot; shows `---` when the gate is off
 * (no meter is recorded), no meter is mapped to the slot, or the meter has no sample yet.
 *
 * REACTIVE WIRING CHOICE: this is a verbatim clone of [RealPowerDataType]'s pipeline
 * (combine(gate, savedMeters) -> flatMapLatest -> sample(1s) -> distinctUntilChanged). The only
 * differences are (a) the gate is "a meter is recorded" (antMeters non-empty) instead of
 * comparisonModeFlow, and (b) the per-device value flow is provided by [metricFlowFor], which
 * KpowerExtension builds from the live meter's dynamics StateFlow (e.g.
 * `meter(dn).tePs.map { it?.teLeftPct ?: NaN }`).
 *
 * Why a per-device Flow<Double> rather than reading the meter object on a 1Hz timer: the dynamics
 * already live in MutableStateFlows on RawAntPowerMeter, so mapping them is fully reactive (emits
 * exactly when a new page arrives) and reuses the proven RealPowerDataType pipeline unchanged.
 * Selectors map null -> Double.NaN so the field cleanly shows `---`. Note: unlike powerFlow(), the
 * meter object is resolved at flatMapLatest time, so if the meter is (dis)connected the gate/
 * savedMeters combine re-emits and the flow is rebuilt; flowOf(NaN) covers the not-yet-connected case.
 */
class DynamicsDataType(
    extension: String,
    typeId: String,
    private val activeDnFlow: () -> Flow<Int?>,
    private val metricFlowFor: (deviceNumber: Int) -> Flow<Double>,
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

package com.enderthor.kpower.extension

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import com.enderthor.kpower.R
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * A GRAPHICAL data field that shows a left/right pair as a single "47/53" value — used for the
 * cycling-dynamics metrics that are inherently two-sided (L/R balance, torque effectiveness, pedal
 * smoothness). A normal numeric field can only show one number, so these must be graphical: we draw
 * our own [RemoteViews] (the host still draws the field name header via [UpdateGraphicConfig]).
 *
 * Left is shown first (left pedal = L), right second (R) — no letters, just "L/R" as numbers. Shows
 * "--" when no meter is enabled or there's no sample yet. Mirrors the gate/device-selection of
 * [DynamicsDataType]: only the enabled meter on this slot feeds it.
 */
class DualValueDataType(
    extension: String,
    typeId: String,
    private val slot: Int = 0,
    private val gateFlow: () -> Flow<Boolean>,
    private val savedMetersFlow: () -> Flow<List<com.enderthor.kpower.ant.SavedMeter>>,
    /** Left/right pair (each nullable) for a device number. */
    private val pairFlowFor: (deviceNumber: Int) -> Flow<Pair<Double?, Double?>>,
) : DataTypeImpl(extension, typeId) {

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Let the host draw the field-name header; we only render the value area.
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        fun render(text: String) {
            // Read day/night PER render (cheap, ~1 Hz): a mid-ride theme flip (e.g. auto-dark at
            // sunset on a long ride) must update the text colour, not stay stale until a page swap.
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val rv = RemoteViews(context.packageName, R.layout.field_dual).apply {
                setTextViewText(R.id.dual_value, text)
                setTextColor(R.id.dual_value, if (night) Color.WHITE else Color.BLACK)
                setTextViewTextSize(R.id.dual_value, TypedValue.COMPLEX_UNIT_SP, config.textSize.toFloat())
            }
            emitter.updateView(rv)
        }

        if (config.preview) {
            render("50/50")   // static sample for the field-picker preview
            return
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            combine(gateFlow(), savedMetersFlow()) { enabled, meters ->
                enabled to meters.firstOrNull { it.slot == slot && it.enabled }?.deviceNumber
            }
                .flatMapLatest { (enabled, dn) ->
                    if (dn == null) flowOf(enabled to (null to null))
                    else pairFlowFor(dn).map { enabled to it }
                }
                .sample(1.seconds)
                .distinctUntilChanged()
                .collect { (enabled, pair) ->
                    val (l, r) = pair
                    // Show "L/R" only when BOTH sides exist. A single side shows just that number —
                    // covers combined pedal smoothness (0x13 b5=0xFE → left carries the COMBINED
                    // value, right is null) and single-sided meters, instead of a confusing "24/–".
                    val text = when {
                        !enabled || (l == null && r == null) -> "--"
                        l == null -> fmt(r)
                        r == null -> fmt(l)
                        else -> "${fmt(l)}/${fmt(r)}"
                    }
                    render(text)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    /** Round a side to an integer; an absent side shows "–". */
    private fun fmt(v: Double?): String =
        if (v == null || v.isNaN()) "–" else v.roundToInt().toString()
}

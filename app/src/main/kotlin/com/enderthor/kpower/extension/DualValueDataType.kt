package com.enderthor.kpower.extension

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider          // ColorProvider(day, night) factory
import androidx.glance.unit.ColorProvider           // ColorProvider type (TextStyle.color)
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * A GRAPHICAL data field that shows a left/right pair as a single "47/53" value — used for the
 * cycling-dynamics metrics that are inherently two-sided (L/R balance, torque effectiveness, pedal
 * smoothness). A normal numeric field can only show one number, so this must be graphical.
 *
 * RENDERED WITH GLANCE (Hammerhead's recommended approach — karoo-ext sample, KDouble, Headwind).
 * The host draws the field-name header (showHeader = true) so these stay consistent with the
 * single-value fields; we just render the value, centred, at the SAME [ViewConfig.textSize] the host
 * uses for its own numeric fields.
 *
 * Left is shown first (L), right second (R) — just "L/R" as numbers. Shows "--" when no meter is
 * enabled or there's no sample yet. Mirrors the gate/device-selection of [DynamicsDataType].
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class DualValueDataType(
    extension: String,
    typeId: String,
    private val activeDnFlow: () -> Flow<Int?>,
    /** Left/right pair (each nullable) for a device number. */
    private val pairFlowFor: (deviceNumber: Int) -> Flow<Pair<Double?, Double?>>,
) : DataTypeImpl(extension, typeId) {

    private val glance = GlanceRemoteViews()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Host draws the field-name header (consistent with the single-value fields); Glance fills the
        // remaining area and centres our value.
        emitter.onNext(UpdateGraphicConfig(showHeader = true))

        // Same size the host uses for its own numeric fields — that's what makes it look like "the rest".
        val sizeSp = config.textSize.toFloat()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        suspend fun render(text: String) {
            // Matches the official karoo-ext sample (CustomSpeedDataType): compose on the IO scope.
            val result = glance.compose(context, DpSize.Unspecified) { DualContent(text, sizeSp) }
            emitter.updateView(result.remoteViews)
        }

        scope.launch {
            if (config.preview) {
                render("50/50")   // static sample for the field-picker preview
                return@launch
            }
            activeDnFlow()
                .flatMapLatest { dn ->
                    // null sentinel = no enabled meter → "--".
                    if (dn == null) flowOf<Pair<Double?, Double?>?>(null)
                    else pairFlowFor(dn).map<Pair<Double?, Double?>, Pair<Double?, Double?>?> { it }
                }
                .sample(1.seconds)
                .distinctUntilChanged()
                .collect { pair ->
                    val l = pair?.first
                    val r = pair?.second
                    // Show "L/R" only when BOTH sides exist. A single side shows just that number —
                    // covers combined pedal smoothness (0x13 b5=0xFE → left carries the COMBINED
                    // value, right is null) and single-sided meters, instead of a confusing "24/–".
                    val text = when {
                        pair == null || (l == null && r == null) -> "--"
                        l == null -> fmt(r)
                        r == null -> fmt(l)
                        else -> "${fmt(l)}/${fmt(r)}"
                    }
                    render(text)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    @Composable
    private fun DualContent(text: String, sizeSp: Float) {
        // Value centred, then pulled UP with a NEGATIVE top padding (KDouble's trick: it uses
        // padding(top = -…) to tighten content). Negative top padding shifts the text up — closing the
        // gap under the header and lifting it off the bottom — WITHOUT shrinking the box, so it can't
        // clip like a bottom padding did. VALUE_TOP_DP is the knob: MORE negative = higher.
        // Day/night via Glance from the render context (TextDayNight).
        Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                maxLines = 1,
                modifier = GlanceModifier.padding(top = VALUE_TOP_DP.dp),
                style = TextStyle(
                    color = TextDayNight,
                    fontSize = sizeSp.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }
    }

    /** Round a side to an integer; an absent side shows "–". */
    private fun fmt(v: Double?): String =
        if (v == null || v.isNaN()) "–" else v.roundToInt().toString()
}

/** Black on day / white on night — resolved by Glance per render from the context (KDouble pattern).
 *  File-level val so the 5 dual fields share one instance instead of allocating per frame. */
private val TextDayNight: ColorProvider = ColorProvider(day = Color.Black, night = Color.White)

/** NEGATIVE top padding (dp) that pulls the value up toward the header, like KDouble's padding(top=-…).
 *  More negative = higher; less negative / 0 = lower. */
private const val VALUE_TOP_DP = -10

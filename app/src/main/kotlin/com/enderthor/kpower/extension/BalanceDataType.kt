package com.enderthor.kpower.extension

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * Dual L/R balance field — renders "L/R" (e.g. `47/53`) from the meter's right-side balance %.
 * Its live value only matters with a **KPW-virtual (offset) source**, where the Karoo shows no native
 * dynamics; with a natively-paired meter the Karoo already shows balance. [rightPctFlowFor] gives the
 * right-side %; left = 100 − right.
 *
 * Rendered with **Glance** (raw RemoteViews never size/centre reliably under the host header). Following
 * the recipe verified on KPower's earlier dual fields: **turn the host header OFF** (`UpdateGraphicConfig
 * showHeader=false`) and draw our OWN — a fixed label row on top, the value FILLING the remaining height
 * via `defaultWeight()` and centred. A value centred *under* the host header rendered low/clipped.
 * Value at `config.textSize` (the size the host calibrates for the slot → matches native fields).
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class BalanceDataType(
    extension: String,
    typeId: String,
    private val label: String,
    private val activeDnFlow: () -> Flow<Int?>,
    private val rightPctFlowFor: (deviceNumber: Int) -> StateFlow<Double>,
) : DataTypeImpl(extension, typeId) {

    private val glance = GlanceRemoteViews()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        // Own header, not the host's (see class doc).
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        // Day/night resolved per compose by Glance. Factory in androidx.glance.color; the type
        // TextStyle.color wants is androidx.glance.unit — call fully-qualified to dodge the name clash.
        val color = androidx.glance.color.ColorProvider(day = Color.Black, night = Color.White)
        val labelSp = (if (config.textSize < 18) 12 else 15)
        suspend fun render(value: String) {
            val result = glance.compose(context, DpSize.Unspecified) {
                Column(GlanceModifier.fillMaxSize().padding(2.dp)) {
                    Text(
                        label.uppercase(),
                        style = TextStyle(fontSize = labelSp.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center),
                        maxLines = 2,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                    Box(GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.Center) {
                        Text(
                            value,
                            style = TextStyle(fontSize = config.textSize.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color, textAlign = TextAlign.Center),
                        )
                    }
                }
            }
            emitter.updateView(result.remoteViews)
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            if (config.preview) { render("48/52"); return@launch }   // gallery sample
            activeDnFlow()
                .flatMapLatest { dn -> if (dn == null) flowOf(Double.NaN) else rightPctFlowFor(dn) }
                .sample(1.seconds)
                // Dedup on what is actually PAINTED, not on the raw Double: the screen shows the rounded
                // integer pair, so 52.31 -> 52.44 both paint "48/52". Deduping upstream of the rounding
                // let every sample through and ran a full Glance compose + updateView IPC (the most
                // expensive operation in this file) once a second for hours — and for the session-average
                // field, whose text is effectively frozen after a few minutes, that was ~100% wasted.
                //
                // The night mask is part of the key, and it is NOT decoration. Glance's DayNightColorProvider
                // only defers day/night to the host on API >= 31; the Karoo is below that, so the colour is
                // resolved HERE at compose time and baked into the RemoteViews. Keying on the text alone
                // would mean a settled average stops recomposing and keeps the day palette after sunset
                // (black on black) — the old per-sample repaint was silently carrying the theme flip.
                .map { right ->
                    val text = if (right.isNaN()) "--" else { val r = right.roundToInt(); "${100 - r}/$r" }
                    text to (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                }
                .distinctUntilChanged()
                .collect { (text, _) -> render(text) }
        }
        emitter.setCancellable { scope.cancel() }
    }
}

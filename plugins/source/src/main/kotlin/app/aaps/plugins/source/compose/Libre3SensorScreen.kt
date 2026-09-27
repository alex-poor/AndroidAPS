package app.aaps.plugins.source.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.DangerButton
import app.aaps.core.compose.components.ListRow
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Tier 2 of report/libre3-ui-plan.md — the Sensor screen.
 *
 * Deliberately NOT a port of Juggluco's diagnostics panel. That screen shows raw status codes,
 * characteristic names and strikethrough timestamps, with `Terminate` and `Forget` sitting inline
 * beside `Info` and no confirmation. What a person can actually act on is: is it connected, how
 * fresh is the data, how long has the sensor got, and the two destructive actions — separated and
 * confirmed.
 */
@Composable
fun Libre3SensorScreen(
    state: Libre3SensorState,
    onStartNewSensor: () -> Unit,
    onStopSensor: () -> Unit,
    onForgetSensor: () -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        AapsCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LifecycleRing(state.lifecycle)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.statusLine, style = AapsTheme.type.listTitle, color = colors.textPrimary)
                        state.lastError?.let {
                            Text(it, style = AapsTheme.type.caption, color = colors.low)
                        }
                    }
                    state.signalBars?.let { SignalBars(it) }
                }
            }
        }

        if (state.serial != null) {
            AapsCard {
                Column {
                    ListRow(title = "Sensor", trailing = { Value(state.serial) })
                    state.startedLabel?.let { ListRow(title = "Started", trailing = { Value(it) }) }
                    state.expiresLabel?.let { ListRow(title = "Expires", trailing = { Value(it) }) }
                    if (state.backfilledCount > 0) {
                        ListRow(
                            title = "Backfilled",
                            sub = "readings recovered from the sensor after a gap",
                            trailing = { Value("${state.backfilledCount}") }
                        )
                    }
                    // MAC is a detail, not identity — kept last and quiet.
                    state.mac?.let { ListRow(title = "Address", trailing = { Value(it) }) }
                }
            }
        }

        PrimaryButton(
            label = if (state.serial == null) "Start sensor" else "Start new sensor",
            onClick = onStartNewSensor,
            modifier = Modifier.fillMaxWidth()
        )

        if (state.serial != null) {
            // Destructive actions live below a divider and away from the primary action, and each
            // confirms. Juggluco puts Terminate one tap from Info; that is how sensors get killed
            // by accident.
            Spacer(Modifier.height(4.dp))
            AapsCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Ending this sensor cannot be undone.",
                        style = AapsTheme.type.caption, color = colors.textSecondary
                    )
                    DangerButton(label = "Stop sensor", onClick = onStopSensor, modifier = Modifier.fillMaxWidth())
                    DangerButton(label = "Forget sensor", onClick = onForgetSensor, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun Value(text: String) =
    Text(text, style = AapsTheme.type.body, color = AapsTheme.colors.textPrimary)

/**
 * Warm-up FILLS, life DEPLETES. Same ring, inverted meaning — an hour with no data has to read
 * as "not ready yet", never as "broken".
 */
@Composable
private fun LifecycleRing(lifecycle: Libre3SensorState.Lifecycle) {
    val colors = AapsTheme.colors
    val (fraction, color) = when (lifecycle) {
        is Libre3SensorState.Lifecycle.WarmingUp ->
            (1f - (lifecycle.minutesRemaining / 60f)).coerceIn(0f, 1f) to colors.high
        is Libre3SensorState.Lifecycle.Active    -> {
            val f = lifecycle.fractionRemaining.coerceIn(0f, 1f)
            f to when {
                f <= 0.02f -> colors.low
                f <= 0.10f -> colors.high
                else       -> colors.inRange
            }
        }
        is Libre3SensorState.Lifecycle.Expired,
        is Libre3SensorState.Lifecycle.Failed    -> 1f to colors.low
        is Libre3SensorState.Lifecycle.NoSensor  -> 0f to colors.textTertiary
    }
    Canvas(Modifier.size(28.dp)) {
        val stroke = size.minDimension * 0.16f
        val inset = stroke / 2f
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(
            Color.White.copy(alpha = 0.15f), -90f, 360f, false,
            topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke)
        )
        drawArc(
            color, -90f, 360f * fraction, false,
            topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke)
        )
    }
}

/** Coarse bars rather than a dBm figure: "weak" is actionable, "-83" is not. */
@Composable
private fun SignalBars(bars: Int) {
    val colors = AapsTheme.colors
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Spacer(
                Modifier
                    .width(3.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (i <= bars) colors.textSecondary else colors.divider)
            )
        }
    }
}

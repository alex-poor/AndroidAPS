package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.Stepper
import app.aaps.core.compose.theme.AapsTheme
import java.util.Locale

/**
 * Redesigned Temp target sheet (handoff Section 3): "pick an intent" preset cards + Target/Duration
 * steppers. [onStart] runs the SAME confirmation + persistence path as the legacy dialog.
 */
@Composable
fun TempTargetSheet(
    state: TempTargetSheetState,
    onStart: (target: Double, durationMin: Int, reason: TtReason) -> Unit,
    onCancelActive: () -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    var target by remember { mutableStateOf(state.initialTarget) }
    var duration by remember { mutableStateOf(state.initialDuration) }
    var reason by remember { mutableStateOf(TtReason.CUSTOM) }

    fun fmt(v: Double) = String.format(Locale.getDefault(), "%.${state.decimals}f", v)

    SheetSurface(title = "Temp target", onClose = onClose) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("PICK AN INTENT", style = AapsTheme.type.label, color = colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.presets.forEach { p ->
                    val selected = reason == p.reason
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(AapsTheme.shape.card)
                            .background(if (selected) colors.accentTintStrong else colors.surface2)
                            .clickable {
                                target = p.target; duration = p.durationMin; reason = p.reason
                            }
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(p.label, style = AapsTheme.type.listTitle, color = if (selected) colors.accentOnLight else colors.textPrimary)
                        Text(p.targetText, style = AapsTheme.type.caption, color = colors.textSecondary)
                        Text(p.durationText, style = AapsTheme.type.caption, color = colors.textTertiary)
                    }
                }
            }

            Text("ADJUST", style = AapsTheme.type.label, color = colors.textSecondary)
            AapsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stepper(
                        value = "${fmt(target)} ${state.unitLabel}",
                        caption = "target",
                        onMinus = { target = (target - state.unitStep).coerceAtLeast(state.targetMin); reason = TtReason.CUSTOM },
                        onPlus = { target = (target + state.unitStep).coerceAtMost(state.targetMax); reason = TtReason.CUSTOM }
                    )
                    Stepper(
                        value = "$duration min",
                        caption = "duration",
                        onMinus = { duration = (duration - state.durationStep).coerceAtLeast(0) },
                        onPlus = { duration += state.durationStep }
                    )
                }
            }

            val enabled = target > 0.0 && duration > 0
            Text(
                "Start temp target",
                style = AapsTheme.type.title,
                color = if (enabled) colors.onAccent else colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AapsTheme.shape.button)
                    .background(if (enabled) colors.accent else colors.controlFill)
                    .then(if (enabled) Modifier.clickable { onStart(target, duration, reason) } else Modifier)
                    .padding(vertical = 14.dp)
            )
            if (state.hasActive)
                Text(
                    "Cancel current temp target",
                    style = AapsTheme.type.body,
                    color = colors.low,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AapsTheme.shape.pill)
                        .clickable(onClick = onCancelActive)
                        .padding(vertical = 10.dp)
                )
        }
    }
}

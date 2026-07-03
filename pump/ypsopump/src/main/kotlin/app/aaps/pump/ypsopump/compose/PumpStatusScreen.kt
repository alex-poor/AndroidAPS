package app.aaps.pump.ypsopump.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsSemantic
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned pump status screen (handoff Section 7): connection pill, Reservoir + Battery gauge tiles,
 * status rows, and the command-queue list. Read-only view over the pump state + CommandQueue. The card
 * layout is generic enough to reuse for any driver.
 */
@Composable
fun PumpStatusScreen(state: PumpStatusState) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.title, style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            StatusPill(
                label = if (state.connected) "Connected" else state.connection.ifBlank { "Disconnected" },
                dotColor = if (state.connected) AapsSemantic.inRange else AapsSemantic.low
            )
        }

        Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            GaugeTile(
                "RESERVOIR", String.format(java.util.Locale.getDefault(), "%.0f U", state.reservoir),
                (state.reservoir / state.reservoirMax).toFloat(),
                if (state.reservoir < 20) AapsSemantic.high else AapsSemantic.inRange, Modifier.weight(1f)
            )
            GaugeTile(
                "BATTERY", "${state.battery}%", state.battery / 100f,
                if (state.battery < 25) AapsSemantic.low else AapsSemantic.inRange, Modifier.weight(1f)
            )
        }

        if (state.rows.isNotEmpty()) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    state.rows.forEachIndexed { i, r ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                            Text(r.value, style = AapsType.listTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        Text("COMMAND QUEUE", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        AapsCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Column {
                if (state.queue.isEmpty()) {
                    Text("Idle", style = AapsType.body, color = colors.textTertiary, modifier = Modifier.padding(vertical = 12.dp))
                } else state.queue.forEachIndexed { i, q ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (q.running) AapsSemantic.inRange else colors.textTertiary))
                        Text(q.text, style = AapsType.body, color = colors.textOnSurfaceStrong, modifier = Modifier.padding(start = 10.dp).weight(1f))
                        if (q.running) Text("running", style = AapsType.caption, color = AapsSemantic.inRange)
                    }
                }
            }
        }

        if (state.note.isNotBlank()) Text(state.note, style = AapsType.caption, color = colors.textTertiary, modifier = Modifier.padding(bottom = 24.dp))
    }
}


@Composable
private fun GaugeTile(label: String, value: String, fraction: Float, color: Color, modifier: Modifier) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = AapsType.label.copy(fontSize = 9.sp), color = colors.textSecondary)
            Text(value, style = AapsType.cardValue, color = colors.textPrimary)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.controlFill)) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
            }
        }
    }
}

package app.aaps.ui.activities.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType
import kotlin.math.roundToInt

/**
 * Redesigned Statistics screen (handoff Section 4): range selector, a TIR card (big in-range % + a
 * 5-band stacked bar), and 2×2 stat tiles. Read-only; [onRange] recomputes for the chosen window.
 */
@Composable
fun StatsScreen(state: StatsUiState, onRange: (Int) -> Unit, onBack: () -> Unit) {
    val colors = AapsTheme.colors
    val ranges = listOf(7, 30, 90)
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onBack).padding(8.dp)
            ) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = colors.textSecondary) }
            Text("Statistics", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
            SegmentedControl(ranges.map { "${it}d" }, ranges.indexOf(state.rangeDays).coerceAtLeast(0), { onRange(ranges[it]) })
        }

        // TIR card
        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("TIME IN RANGE", style = AapsType.label, color = colors.textSecondary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (state.loading) "--" else "${state.inRange.roundToInt()}%", style = AapsType.hero.copy(fontSize = 56.sp, lineHeight = 56.sp), color = colors.inRange)
                    Text("in range", style = AapsType.caption, color = colors.textTertiary, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
                }
                // stacked bar
                Row(
                    Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(colors.controlFill)
                ) {
                    Seg(state.veryLow, colors.veryLow)
                    Seg(state.low, colors.low)
                    Seg(state.inRange, colors.inRange)
                    Seg(state.high, colors.high)
                    Seg(state.veryHigh, colors.veryHigh)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BarLabel("Low", state.veryLow + state.low, colors.low, colors.textTertiary)
                    BarLabel("In range", state.inRange, colors.inRange, colors.textTertiary)
                    BarLabel("High", state.high + state.veryHigh, colors.high, colors.textTertiary)
                }
            }
        }

        // 2×2 tiles
        Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            StatTile("GMI / eA1c", state.gmi, Modifier.weight(1f))
            StatTile("AVG GLUCOSE", if (state.avgGlucose == "--") "--" else "${state.avgGlucose} ${state.avgGlucoseUnit}", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            StatTile("CV", state.cv, Modifier.weight(1f), valueColor = if (state.cvGood) colors.inRange else colors.textPrimary)
            StatTile("AVG TDD", state.avgTdd, Modifier.weight(1f))
        }

        // extra
        AapsCard(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Carbs / day", style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text(state.carbsPerDay, style = AapsType.listTitle, color = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Seg(pct: Double, color: Color) {
    if (pct > 0.0) Box(Modifier.weight(pct.toFloat()).fillMaxHeight().background(color))
}

@Composable
private fun BarLabel(name: String, pct: Double, dot: Color, textColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text("  $name ${pct.roundToInt()}%", style = AapsType.caption, color = textColor)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = AapsTheme.colors.textPrimary) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = AapsType.label.copy(fontSize = 9.sp), color = colors.textSecondary)
            Text(value, style = AapsType.cardValue, color = valueColor, maxLines = 1)
        }
    }
}

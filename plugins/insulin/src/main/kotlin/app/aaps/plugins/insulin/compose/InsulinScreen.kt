package app.aaps.plugins.insulin.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import java.util.Locale
import kotlin.math.exp

/**
 * Redesigned Insulin curve screen (handoff Section 6 — Insulin): type chips, an activity-curve preview
 * (computed from the active insulin's DIA + peak via the oref exponential model), Duration/Peak tiles,
 * and helper text. Read-only presentation over the active [app.aaps.core.interfaces.insulin.Insulin].
 */
@Composable
fun InsulinScreen(state: InsulinUiState) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Text("Insulin", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        if (state.types.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = AapsSpacing.sectionGap),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.types.forEach { c -> Chip(label = c.label, onClick = {}, selected = c.active, enabled = false) }
            }
        }

        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column {
                Text("INSULIN ACTIVITY · % PER HOUR", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
                InsulinCurve(state.diaHours, state.peakMinutes, Modifier.fillMaxWidth().height(150.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text("0", style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.weight(1f))
                    Text("peak ${state.peakMinutes}m", style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.weight(1f))
                    Text(fmtHours(state.diaHours), style = AapsTheme.type.caption, color = colors.textTertiary)
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            Tile("DURATION (DIA)", fmtHours(state.diaHours), Modifier.weight(1f))
            Tile("PEAK TIME", "${state.peakMinutes} min", Modifier.weight(1f))
        }

        Text(
            "This curve is how AAPS models insulin working in your body over time. Changing type reshapes it — and with it every dose calculation. Change the active insulin in Config Builder.",
            style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    val colors = AapsTheme.colors
    AapsCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = AapsTheme.type.label, color = colors.textSecondary)
            Text(value, style = AapsTheme.type.cardValue, color = colors.textPrimary)
        }
    }
}

@Composable
private fun InsulinCurve(diaHours: Double, peakMinutes: Int, modifier: Modifier) {
    val colors = AapsTheme.colors
    val accent = colors.accent
    val grid = colors.divider
    Canvas(modifier) {
        // baseline is always drawn; the curve is skipped for not-yet-loaded / degenerate params
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
        val diaMin = diaHours * 60.0
        if (diaMin < 30.0 || peakMinutes <= 0) return@Canvas
        // keep peak strictly inside (0, dia/2) so the oref model is well-defined (no div-by-zero / empty range)
        val peak = peakMinutes.toDouble().coerceIn(1.0, diaMin * 0.45)
        // oref exponential insulin activity model (activity fraction per minute)
        val tau = peak * (1 - peak / diaMin) / (1 - 2 * peak / diaMin)
        val a = 2 * tau / diaMin
        val s = 1.0 / (1 - a + (1 + a) * exp(-diaMin / tau))
        val n = 80
        val acts = DoubleArray(n + 1) { i ->
            val t = diaMin * i / n
            (s / (tau * tau)) * t * (1 - t / diaMin) * exp(-t / tau)
        }
        val peakAct = (acts.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
        val path = Path()
        for (i in 0..n) {
            val x = size.width * i / n
            val y = size.height * (1f - (acts[i] / peakAct).toFloat() * 0.92f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent, style = Stroke(width = 4f))
        // fill under curve
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, accent.copy(alpha = 0.12f))
    }
}

private fun fmtHours(h: Double) = String.format(Locale.getDefault(), "%.1f h", h)

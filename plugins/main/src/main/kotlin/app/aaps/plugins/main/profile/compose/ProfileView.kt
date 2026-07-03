package app.aaps.plugins.main.profile.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Read-only profile view (handoff Section 4, presentation): Basal/ISF/Carb-ratio/Target tabs, a 24h
 * stepped basal curve, and per-tab time-block lists. [onEdit] hands off to the legacy editor
 * (revealed underneath); editing here is a later pass.
 */
@Composable
fun ProfileView(state: ProfileViewState, onEdit: () -> Unit) {
    val colors = AapsTheme.colors
    var tab by remember { mutableStateOf(ProfileTab.BASAL) }
    val blocks = when (tab) {
        ProfileTab.BASAL  -> state.basal
        ProfileTab.ISF    -> state.isf
        ProfileTab.IC     -> state.ic
        ProfileTab.TARGET -> state.target
    }
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Profile", style = AapsType.title, color = colors.textPrimary)
                if (state.profileName.isNotBlank()) Text(state.profileName, style = AapsType.caption, color = colors.textTertiary)
            }
            Row(
                Modifier.background(colors.accentTint, app.aaps.core.compose.theme.AapsShape.pill).clickable(onClick = onEdit).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = colors.accentOnLight, modifier = Modifier.height(16.dp))
                Text("  Edit", style = AapsType.listTitle, color = colors.accentOnLight)
            }
        }

        if (state.dia.isNotBlank()) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Insulin duration (DIA)", style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(state.dia, style = AapsType.listTitle, color = colors.textPrimary)
                }
            }
        }

        SegmentedControl(
            ProfileTab.entries.map { it.label },
            ProfileTab.entries.indexOf(tab),
            { tab = ProfileTab.entries[it] },
            Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)
        )

        if (tab == ProfileTab.BASAL) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        Text("24-HOUR BASAL", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(state.dailyBasal, style = AapsType.listTitle, color = colors.textPrimary)
                    }
                    BasalCurve(state.basal)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("00", "06", "12", "18", "24").forEach { Text(it, style = AapsType.caption, color = colors.textTertiary) }
                    }
                }
            }
        }

        AapsCard(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column {
                blocks.forEachIndexed { i, b ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(b.time, style = AapsType.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
                        Text(b.value, style = AapsType.listTitle, color = colors.textPrimary)
                    }
                }
                if (blocks.isEmpty()) Text("No data", style = AapsType.body, color = colors.textTertiary, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun BasalCurve(blocks: List<ProfileBlock>) {
    val colors = AapsTheme.colors
    val accent = colors.accent
    val fill = colors.accentTint
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        if (blocks.isEmpty()) return@Canvas
        val maxV = (blocks.maxOf { it.curveValue }).coerceAtLeast(0.01) * 1.15
        val w = size.width; val h = size.height
        fun x(sec: Int) = (sec / 86400f) * w
        fun y(v: Double) = (h - (v / maxV * h)).toFloat()
        val line = Path()
        val area = Path()
        area.moveTo(0f, h)
        blocks.forEachIndexed { i, b ->
            val x0 = x(b.startSeconds)
            val x1 = if (i + 1 < blocks.size) x(blocks[i + 1].startSeconds) else w
            val yv = y(b.curveValue)
            if (i == 0) { line.moveTo(x0, yv); area.lineTo(x0, yv) } else { line.lineTo(x0, yv); area.lineTo(x0, yv) }
            line.lineTo(x1, yv); area.lineTo(x1, yv)
        }
        area.lineTo(w, h); area.close()
        drawPath(area, fill)
        drawPath(line, accent, style = Stroke(width = 3f))
        // baseline
        drawLine(colors.divider, Offset(0f, h - 1), Offset(w, h - 1), strokeWidth = 1f)
    }
}

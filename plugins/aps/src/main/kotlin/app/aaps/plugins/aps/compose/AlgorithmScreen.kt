package app.aaps.plugins.aps.compose

import app.aaps.core.compose.icons.AapsIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import java.util.Locale

/**
 * Redesigned Algorithm screen (handoff Section 6 — Algorithm): algorithm chips, a model-response
 * preview (the active APS's eventual/predicted BG toward target), first-class labeled toggles for the
 * active algorithm's real parameters, and a row into the full preferences. Bound to the active APS +
 * preferences — no dosing logic changes.
 */
@Composable
fun AlgorithmScreen(
    state: AlgorithmUiState,
    onToggle: (id: String, on: Boolean) -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Text(state.title, style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        if (state.chips.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = AapsSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.chips.forEach { c -> Chip(label = c.label, onClick = {}, selected = c.active, enabled = false) }
            }
        }

        if (state.predictedMmol != null) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row {
                        Text("MODEL RESPONSE · PREDICTED BG", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        if (state.targetMmol != null) Text("target ${fmt1(state.targetMmol)}", style = AapsTheme.type.caption, color = colors.accentOnLight)
                    }
                    Text("${fmt1(state.predictedMmol)} mmol/L", style = AapsTheme.type.cardValue, color = colors.textPrimary)
                }
            }
        }

        if (state.toggles.isNotEmpty()) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    state.toggles.forEachIndexed { i, t ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(t.label, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong)
                                if (t.sub.isNotBlank()) Text(t.sub, style = AapsTheme.type.caption, color = colors.textTertiary)
                            }
                            Switch(
                                checked = t.on,
                                onCheckedChange = { onToggle(t.id, it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.onAccent,
                                    checkedTrackColor = colors.accent,
                                    uncheckedTrackColor = colors.controlFill,
                                    uncheckedThumbColor = colors.textSecondary,
                                    uncheckedBorderColor = colors.hairline
                                )
                            )
                        }
                    }
                }
            }
        }

        AapsCard(Modifier.fillMaxWidth().padding(bottom = 24.dp), onClick = onOpenSettings) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Advanced settings", style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong)
                    val sub = state.bodyWeight?.let { "Body weight ${fmt1(it)} kg · all parameters" } ?: "All algorithm parameters"
                    Text(sub, style = AapsTheme.type.caption, color = colors.textTertiary)
                }
                Icon(AapsIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.height(18.dp))
            }
        }
    }
}

private fun fmt1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)

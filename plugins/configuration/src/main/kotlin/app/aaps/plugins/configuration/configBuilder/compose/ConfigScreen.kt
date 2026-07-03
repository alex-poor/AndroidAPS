package app.aaps.plugins.configuration.configBuilder.compose

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
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Dot
import app.aaps.core.compose.theme.AapsSemantic
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned Config Builder (handoff Section 5): a "Your loop, right now" summary card + a list of
 * toggleable general plugins. Toggling reuses `ConfigBuilder.performPluginSwitch` in the fragment.
 */
@Composable
fun ConfigScreen(state: ConfigUiState, onToggle: (index: Int, enabled: Boolean) -> Unit, onOpenPrefs: (index: Int) -> Unit) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Text("Config Builder", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        if (state.summary.isNotEmpty()) {
            Text("YOUR LOOP, RIGHT NOW", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    state.summary.forEachIndexed { i, s ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (s.ok) AapsSemantic.inRange else AapsSemantic.high, size = 9.dp)
                            Text(s.label, style = AapsType.listTitle, color = colors.textSecondary, modifier = Modifier.padding(start = 10.dp).weight(1f))
                            Text(s.value, style = AapsType.listTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (state.plugins.isNotEmpty()) {
            Text("PLUGINS", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            AapsCard(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Column {
                    state.plugins.forEachIndexed { i, p ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(p.name, style = AapsType.listTitle, color = colors.textOnSurfaceStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.sub.isNotBlank()) Text(p.sub, style = AapsType.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(
                                checked = p.enabled,
                                onCheckedChange = { onToggle(p.index, it) },
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

        if (state.prefs.isNotEmpty()) {
            Text("SETTINGS", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            state.prefs.groupBy { it.group }.forEach { (group, rows) ->
                Text(group, style = AapsType.caption, color = colors.textTertiary, modifier = Modifier.padding(bottom = 4.dp, top = 4.dp))
                AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap)) {
                    Column {
                        rows.forEachIndexed { i, p ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenPrefs(p.index) }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.name, style = AapsType.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
                                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.height(18.dp))
                            }
                        }
                    }
                }
            }
            Box(Modifier.height(24.dp))
        }
    }
}

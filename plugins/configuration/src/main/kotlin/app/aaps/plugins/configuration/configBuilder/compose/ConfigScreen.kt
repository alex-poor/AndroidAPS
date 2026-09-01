package app.aaps.plugins.configuration.configBuilder.compose

import app.aaps.core.compose.icons.AapsIcons
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Redesigned Config Builder (handoff Section 5): a "Your loop, right now" summary card + a list of
 * toggleable general plugins. Toggling reuses `ConfigBuilder.performPluginSwitch` in the fragment.
 */
@Composable
fun ConfigScreen(
    state: ConfigUiState,
    onToggle: (index: Int, enabled: Boolean) -> Unit,
    onOpenPrefs: (index: Int) -> Unit,
    onSelect: (index: Int, enabled: Boolean) -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Text("Config Builder", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        if (state.summary.isNotEmpty()) {
            Text("YOUR LOOP, RIGHT NOW", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    state.summary.forEachIndexed { i, s ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (s.ok) colors.inRange else colors.high, size = 9.dp)
                            Text(s.label, style = AapsTheme.type.listTitle, color = colors.textSecondary, modifier = Modifier.padding(start = 10.dp).weight(1f))
                            Text(s.value, style = AapsTheme.type.listTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        state.categories.forEach { category ->
            Text(category.title.uppercase(), style = AapsTheme.type.label, color = colors.textSecondary)
            if (category.description.isNotBlank())
                Text(category.description, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            else Box(Modifier.height(8.dp))
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    category.options.forEachIndexed { i, option ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !option.fixed) { onSelect(option.index, !option.selected) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (category.multiple)
                                Checkbox(
                                    checked = option.selected,
                                    enabled = !option.fixed,
                                    onCheckedChange = { onSelect(option.index, it) },
                                    colors = CheckboxDefaults.colors(checkedColor = colors.accent, checkmarkColor = colors.onAccent, uncheckedColor = colors.hairline)
                                )
                            else
                                RadioButton(
                                    selected = option.selected,
                                    enabled = !option.fixed,
                                    onClick = { onSelect(option.index, true) },
                                    colors = RadioButtonDefaults.colors(selectedColor = colors.accent, unselectedColor = colors.hairline)
                                )
                            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(option.name, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (option.description.isNotBlank())
                                    Text(option.description, style = AapsTheme.type.caption, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        if (state.plugins.isNotEmpty()) {
            Text("PLUGINS", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            AapsCard(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Column {
                    state.plugins.forEachIndexed { i, p ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(p.name, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.sub.isNotBlank()) Text(p.sub, style = AapsTheme.type.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Text("SETTINGS", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            state.prefs.groupBy { it.group }.forEach { (group, rows) ->
                Text(group, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(bottom = 4.dp, top = 4.dp))
                AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap)) {
                    Column {
                        rows.forEachIndexed { i, p ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenPrefs(p.index) }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(p.name, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
                                Icon(AapsIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.height(18.dp))
                            }
                        }
                    }
                }
            }
            Box(Modifier.height(24.dp))
        }
    }
}

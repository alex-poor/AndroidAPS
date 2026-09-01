package app.aaps.plugins.configuration.maintenance.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/** One log category and whether it is currently being written. */
data class LogToggle(val name: String, val enabled: Boolean)

/**
 * Redesigned log settings: which `LTag` categories AAPS writes to its own persistent log. Reached from
 * the Maintenance tab, which is already Compose — this was the last old-UI hop out of it.
 */
@Composable
fun LogSettingsScreen(
    elements: List<LogToggle>,
    onToggle: (name: String, enabled: Boolean) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←", style = AapsTheme.type.title, color = colors.textSecondary,
                modifier = Modifier.clip(AapsTheme.shape.iconButton).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text("Log settings", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(start = 6.dp))
            Text(
                "Reset", style = AapsTheme.type.label, color = colors.accent,
                modifier = Modifier.clip(AapsTheme.shape.button).clickable(onClick = onReset).padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }

        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column {
                elements.forEachIndexed { i, element ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(element.name, style = AapsTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        Switch(
                            checked = element.enabled,
                            onCheckedChange = { onToggle(element.name, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent)
                        )
                    }
                }
            }
        }
    }
}

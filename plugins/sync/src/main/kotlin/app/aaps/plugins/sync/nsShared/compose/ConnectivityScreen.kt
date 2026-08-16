package app.aaps.plugins.sync.nsShared.compose

import app.aaps.core.compose.icons.AapsIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material3.Icon
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
 * Redesigned Connectivity & sync screen (handoff Section 7): a CGM → phone → cloud data-flow row and a
 * status card per connection (dot + name + health sub + chevron). Read-only presentation over the
 * active BG source + sync plugins; tapping a Nightscout card re-triggers a sync.
 */
@Composable
fun ConnectivityScreen(state: ConnectivityUiState, onCard: (id: String) -> Unit) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Text("Connections", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        // data-flow row
        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowNode(state.cgmName.ifBlank { "CGM" }, Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = AapsSemantic.inRange, modifier = Modifier.padding(horizontal = 4.dp))
                FlowNode("AAPS", Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowForward, contentDescription = null, tint = AapsSemantic.inRange, modifier = Modifier.padding(horizontal = 4.dp))
                FlowNode(state.cloudName, Modifier.weight(1f))
            }
        }

        state.connections.forEach { c ->
            AapsCard(
                Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap),
                onClick = if (c.tappable) ({ onCard(c.id) }) else null
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Dot(levelColor(c.level), size = 9.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(c.name, style = AapsType.listTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.sub, style = AapsType.caption, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.tappable) Icon(AapsIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary)
                }
            }
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun FlowNode(label: String, modifier: Modifier) {
    val colors = AapsTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Dot(AapsSemantic.inRange, size = 8.dp)
        Text(label, style = AapsType.caption, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun levelColor(level: Int) = when (level) {
    0    -> AapsSemantic.inRange
    1    -> AapsSemantic.high
    else -> AapsTheme.colors.textTertiary
}

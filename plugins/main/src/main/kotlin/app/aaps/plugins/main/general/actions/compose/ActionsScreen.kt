package app.aaps.plugins.main.general.actions.compose

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.TintIcon
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned Actions & Careportal screen (handoff Section 3): Therapy 2-col cards, a "Log an event"
 * icon grid, and a Tools list. Stateless — every tap dispatches an [ActionId] to [onAction], which the
 * fragment routes to the SAME protected dialogs / careportal / command-queue paths as before.
 */
@Composable
fun ActionsScreen(state: ActionsUiState, onAction: (ActionId) -> Unit) {
    val colors = AapsTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH)
    ) {
        Text("Actions", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        if (state.therapy.isNotEmpty()) {
            SectionLabel("THERAPY")
            // 2-column grid
            state.therapy.chunked(2).forEach { rowItems ->
                Row(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
                    rowItems.forEach { t -> TherapyCard(t, Modifier.weight(1f), onAction) }
                    if (rowItems.size == 1) Box(Modifier.weight(1f))
                }
            }
        }

        if (state.events.isNotEmpty()) {
            SectionLabel("LOG AN EVENT")
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    state.events.chunked(4).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            rowItems.forEach { e -> EventTile(e, Modifier.weight(1f), onAction) }
                            repeat(4 - rowItems.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        if (state.tools.isNotEmpty()) {
            SectionLabel("TOOLS")
            AapsCard(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Column {
                    state.tools.forEachIndexed { i, tool ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        ToolRow(tool, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) =
    Text(text, style = AapsType.label, color = AapsTheme.colors.textSecondary, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))

@Composable
private fun TherapyCard(t: TherapyAction, modifier: Modifier, onAction: (ActionId) -> Unit) {
    val colors = AapsTheme.colors
    val accent = t.cancelable
    AapsCard(
        modifier = modifier,
        color = if (accent) colors.accentTint else colors.surface,
        onClick = { onAction(t.id) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TintIcon(iconFor(t.id), tint = if (accent) colors.accentOnLight else colors.accent, background = colors.controlFill)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(t.label, style = AapsType.listTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    t.sub.ifBlank { if (t.cancelable) "tap to cancel" else "" },
                    style = AapsType.caption,
                    color = if (accent) colors.accentOnLight else colors.textTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EventTile(e: EventAction, modifier: Modifier, onAction: (ActionId) -> Unit) {
    val colors = AapsTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable { onAction(e.id) }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(colors.controlFill),
            contentAlignment = Alignment.Center
        ) { Icon(iconFor(e.id), contentDescription = e.label, tint = colors.textSecondary, modifier = Modifier.size(22.dp)) }
        Text(e.label, style = AapsType.caption, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolRow(tool: ToolAction, onAction: (ActionId) -> Unit) {
    val colors = AapsTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable { onAction(tool.id) }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TintIcon(iconFor(tool.id), tint = colors.accent, background = colors.accentTint, size = 34.dp)
        Text(tool.label, style = AapsType.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
        Icon(AapsIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

private fun iconFor(id: ActionId): ImageVector = when (id) {
    ActionId.TEMP_TARGET                              -> AapsIcons.GpsFixed
    ActionId.TEMP_BASAL, ActionId.TEMP_BASAL_CANCEL   -> AapsIcons.Timeline
    ActionId.EXTENDED_BOLUS, ActionId.EXTENDED_BOLUS_CANCEL -> AapsIcons.Timelapse
    ActionId.PROFILE_SWITCH                           -> AapsIcons.SwapHoriz
    ActionId.FILL                                     -> AapsIcons.Colorize
    ActionId.SENSOR_INSERT                            -> AapsIcons.Sensors
    ActionId.BATTERY_CHANGE                           -> AapsIcons.BatteryChargingFull
    ActionId.BG_CHECK                                 -> AapsIcons.Bloodtype
    ActionId.NOTE                                     -> AapsIcons.EditNote
    ActionId.EXERCISE                                 -> AapsIcons.DirectionsRun
    ActionId.ANNOUNCEMENT                             -> AapsIcons.Campaign
    ActionId.QUESTION                                 -> AapsIcons.HelpOutline
    ActionId.HISTORY                                  -> AapsIcons.History
}

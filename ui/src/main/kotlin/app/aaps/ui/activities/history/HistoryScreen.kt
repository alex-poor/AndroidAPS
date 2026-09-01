package app.aaps.ui.activities.history

import app.aaps.core.compose.icons.AapsIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Redesigned History timeline (handoff Section 4): filter chips + a chronological, day-grouped list of
 * boluses / carbs / events. Read-only. [onBack] finishes the activity.
 */
@Composable
fun HistoryScreen(state: HistoryUiState, onBack: () -> Unit) {
    val colors = AapsTheme.colors
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }

    val filtered = state.items.filter {
        when (filter) {
            HistoryFilter.ALL    -> true
            HistoryFilter.BOLUS  -> it.kind == HistoryKind.BOLUS || it.kind == HistoryKind.SMB
            HistoryFilter.CARBS  -> it.kind == HistoryKind.CARBS
            HistoryFilter.EVENTS -> it.kind == HistoryKind.EVENT
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).clickable(onClick = onBack).padding(8.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = colors.textSecondary)
            }
            Text("History", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.padding(start = 4.dp))
        }
        // filter chips
        Row(Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.screenH, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All", filter == HistoryFilter.ALL) { filter = HistoryFilter.ALL }
            FilterChip("Bolus", filter == HistoryFilter.BOLUS) { filter = HistoryFilter.BOLUS }
            FilterChip("Carbs", filter == HistoryFilter.CARBS) { filter = HistoryFilter.CARBS }
            FilterChip("Events", filter == HistoryFilter.EVENTS) { filter = HistoryFilter.EVENTS }
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…", style = AapsTheme.type.body, color = colors.textTertiary) }
        } else if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No entries", style = AapsTheme.type.body, color = colors.textTertiary) }
        } else {
            // group by day, preserving order (items already sorted desc)
            val groups = filtered.groupBy { it.dayLabel }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = AapsSpacing.screenH), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                groups.forEach { (day, dayItems) ->
                    item(key = "h_$day") {
                        Text(day.uppercase(), style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                    }
                    items(dayItems, key = { it.kind.name + it.timestamp }) { HistoryRow(it) }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) =
    Chip(label, onClick, selected = selected)

@Composable
private fun HistoryRow(item: HistoryItem) {
    val colors = AapsTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.time, style = AapsTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(end = 10.dp))
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(tintFor(item.kind).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) { Icon(iconFor(item.kind), contentDescription = null, tint = tintFor(item.kind), modifier = Modifier.size(18.dp)) }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(item.title, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong)
            if (item.sub.isNotBlank()) Text(item.sub, style = AapsTheme.type.caption, color = colors.textTertiary)
        }
        if (item.value.isNotBlank()) Text(item.value, style = AapsTheme.type.listTitle, color = colors.textPrimary)
    }
}

private fun iconFor(kind: HistoryKind): ImageVector = when (kind) {
    HistoryKind.BOLUS, HistoryKind.SMB -> AapsIcons.WaterDrop
    HistoryKind.CARBS                  -> AapsIcons.Restaurant
    HistoryKind.EVENT                  -> AapsIcons.EventNote
}

@Composable
@ReadOnlyComposable
private fun tintFor(kind: HistoryKind): Color = when (kind) {
    HistoryKind.BOLUS -> AapsTheme.colors.inRange
    HistoryKind.SMB   -> AapsTheme.colors.inRange
    HistoryKind.CARBS -> AapsTheme.colors.high
    HistoryKind.EVENT -> AapsTheme.colors.accent
}

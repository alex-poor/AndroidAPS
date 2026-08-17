package app.aaps.plugins.source.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsAccent
import app.aaps.core.compose.theme.AapsSemantic
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned BG source tab: the day-grouped reading list with the same long-press-to-select /
 * tap-to-toggle / delete flow the legacy `ActionModeHelper` contextual bar provided, rebuilt as an
 * in-screen selection bar. Deletion itself still goes through the caller's confirm + invalidate path.
 *
 * [onLoadMore] fires when the list reaches its end, matching the legacy "scroll to bottom loads another
 * 24 h" behaviour.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BgSourceScreen(
    state: BgSourceState,
    onToggle: (Long) -> Unit,
    onStartSelecting: (Long) -> Unit,
    onCancelSelecting: () -> Unit,
    onDeleteSelected: () -> Unit,
    onLoadMore: () -> Unit
) {
    val colors = AapsTheme.colors
    val listState = rememberLazyListState()

    val atEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 1 && listState.layoutInfo.totalItemsCount > 0
        }
    }
    LaunchedEffect(listState) { snapshotFlow { atEnd }.collect { if (it) onLoadMore() } }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.screenH, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.selecting) {
                Text("${state.selected.size} selected", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    "Cancel", style = AapsType.body, color = colors.textSecondary,
                    modifier = Modifier.clip(AapsShape.button).combinedClickable(onClick = onCancelSelecting).padding(horizontal = 10.dp, vertical = 6.dp)
                )
                Text(
                    "Delete", style = AapsType.body, color = if (state.selected.isEmpty()) colors.textTertiary else AapsSemantic.low,
                    modifier = Modifier
                        .clip(AapsShape.button)
                        .then(if (state.selected.isEmpty()) Modifier else Modifier.combinedClickable(onClick = onDeleteSelected))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            } else {
                Text("Glucose readings", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                StatusPill(label = "${state.rows.size}", dotColor = AapsSemantic.inRange)
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.rows, key = { it.id }) { row ->
                row.dayLabel?.let {
                    Text(
                        it, style = AapsType.label, color = colors.textSecondary,
                        modifier = Modifier.fillMaxWidth().padding(start = AapsSpacing.screenH, end = AapsSpacing.screenH, top = 14.dp, bottom = 6.dp)
                    )
                }
                BgReadingRow(
                    row = row,
                    selecting = state.selecting,
                    selected = row.id in state.selected,
                    onToggle = { onToggle(row.id) },
                    onLongPress = { onStartSelecting(row.id) }
                )
                Box(Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.screenH).height(1.dp).background(colors.divider))
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BgReadingRow(
    row: BgRow,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit
) {
    val colors = AapsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (row.tooClose) AapsSemantic.high.copy(alpha = 0.10f) else colors.background)
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = onLongPress)
            .padding(horizontal = AapsSpacing.screenH, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)
    ) {
        if (selecting)
            Checkbox(
                checked = selected, onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = AapsAccent.accent, uncheckedColor = colors.textTertiary)
            )

        Text(row.time, style = AapsType.body, color = colors.textSecondary, modifier = Modifier.width(78.dp))
        Text(
            row.value,
            style = AapsType.listTitle,
            color = if (row.valid) colors.textPrimary else colors.textTertiary,
            textDecoration = if (row.valid) null else TextDecoration.LineThrough,
            modifier = Modifier.weight(1f)
        )
        Text(row.trend, style = AapsType.listTitle, color = colors.textSecondary)
        if (row.fromNightscout)
            Box(Modifier.size(6.dp).clip(CircleShape).background(AapsAccent.accent))
        else
            Box(Modifier.size(6.dp))
    }
}

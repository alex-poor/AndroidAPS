package app.aaps.plugins.constraints.objectives.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned Objectives journey (handoff Section 8 — Objectives): a collapsed "complete" summary, the
 * current objective card (accent border + progress) and locked future objectives. Read-only view —
 * "Manage & verify" reveals the legacy list where start/verify (the real gating logic) happens.
 */
@Composable
fun ObjectivesJourney(state: ObjectivesUiState, onManage: () -> Unit) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Objectives", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("${state.completed} of ${state.total}", style = AapsType.listTitle, color = colors.textSecondary)
        }

        if (state.completed > 0) {
            AapsCard(
                Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap),
                color = colors.inRange.copy(alpha = 0.10f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.inRange, modifier = Modifier.size(20.dp))
                    Text("Objectives 1–${state.completed} complete", style = AapsType.listTitle, color = colors.textPrimary)
                }
            }
        }

        state.items.filter { it.state != 0 }.forEach { item ->
            when (item.state) {
                1    -> CurrentCard(item)
                else -> LockedCard(item)
            }
        }

        Box(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp)
                .clip(AapsShape.button).background(colors.accentTint).clickable(onClick = onManage).padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Manage & verify objectives", style = AapsType.listTitle, color = colors.accentOnLight)
        }
    }
}

@Composable
private fun CurrentCard(item: ObjItem) {
    val colors = AapsTheme.colors
    AapsCard(
        Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap).border(BorderStroke(1.dp, colors.accent), AapsShape.card)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberBadge(item.number, colors.accent, colors.onAccent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, style = AapsType.listTitle, color = colors.textPrimary)
                if (item.gate.isNotBlank()) Text(item.gate, style = AapsType.caption, color = colors.textTertiary)
                if (item.progress.isNotBlank()) Text(item.progress, style = AapsType.caption, color = colors.accentOnLight, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun LockedCard(item: ObjItem) {
    val colors = AapsTheme.colors
    AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberBadge(item.number, colors.controlFill, colors.textTertiary)
            Text(item.title, style = AapsType.listTitle, color = colors.textTertiary, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun NumberBadge(number: Int, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text("$number", style = AapsType.listTitle, color = fg)
    }
}

package app.aaps.plugins.automation.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned Automation screen (handoff Section 6 — Automation): "When … then …" rule cards with
 * WHEN/THEN chips + an enable switch, and a "+" to add. All actions reuse the existing automation
 * paths — tap a card opens the legacy [app.aaps.plugins.automation.dialogs.EditEventDialog],
 * toggle flips `isEnabled`, long-press removes, "+" adds a new rule.
 */
@Composable
fun AutomationScreen(
    state: AutomationUiState,
    onAdd: () -> Unit,
    onEdit: (position: Int) -> Unit,
    onToggle: (position: Int, enabled: Boolean) -> Unit,
    onRemove: (position: Int) -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Automation", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(colors.accentTint).clickable(onClick = onAdd),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Add, contentDescription = "Add rule", tint = colors.accentOnLight, modifier = Modifier.size(22.dp)) }
        }

        if (state.rules.isEmpty()) {
            Text("No automation rules yet. Tap + to add one.", style = AapsType.body, color = colors.textTertiary, modifier = Modifier.padding(vertical = 12.dp))
        }

        state.rules.forEach { rule ->
            RuleCard(rule, onEdit = { onEdit(rule.position) }, onToggle = { onToggle(rule.position, it) }, onRemove = { onRemove(rule.position) })
        }
        Box(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun RuleCard(rule: AutomationRule, onEdit: () -> Unit, onToggle: (Boolean) -> Unit, onRemove: () -> Unit) {
    val colors = AapsTheme.colors
    AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap), onClick = onEdit) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.title, style = AapsType.listTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = if (rule.readOnly) null else onToggle,
                    enabled = !rule.readOnly,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onAccent,
                        checkedTrackColor = colors.accent,
                        uncheckedTrackColor = colors.controlFill,
                        uncheckedThumbColor = colors.textSecondary,
                        uncheckedBorderColor = colors.hairline
                    )
                )
            }
            if (rule.whenChips.isNotEmpty()) ChipGroup("WHEN", rule.whenChips)
            if (rule.thenChips.isNotEmpty()) ChipGroup("THEN", rule.thenChips)
            if (!rule.readOnly) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tap to edit", style = AapsType.caption, color = colors.textTertiary, modifier = Modifier.weight(1f))
                Text("Remove", style = AapsType.caption, color = colors.low, modifier = Modifier.clickable(onClick = onRemove).padding(4.dp))
            }
        }
    }
}

@Composable
private fun ChipGroup(label: String, chips: List<String>) {
    val colors = AapsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = AapsType.label, color = colors.textSecondary)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { Chip(label = it, onClick = {}, enabled = false) }
        }
    }
}

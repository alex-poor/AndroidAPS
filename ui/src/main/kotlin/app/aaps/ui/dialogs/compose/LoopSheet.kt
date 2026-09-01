package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.components.Dot
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType
import app.aaps.core.compose.theme.color

/**
 * Redesigned Loop control sheet (handoff Section 3). Presents the current status, a mode radio list
 * (Closed / LGS / Open / Disabled), and Suspend / Disconnect chips. Every choice calls back through
 * [onAction] which runs the SAME confirmation + `loop.handleRunningModeChange` path as before.
 */
@Composable
fun LoopSheet(
    state: LoopSheetState,
    onAction: (LoopActionId) -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    SheetSurface(title = "Loop control", onClose = onClose) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // status
            AapsCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(state.statusTone?.color() ?: colors.inRange, glow = state.looping, size = 10.dp)
                    Column(Modifier.padding(start = 10.dp).weight(1f)) {
                        Text(state.statusLabel, style = AapsType.listTitle, color = colors.textPrimary)
                        if (state.algoLine.isNotBlank()) Text(state.algoLine, style = AapsType.caption, color = colors.textTertiary)
                        if (state.enactedLine.isNotBlank()) Text(state.enactedLine, style = AapsType.caption, color = colors.textTertiary)
                        if (state.reasons.isNotBlank()) Text(state.reasons, style = AapsType.caption, color = colors.textTertiary)
                    }
                }
            }

            // mode radio list
            if (state.modes.isNotEmpty()) {
                Text("MODE", style = AapsType.label, color = colors.textSecondary)
                AapsCard(Modifier.fillMaxWidth()) {
                    Column {
                        state.modes.forEach { m -> ModeRow(m, colors, onAction) }
                    }
                }
            }

            // suspend
            if (state.suspendVisible) {
                Text("SUSPEND LOOP", style = AapsType.label, color = colors.textSecondary)
                AapsCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("1h", { onAction(LoopActionId.SUSPEND_1H) }, Modifier.weight(1f))
                        Chip("2h", { onAction(LoopActionId.SUSPEND_2H) }, Modifier.weight(1f))
                        Chip("3h", { onAction(LoopActionId.SUSPEND_3H) }, Modifier.weight(1f))
                        Chip("10h", { onAction(LoopActionId.SUSPEND_10H) }, Modifier.weight(1f))
                    }
                }
            }
            if (state.resumeVisible) FullButton("Resume loop") { onAction(LoopActionId.RESUME) }

            // disconnect
            if (state.disconnectVisible) {
                Text("DISCONNECT PUMP", style = AapsType.label, color = colors.textSecondary)
                AapsCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.disconnect15m) Chip("15m", { onAction(LoopActionId.DISCONNECT_15M) }, Modifier.weight(1f))
                        if (state.disconnect30m) Chip("30m", { onAction(LoopActionId.DISCONNECT_30M) }, Modifier.weight(1f))
                        Chip("1h", { onAction(LoopActionId.DISCONNECT_1H) }, Modifier.weight(1f))
                        Chip("2h", { onAction(LoopActionId.DISCONNECT_2H) }, Modifier.weight(1f))
                        Chip("3h", { onAction(LoopActionId.DISCONNECT_3H) }, Modifier.weight(1f))
                    }
                }
            }
            if (state.reconnectVisible) FullButton("Reconnect pump") { onAction(LoopActionId.RECONNECT) }
        }
    }
}

@Composable
private fun ModeRow(m: LoopModeOption, colors: app.aaps.core.compose.theme.AapsColors, onAction: (LoopActionId) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AapsShape.cardSmall)
            .then(if (m.enabled && !m.selected) Modifier.clickable { onAction(m.id) } else Modifier)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.title, style = AapsType.listTitle, color = if (m.enabled || m.selected) colors.textPrimary else colors.textTertiary)
            if (m.sub.isNotBlank()) Text(m.sub, style = AapsType.caption, color = colors.textTertiary)
        }
        if (m.selected)
            Box(Modifier.size(24.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = "selected", tint = colors.onAccent, modifier = Modifier.size(16.dp))
            }
        else
            Box(Modifier.size(20.dp).clip(CircleShape).background(colors.controlFill))
    }
}

@Composable
private fun FullButton(label: String, onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Text(
        label,
        style = AapsType.title,
        color = colors.onAccent,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(AapsShape.button)
            .background(colors.accent)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    )
}

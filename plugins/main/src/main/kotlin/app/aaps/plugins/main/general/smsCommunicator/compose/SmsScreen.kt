package app.aaps.plugins.main.general.smsCommunicator.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.theme.AapsSemantic
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned SMS & remote screen (handoff Section 7): OTP status banner, allowed-numbers list,
 * remote-command palette, and the message log. Read-only presentation over the SMS plugin state.
 */
@Composable
fun SmsScreen(state: SmsUiState) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Text("SMS & remote", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        // OTP banner
        AapsCard(
            Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap),
            color = if (state.otpOn) AapsSemantic.inRange.copy(alpha = 0.10f) else colors.surface
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(colors.controlFill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = if (state.otpOn) AapsSemantic.inRange else colors.textSecondary, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (state.otpOn) "One-time passwords on" else "One-time passwords off", style = AapsType.listTitle, color = colors.textPrimary)
                    Text(
                        if (state.remoteCommandsOn) "Every command needs a fresh code" else "Remote commands are disabled",
                        style = AapsType.caption, color = colors.textTertiary
                    )
                }
            }
        }

        Text("ALLOWED NUMBERS", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column {
                if (state.allowedNumbers.isEmpty())
                    Text("None configured", style = AapsType.body, color = colors.textTertiary, modifier = Modifier.padding(vertical = 10.dp))
                else state.allowedNumbers.forEachIndexed { i, n ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(colors.accentTint), contentAlignment = Alignment.Center) {
                            Text(n.trim().takeLast(2), style = AapsType.label, color = colors.accentOnLight)
                        }
                        Text(n.trim(), style = AapsType.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Text("REMOTE COMMANDS", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = AapsSpacing.sectionGap), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("BOLUS", "CARBS", "TARGET", "LOOP", "PROFILE").forEach { Chip(label = it, onClick = {}, enabled = false) }
        }

        Text("MESSAGE LOG", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        AapsCard(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column {
                if (state.messages.isEmpty())
                    Text("No messages", style = AapsType.body, color = colors.textTertiary, modifier = Modifier.padding(vertical = 10.dp))
                else state.messages.forEachIndexed { i, m ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(m.time, style = AapsType.caption, color = colors.textTertiary)
                        Text(if (m.incoming) "◀" else "▶", style = AapsType.caption, color = if (m.ignored) colors.textTertiary else colors.accent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(m.number, style = AapsType.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.text, style = AapsType.body, color = colors.textOnSurfaceStrong)
                        }
                        if (m.ignored) Text("ignored", style = AapsType.caption, color = colors.textTertiary)
                        else if (!m.processed) Text("○", style = AapsType.caption, color = colors.textTertiary)
                    }
                }
            }
        }
    }
}

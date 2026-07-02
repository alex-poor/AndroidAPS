package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.Stepper
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned Profile switch sheet (handoff Section 3): profile chips, percentage stepper+chips,
 * timeshift, duration chips, and an "effect at N%" preview. [onApply] runs the SAME validity check +
 * confirmation + `profileFunction.createProfileSwitch` path as the legacy dialog.
 */
@Composable
fun ProfileSwitchSheet(
    state: ProfileSwitchSheetState,
    computeEffect: (profile: String, percent: Int) -> ProfileEffect,
    onApply: (profile: String, percent: Int, timeshiftHours: Int, durationMin: Int) -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    var profile by remember { mutableStateOf(state.selectedProfile) }
    var percent by remember { mutableIntStateOf(state.initialPercentage) }
    var timeshift by remember { mutableIntStateOf(state.initialTimeshift) }
    var durationMin by remember { mutableIntStateOf(0) } // 0 = until I change
    val effect = remember(profile, percent) { computeEffect(profile, percent) }

    SheetSurface(title = "Profile switch", onClose = onClose) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.profiles.size > 1) {
                Text("PROFILE", style = AapsType.label, color = colors.textSecondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.profiles.forEach { p -> Chip(p, { profile = p }, selected = p == profile) }
                }
            }

            Text("PERCENTAGE", style = AapsType.label, color = colors.textSecondary)
            AapsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stepper(
                        value = "$percent%",
                        caption = "of profile basal / ISF / IC",
                        onMinus = { percent = (percent - 5).coerceAtLeast(30) },
                        onPlus = { percent = (percent + 5).coerceAtMost(200) }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(80, 90, 100, 110, 120).forEach { p ->
                            Chip("$p", { percent = p }, Modifier.weight(1f), selected = percent == p)
                        }
                    }
                }
            }

            Text("TIMESHIFT & DURATION", style = AapsType.label, color = colors.textSecondary)
            AapsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Stepper(
                        value = "${if (timeshift > 0) "+" else ""}$timeshift h",
                        caption = "timeshift",
                        onMinus = { timeshift -= 1 },
                        onPlus = { timeshift += 1 }
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip("1h", { durationMin = 60 }, Modifier.weight(1f), selected = durationMin == 60)
                        Chip("2h", { durationMin = 120 }, Modifier.weight(1f), selected = durationMin == 120)
                        Chip("Until I change", { durationMin = 0 }, Modifier.weight(1.6f), selected = durationMin == 0)
                    }
                }
            }

            // effect preview
            if (percent != 100) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(AapsShape.card)
                        .background(colors.inRange.copy(alpha = 0.10f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("EFFECT AT $percent%", style = AapsType.label, color = colors.inRange)
                    EffectRow("Basal", effect.basalBefore, effect.basalAfter, colors)
                    EffectRow("ISF", effect.isfBefore, effect.isfAfter, colors)
                    EffectRow("Carb ratio", effect.icBefore, effect.icAfter, colors)
                }
            }

            Text(
                "Apply profile switch",
                style = AapsType.title,
                color = colors.onAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AapsShape.button)
                    .background(colors.accent)
                    .clickable { onApply(profile, percent, timeshift, durationMin) }
                    .padding(vertical = 14.dp)
            )
        }
    }
}

@Composable
private fun EffectRow(label: String, before: String, after: String, colors: app.aaps.core.compose.theme.AapsColors) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text("$before → ", style = AapsType.body, color = colors.textTertiary)
        Text(after, style = AapsType.listTitle, color = colors.textPrimary)
    }
}

package app.aaps.pump.virtual.compose

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

data class VirtualPumpRow(val label: String, val value: String)

data class VirtualPumpState(
    val suspended: Boolean = false,
    val status: List<VirtualPumpRow> = emptyList(),
    val definition: String = ""
)

/**
 * Redesigned virtual pump tab: delivery state on top, the pump-type capability blob underneath. The
 * "suspended" switch is the one control here, and it writes the same preference the legacy checkbox did.
 */
@Composable
fun VirtualPumpScreen(state: VirtualPumpState, onSuspendedChange: (Boolean) -> Unit) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Virtual pump", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            StatusPill(
                label = if (state.suspended) "Suspended" else "Running",
                dotColor = if (state.suspended) colors.high else colors.inRange
            )
        }

        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Pump suspended", style = AapsTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                Switch(
                    checked = state.suspended,
                    onCheckedChange = onSuspendedChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.accent)
                )
            }
        }

        if (state.status.isNotEmpty())
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Column {
                    state.status.forEachIndexed { i, r ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.label, style = AapsTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                            Text(r.value, style = AapsTheme.type.listTitle, color = colors.textPrimary)
                        }
                    }
                }
            }

        if (state.definition.isNotBlank()) {
            Text("CAPABILITIES", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Text(state.definition, style = AapsTheme.type.caption, color = colors.textSecondary)
            }
        }
    }
}

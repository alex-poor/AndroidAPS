package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * One line in the wizard breakdown. [label] on the left, [value] (an insulin amount, already
 * formatted) on the right; [detail] is the optional secondary text under the label (e.g. the BG /
 * ISF or COB / IC context that the legacy dialog showed). [used] draws a check when the component
 * actually contributed to the calculation (the legacy checkboxes).
 */
data class WizardInfoRow(
    val label: String,
    val value: String,
    val detail: String? = null,
    val used: Boolean? = null
)

/**
 * Presentation state for the read-only bolus-wizard breakdown. All strings are pre-formatted by the
 * hosting [app.aaps.ui.dialogs.WizardInfoDialog] using the same resources/formatters as the legacy
 * XML. [inputs] are the per-component contributions (BG, trend, COB, IOB, carbs, correction,
 * superbolus); [result] holds the summary rows (percentage, total). [profileName] / [notes] are the
 * optional footer readouts (shown only when non-empty).
 */
data class WizardInfoState(
    val inputs: List<WizardInfoRow>,
    val result: List<WizardInfoRow>,
    val profileName: String?,
    val notes: String?
)

/** Read-only bottom sheet showing the breakdown of a bolus-wizard calculation. */
@Composable
fun WizardInfoSheet(state: WizardInfoState, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    SheetSurface(title = "Wizard", onClose = onClose) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
        ) {
            Text("Inputs", style = AapsTheme.type.label, color = colors.textSecondary)
            AapsCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
                    state.inputs.forEach { BreakdownRow(it) }
                }
            }

            Text("Result", style = AapsTheme.type.label, color = colors.textSecondary)
            AapsCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
                    state.result.forEach { BreakdownRow(it) }
                }
            }

            val profile = state.profileName
            val notes = state.notes
            if (!profile.isNullOrBlank() || !notes.isNullOrBlank()) {
                AapsCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
                        if (!profile.isNullOrBlank()) BreakdownRow(WizardInfoRow(label = "Profile", value = profile))
                        if (!notes.isNullOrBlank()) BreakdownRow(WizardInfoRow(label = "Notes", value = notes))
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(row: WizardInfoRow) {
    val colors = AapsTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // check indicator (whether this component was actually used)
        if (row.used != null) {
            if (row.used) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "used",
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                // keep alignment when unused
                Spacer(Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.label, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong)
            row.detail?.let { Text(it, style = AapsTheme.type.caption, color = colors.textTertiary) }
        }
        Text(
            row.value,
            style = AapsTheme.type.body,
            color = if (row.used == false) colors.textTertiary else colors.textPrimary,
            textAlign = TextAlign.End
        )
    }
}

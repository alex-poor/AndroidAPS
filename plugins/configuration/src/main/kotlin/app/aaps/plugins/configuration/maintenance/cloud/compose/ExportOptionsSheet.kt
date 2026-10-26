package app.aaps.plugins.configuration.maintenance.cloud.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SecondaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsTheme

/** The seven export-destination toggles, as saved. */
data class ExportOptionsState(
    val allCloud: Boolean,
    val logEmail: Boolean,
    val logCloud: Boolean,
    val settingsLocal: Boolean,
    val settingsCloud: Boolean,
    val csvLocal: Boolean,
    val csvCloud: Boolean
)

/** The labels for the sheet, resolved by the caller. */
data class ExportOptionsLabels(
    val title: String,
    val allCloud: String,
    val logSection: String,
    val email: String,
    val cloud: String,
    val settingsSection: String,
    val local: String,
    val csvSection: String,
    val ok: String,
    val cancel: String,
    val cloudNotConfigured: String
)

/**
 * Export destinations, as a design-system sheet. Ports the legacy dialog's three
 * rules verbatim: the log and CSV rows are mutually exclusive (exactly one on),
 * the settings boxes are "at least one on" (both allowed), and the master
 * "all cloud" switch forces every row to cloud and disables them while it is on
 * (the settings-local box stays togglable). Enabling cloud anywhere needs cloud
 * configured; the master switch refuses otherwise.
 */
@Composable
fun ExportOptionsSheet(
    labels: ExportOptionsLabels,
    isCloudConfigured: Boolean,
    initial: ExportOptionsState,
    onWarn: (String) -> Unit,
    onSave: (ExportOptionsState) -> Unit,
    onClose: () -> Unit
) {
    var allCloud by remember { mutableStateOf(initial.allCloud) }
    var logEmail by remember { mutableStateOf(initial.logEmail) }
    var logCloud by remember { mutableStateOf(initial.logCloud) }
    var settingsLocal by remember { mutableStateOf(initial.settingsLocal) }
    var settingsCloud by remember { mutableStateOf(initial.settingsCloud) }
    var csvLocal by remember { mutableStateOf(initial.csvLocal) }
    var csvCloud by remember { mutableStateOf(initial.csvCloud) }

    // Per-row enabled state, derived the same way the legacy listeners set it.
    val perRowOn = !allCloud
    val cloudRowOn = perRowOn && isCloudConfigured

    SheetSurface(title = labels.title, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleRow(labels.allCloud, allCloud, enabled = isCloudConfigured || allCloud) { on ->
                if (on && !isCloudConfigured) {
                    onWarn(labels.cloudNotConfigured)
                } else {
                    allCloud = on
                    if (on) { logCloud = true; settingsCloud = true; csvCloud = true; logEmail = false; csvLocal = false }
                }
            }

            Section(labels.logSection)
            ToggleRow(labels.email, logEmail, enabled = perRowOn) { on ->
                logEmail = on
                if (on) logCloud = false
                if (!on && !logCloud) logEmail = true   // at least one
            }
            ToggleRow(labels.cloud, logCloud, enabled = cloudRowOn) { on ->
                logCloud = on
                if (on) logEmail = false
                if (!on && !logEmail) logCloud = true
            }

            Section(labels.settingsSection)
            CheckRow(labels.local, settingsLocal, enabled = perRowOn) { on ->
                settingsLocal = on
                if (!on && !settingsCloud) settingsLocal = true
            }
            CheckRow(labels.cloud, settingsCloud, enabled = cloudRowOn) { on ->
                settingsCloud = on
                if (!on && !settingsLocal) settingsCloud = true
            }

            Section(labels.csvSection)
            ToggleRow(labels.local, csvLocal, enabled = perRowOn) { on ->
                csvLocal = on
                if (on) csvCloud = false
                if (!on && !csvCloud) csvLocal = true
            }
            ToggleRow(labels.cloud, csvCloud, enabled = cloudRowOn) { on ->
                csvCloud = on
                if (on) csvLocal = false
                if (!on && !csvLocal) csvCloud = true
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(labels.cancel, onClose, Modifier.weight(1f))
                PrimaryButton(labels.ok, {
                    onSave(ExportOptionsState(allCloud, logEmail, logCloud, settingsLocal, settingsCloud, csvLocal, csvCloud))
                    onClose()
                }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = AapsTheme.type.label, color = AapsTheme.colors.textSecondary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AapsTheme.type.body, color = if (enabled) AapsTheme.colors.textPrimary else AapsTheme.colors.textTertiary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AapsTheme.type.body, color = if (enabled) AapsTheme.colors.textPrimary else AapsTheme.colors.textTertiary, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

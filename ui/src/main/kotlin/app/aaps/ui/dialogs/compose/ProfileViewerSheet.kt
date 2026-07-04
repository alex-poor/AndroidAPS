package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * One time-block row for the read-only profile viewer: a start-time label plus the formatted value.
 * [value2] is populated only in PROFILE_COMPARE mode (the second, examined profile).
 */
data class ProfileViewerRow(val time: String, val value: String, val value2: String? = null)

/** One labeled category (Basal / ISF / Carb ratio / Target) as a list of time-block rows. */
data class ProfileViewerCategory(val label: String, val rows: List<ProfileViewerRow>)

/**
 * Presentation params for the redesigned read-only profile viewer sheet. All values are pre-extracted
 * (via [app.aaps.ui.dialogs.ProfileViewerDialog.buildState]) so this composable stays logic-free.
 *
 * @param compare when true a second value column is shown per row (PROFILE_COMPARE mode).
 * @param name2   the examined profile's name (compare mode only); rendered under [name].
 */
data class ProfileViewerState(
    val name: String,
    val name2: String? = null,
    val dia: String,
    val dailyBasal: String,
    val dailyBasal2: String? = null,
    val date: String? = null,
    val unitsLabel: String,
    val invalid: String? = null,
    val compare: Boolean = false,
    val basal: ProfileViewerCategory,
    val isf: ProfileViewerCategory,
    val ic: ProfileViewerCategory,
    val target: ProfileViewerCategory
)

/**
 * Read-only profile viewer bottom sheet. Renders a header (profile name(s), DIA, optional date/units),
 * a tab selector, and per-category time-block lists inside [AapsCard]s. No submit — presentation only.
 */
@Composable
fun ProfileViewerSheet(state: ProfileViewerState, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    val categories = listOf(state.basal, state.isf, state.ic, state.target)
    var tab by remember { mutableStateOf(0) }
    val current = categories[tab]

    SheetSurface(title = state.name.ifBlank { "Profile" }, onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {

            // Header card — DIA + units + optional compared name / date.
            AapsCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.compare && state.name2 != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.name, style = AapsType.listTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
                            Text(state.name2, style = AapsType.listTitle, color = colors.accent)
                        }
                    }
                    if (!state.date.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Date", style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                            Text(state.date, style = AapsType.listTitle, color = colors.textPrimary)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Insulin duration (DIA)", style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(state.dia, style = AapsType.listTitle, color = colors.textPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Units", style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(state.unitsLabel, style = AapsType.listTitle, color = colors.textPrimary)
                    }
                }
            }

            if (!state.invalid.isNullOrBlank()) {
                AapsCard(Modifier.fillMaxWidth(), color = colors.surface2) {
                    Text(state.invalid, style = AapsType.body, color = colors.low)
                }
            }

            SegmentedControl(
                options = categories.map { it.label },
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.fillMaxWidth()
            )

            // Basal daily total badge (basal tab only).
            if (tab == 0) {
                AapsCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                        Text(state.dailyBasal, style = AapsType.listTitle, color = colors.textPrimary)
                        if (state.compare && state.dailyBasal2 != null)
                            Text("  ${state.dailyBasal2}", style = AapsType.listTitle, color = colors.accent)
                    }
                }
            }

            AapsCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column {
                    current.rows.forEachIndexed { i, r ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.time, style = AapsType.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
                            Text(r.value, style = AapsType.listTitle, color = colors.textPrimary, textAlign = TextAlign.End)
                            if (state.compare && r.value2 != null)
                                Text("  ${r.value2}", style = AapsType.listTitle, color = colors.accent, textAlign = TextAlign.End)
                        }
                    }
                    if (current.rows.isEmpty())
                        Text("No data", style = AapsType.body, color = colors.textTertiary, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}

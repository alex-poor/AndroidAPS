package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.theme.AapsSpacing

/** Presentation params for the redesigned Prime / Fill sheet. */
data class FillSheetState(
    val maxInsulin: Double,
    val insulinStep: Double,
    val insulinDecimals: Int,
    /** Fill-button presets from prefs; only values > 0 are passed in. */
    val presets: List<Double>,
    val showNotes: Boolean
)

/** Values the legacy [submit] path needs. */
data class FillInputs(
    val insulin: Double,
    val siteChange: Boolean,
    val insulinChange: Boolean,
    val notes: String
)

/**
 * Redesigned Prime / Fill sheet. [onSubmit] runs the SAME constraint + `OKDialog` confirmation +
 * prime-`commandQueue.bolus` + site/insulin-change persistence path as the legacy dialog.
 */
@Composable
fun FillSheet(state: FillSheetState, onSubmit: (FillInputs) -> Unit, onClose: () -> Unit) {
    var insulin by remember { mutableStateOf(0.0) }
    var siteChange by remember { mutableStateOf(false) }
    var insulinChange by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    SheetSurface(title = "Prime / Fill", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
            NumberField(
                label = "Insulin", value = insulin, onValue = { insulin = it },
                step = state.insulinStep, min = 0.0, max = state.maxInsulin, decimals = state.insulinDecimals, unit = "U",
                modifier = Modifier.fillMaxWidth()
            )
            if (state.presets.isNotEmpty())
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
                    state.presets.forEach { preset ->
                        Chip(label = String.format(java.util.Locale.getDefault(), "%.${state.insulinDecimals}f", preset), onClick = { insulin = preset.coerceIn(0.0, state.maxInsulin) })
                    }
                }

            ToggleRow("Pump site change", siteChange, { siteChange = it }, sub = "Record cannula change")
            ToggleRow("Insulin cartridge change", insulinChange, { insulinChange = it }, sub = "Record reservoir change")

            if (state.showNotes) NotesField(notes, { notes = it })

            PrimaryButton(
                label = "Confirm",
                enabled = insulin > 0.0 || siteChange || insulinChange,
                onClick = { onSubmit(FillInputs(insulin, siteChange, insulinChange, notes)) }
            )
        }
    }
}

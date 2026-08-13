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
    val notes: String,
    /**
     * How long ago the site/cartridge change actually happened, in minutes. Back-dates ONLY the
     * therapy events -- any prime bolus is still delivered now, because insulin cannot be given
     * retroactively.
     *
     * Exists because a cannula change is routinely made away from the phone and recorded later, and
     * until now this sheet stamped `dateUtil.now()` with no way to correct it. An hours-late
     * timestamp is not a cosmetic problem: site age drives the post-change analysis and the
     * HovorkaMPC site guard, and a change that is never recorded at all (as happened 2026-08-04 and
     * 2026-08-13) is invisible to both.
     */
    val minutesAgo: Int
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
    var minutesAgo by remember { mutableStateOf(0.0) }

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

            // Only meaningful when something is being recorded; a prime on its own always happens now.
            if (siteChange || insulinChange) {
                NumberField(
                    label = "Changed", value = minutesAgo, onValue = { minutesAgo = it },
                    step = 5.0, min = 0.0, max = 1440.0, decimals = 0, unit = "min ago",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
                    listOf("now" to 0, "30m" to 30, "1h" to 60, "2h" to 120, "4h" to 240, "8h" to 480, "12h" to 720)
                        .forEach { (label, mins) -> Chip(label = label, onClick = { minutesAgo = mins.toDouble() }) }
                }
            }

            if (state.showNotes) NotesField(notes, { notes = it })

            PrimaryButton(
                label = "Confirm",
                enabled = insulin > 0.0 || siteChange || insulinChange,
                onClick = {
                    onSubmit(
                        FillInputs(
                            insulin, siteChange, insulinChange, notes,
                            // back-dating only applies to what is being recorded
                            minutesAgo = if (siteChange || insulinChange) minutesAgo.toInt() else 0
                        )
                    )
                }
            )
        }
    }
}

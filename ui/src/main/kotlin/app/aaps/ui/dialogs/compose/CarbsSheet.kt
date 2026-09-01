package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.components.NotesField
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

enum class CarbTt { NONE, ACTIVITY, EATING_SOON, HYPO }

data class CarbsSheetState(
    val maxCarbs: Double,
    val quickIncrements: List<Int>,
    val maxDurationHours: Int,
    val autoHypoTt: Boolean,
    val showBolusReminder: Boolean
)

data class CarbsInputs(
    val carbs: Int,
    val timeOffsetMin: Int,
    val durationHours: Int,
    val tt: CarbTt,
    val useAlarm: Boolean,
    val remindBolus: Boolean,
    val notes: String
)

/**
 * Redesigned Carbs sheet. [onSubmit] runs the SAME constraint + `OKDialog` confirmation + temp-target /
 * `commandQueue.bolus` (carbs) + automation-reminder path as the legacy dialog.
 */
@Composable
fun CarbsSheet(state: CarbsSheetState, onSubmit: (CarbsInputs) -> Unit, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    var carbs by remember { mutableStateOf(0.0) }
    var timeOffset by remember { mutableStateOf(0.0) }
    var duration by remember { mutableStateOf(0.0) }
    var tt by remember { mutableStateOf(if (state.autoHypoTt) CarbTt.HYPO else CarbTt.NONE) }
    var alarm by remember { mutableStateOf(false) }
    var remindBolus by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    SheetSurface(title = "Carbs", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
            NumberField("Carbs", carbs, { carbs = it }, step = 1.0, min = -state.maxCarbs, max = state.maxCarbs, decimals = 0, unit = "g", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.quickIncrements.forEach { inc ->
                    Chip(label = (if (inc > 0) "+$inc" else "$inc"), onClick = { carbs = (carbs + inc).coerceIn(-state.maxCarbs, state.maxCarbs) })
                }
            }

            Text("PRE-SET TEMP TARGET", style = AapsTheme.type.label, color = colors.textSecondary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("None", onClick = { tt = CarbTt.NONE }, selected = tt == CarbTt.NONE)
                Chip("Activity", onClick = { tt = CarbTt.ACTIVITY }, selected = tt == CarbTt.ACTIVITY)
                Chip("Eating soon", onClick = { tt = CarbTt.EATING_SOON }, selected = tt == CarbTt.EATING_SOON)
                Chip("Hypo", onClick = { tt = CarbTt.HYPO }, selected = tt == CarbTt.HYPO)
            }

            NumberField("Time", timeOffset, { timeOffset = it }, step = 5.0, min = -7 * 24 * 60.0, max = 12 * 60.0, decimals = 0, unit = "min", modifier = Modifier.fillMaxWidth())
            NumberField("Extended over", duration, { duration = it }, step = 1.0, min = 0.0, max = state.maxDurationHours.toDouble(), decimals = 0, unit = "h", modifier = Modifier.fillMaxWidth())

            ToggleRow("Remind me to eat", alarm, { alarm = it }, sub = "Alarm at the chosen time offset")
            if (state.showBolusReminder) ToggleRow("Remind me to bolus", remindBolus, { remindBolus = it })
            NotesField(notes, { notes = it })

            PrimaryButton(
                label = "Add carbs",
                enabled = carbs != 0.0 || tt != CarbTt.NONE,
                onClick = { onSubmit(CarbsInputs(carbs.toInt(), timeOffset.toInt(), duration.toInt(), tt, alarm, remindBolus, notes)) }
            )
        }
    }
}

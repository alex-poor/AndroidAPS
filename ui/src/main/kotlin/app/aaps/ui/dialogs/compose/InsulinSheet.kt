package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

data class InsulinSheetState(
    val maxInsulin: Double,
    val bolusStep: Double,
    val decimals: Int,
    val quickIncrements: List<Double>,
    val forceRecordOnly: Boolean,
    val suspendedWarning: Boolean
)

data class InsulinInputs(
    val amount: Double,
    val recordOnly: Boolean,
    val timeOffsetMin: Int,
    val eatingSoon: Boolean,
    val notes: String
)

/** Redesigned Insulin (careportal bolus) sheet. [onSubmit] runs the same constraint + confirm + deliver/record path. */
@Composable
fun InsulinSheet(state: InsulinSheetState, onSubmit: (InsulinInputs) -> Unit, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    var amount by remember { mutableStateOf(0.0) }
    var recordOnly by remember { mutableStateOf(state.forceRecordOnly) }
    var timeOffset by remember { mutableStateOf(0.0) }
    var eatingSoon by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }

    fun fmtInc(v: Double) = (if (v > 0) "+" else "") + String.format(java.util.Locale.getDefault(), "%.${state.decimals}f", v)

    SheetSurface(title = "Insulin", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
            if (state.suspendedWarning)
                Text("Pump is suspended / not ready — this will be recorded only.", style = AapsTheme.type.caption, color = colors.high)
            NumberField("Insulin", amount, { amount = it }, step = state.bolusStep, min = 0.0, max = state.maxInsulin, decimals = state.decimals, unit = "U", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.quickIncrements.forEach { inc -> Chip(fmtInc(inc), onClick = { amount = (amount + inc).coerceIn(0.0, state.maxInsulin) }) }
            }
            ToggleRow("Record only", recordOnly, { if (!state.forceRecordOnly) recordOnly = it }, sub = "Log without delivering")
            if (recordOnly)
                NumberField("Time", timeOffset, { timeOffset = it }, step = 5.0, min = -12 * 60.0, max = 12 * 60.0, decimals = 0, unit = "min", modifier = Modifier.fillMaxWidth())
            ToggleRow("Start eating-soon temp target", eatingSoon, { eatingSoon = it })
            NotesField(notes, { notes = it })
            PrimaryButton(
                label = if (recordOnly) "Record" else "Deliver",
                enabled = amount > 0.0 || eatingSoon,
                onClick = { onSubmit(InsulinInputs(amount, recordOnly, timeOffset.toInt(), eatingSoon, notes)) }
            )
        }
    }
}

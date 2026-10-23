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
    val suspendedWarning: Boolean,
    /** Pre-fill the amount field (e.g. when opened to deliver a scheduled second dose). */
    val initialAmount: Double = 0.0
)

data class InsulinInputs(
    val amount: Double,
    val recordOnly: Boolean,
    val timeOffsetMin: Int,
    val eatingSoon: Boolean,
    val notes: String,
    /** Split bolus: percent of [amount] to deliver NOW (100 = no split). Remainder scheduled at [splitOffsetMin]. */
    val splitNowPercent: Int = 100,
    /** Minutes from now the scheduled remainder should fire. Ignored when [splitNowPercent] == 100. */
    val splitOffsetMin: Int = 0
)

/** Redesigned Insulin (careportal bolus) sheet. [onSubmit] runs the same constraint + confirm + deliver/record path. */
@Composable
fun InsulinSheet(state: InsulinSheetState, onSubmit: (InsulinInputs) -> Unit, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    var amount by remember { mutableStateOf(state.initialAmount) }
    var recordOnly by remember { mutableStateOf(state.forceRecordOnly) }
    var timeOffset by remember { mutableStateOf(0.0) }
    var eatingSoon by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var splitOn by remember { mutableStateOf(false) }
    var splitNowPercent by remember { mutableStateOf(60.0) }
    var splitOffset by remember { mutableStateOf(45.0) }

    fun fmtInc(v: Double) = (if (v > 0) "+" else "") + String.format(java.util.Locale.getDefault(), "%.${state.decimals}f", v)

    // Split is only meaningful for a dose that actually reaches the pump.
    val splitActive = splitOn && !recordOnly && amount > 0.0
    val doseNow = if (splitActive) amount * splitNowPercent / 100.0 else amount
    val doseLater = if (splitActive) amount - doseNow else 0.0

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
            if (!recordOnly) {
                ToggleRow("Split this dose", splitOn, { splitOn = it }, sub = "Deliver part now, schedule the rest")
                if (splitActive) {
                    NumberField("Deliver now", splitNowPercent, { splitNowPercent = it.coerceIn(10.0, 90.0) }, step = 5.0, min = 10.0, max = 90.0, decimals = 0, unit = "%", modifier = Modifier.fillMaxWidth())
                    NumberField("Second dose in", splitOffset, { splitOffset = it.coerceIn(5.0, 240.0) }, step = 5.0, min = 5.0, max = 240.0, decimals = 0, unit = "min", modifier = Modifier.fillMaxWidth())
                    Text(
                        "Now: ${String.format(java.util.Locale.getDefault(), "%.${state.decimals}f", doseNow)} U · " +
                            "in ${splitOffset.toInt()} min: ${String.format(java.util.Locale.getDefault(), "%.${state.decimals}f", doseLater)} U (you'll confirm it)",
                        style = AapsTheme.type.caption, color = colors.textTertiary
                    )
                }
            }
            ToggleRow("Start eating-soon temp target", eatingSoon, { eatingSoon = it })
            NotesField(notes, { notes = it })
            PrimaryButton(
                label = if (recordOnly) "Record" else if (splitActive) "Deliver ${String.format(java.util.Locale.getDefault(), "%.${state.decimals}f", doseNow)} U now" else "Deliver",
                enabled = amount > 0.0 || eatingSoon,
                onClick = {
                    onSubmit(
                        InsulinInputs(
                            amount, recordOnly, timeOffset.toInt(), eatingSoon, notes,
                            splitNowPercent = if (splitActive) splitNowPercent.toInt() else 100,
                            splitOffsetMin = if (splitActive) splitOffset.toInt() else 0
                        )
                    )
                }
            )
        }
    }
}

package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface

/** Presentation params for the redesigned Extended Bolus sheet. */
data class ExtendedBolusSheetState(
    val maxInsulin: Double,
    val insulinStep: Double,
    val insulinDecimals: Int,
    val durationStep: Double,
    val maxDuration: Double
)

/** Values captured by the Extended Bolus sheet, handed to the legacy submit path. */
data class ExtendedBolusInputs(
    val insulin: Double,
    val durationMin: Int
)

/**
 * Redesigned Extended Bolus sheet. [onSubmit] runs the SAME constraint + `OKDialog` confirmation +
 * `commandQueue.extendedBolus` path as the legacy dialog.
 */
@Composable
fun ExtendedBolusSheet(state: ExtendedBolusSheetState, onSubmit: (ExtendedBolusInputs) -> Unit, onClose: () -> Unit) {
    var insulin by remember { mutableStateOf(state.insulinStep) }
    var duration by remember { mutableStateOf(state.durationStep) }

    SheetSurface(title = "Extended bolus", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            NumberField(
                label = "Insulin", value = insulin, onValue = { insulin = it },
                step = state.insulinStep, min = 0.0, max = state.maxInsulin, decimals = state.insulinDecimals, unit = "U",
                modifier = Modifier.fillMaxWidth()
            )
            NumberField(
                label = "Duration", value = duration, onValue = { duration = it },
                step = state.durationStep, min = 0.0, max = state.maxDuration, decimals = 0, unit = "min",
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton(
                label = "Set extended bolus",
                onClick = { onSubmit(ExtendedBolusInputs(insulin = insulin, durationMin = duration.toInt())) },
                enabled = insulin > 0.0 && duration > 0.0
            )
        }
    }
}

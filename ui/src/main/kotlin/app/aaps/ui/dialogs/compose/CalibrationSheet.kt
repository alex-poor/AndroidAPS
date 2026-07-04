package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsSpacing

data class CalibrationSheetState(
    val initial: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    val decimals: Int,
    val unitLabel: String
)

/** Redesigned Calibration sheet. [onSend] runs the same `OKDialog` confirm + `xDripBroadcast.sendCalibration`. */
@Composable
fun CalibrationSheet(state: CalibrationSheetState, onSend: (Double) -> Unit, onClose: () -> Unit) {
    var bg by remember { mutableStateOf(state.initial) }
    SheetSurface(title = "Calibration", onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
            NumberField("Blood glucose", bg, { bg = it }, step = state.step, min = state.min, max = state.max, decimals = state.decimals, unit = state.unitLabel, modifier = Modifier.fillMaxWidth())
            PrimaryButton("Send calibration", onClick = { onSend(bg) }, enabled = bg > 0.0)
        }
    }
}

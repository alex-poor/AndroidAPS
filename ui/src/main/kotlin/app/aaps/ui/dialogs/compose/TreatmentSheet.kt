package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.layout.Arrangement
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
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsTheme

/** Presentation params for the redesigned Treatment (Bolus + Carbs) sheet. */
data class TreatmentSheetState(
    val maxInsulin: Double,
    val insulinStep: Double,
    val insulinDecimals: Int,
    val maxCarbs: Double,
    val recordOnly: Boolean
)

/**
 * Redesigned Treatment sheet — the bottom-bar "Bolus" entry (bolus + carbs). [onDeliver] runs the
 * SAME constraint + `OKDialog` confirmation + `commandQueue.bolus` path as the legacy dialog.
 */
@Composable
fun TreatmentSheet(state: TreatmentSheetState, onDeliver: (insulin: Double, carbs: Double) -> Unit, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    var insulin by remember { mutableStateOf(0.0) }
    var carbs by remember { mutableStateOf(0.0) }

    SheetSurface(title = "Bolus / Carbs", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            NumberField(
                label = "Insulin", value = insulin, onValue = { insulin = it },
                step = state.insulinStep, min = 0.0, max = state.maxInsulin, decimals = state.insulinDecimals, unit = "U",
                modifier = Modifier.fillMaxWidth()
            )
            NumberField(
                label = "Carbs", value = carbs, onValue = { carbs = it },
                step = 1.0, min = 0.0, max = state.maxCarbs, decimals = 0, unit = "g",
                modifier = Modifier.fillMaxWidth()
            )
            if (state.recordOnly)
                Text("Follower mode — this will be recorded only, not delivered.", style = AapsTheme.type.caption, color = colors.textTertiary)
            PrimaryButton(
                label = if (state.recordOnly) "Record" else "Deliver",
                onClick = { onDeliver(insulin, carbs) },
                enabled = insulin > 0.0 || carbs > 0.0
            )
        }
    }
}

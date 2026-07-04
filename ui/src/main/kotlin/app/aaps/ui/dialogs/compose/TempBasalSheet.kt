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
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsSpacing

/**
 * Presentation params for the redesigned Temp Basal sheet. The pump determines which modes are
 * allowed (percent / absolute); the SegmentedControl is only shown when BOTH are allowed. Each mode
 * carries its own default / min / max / step so the rate field re-parameterises on toggle.
 */
data class TempBasalSheetState(
    val percentAllowed: Boolean,
    val absoluteAllowed: Boolean,
    val defaultIsPercent: Boolean,
    val percentDefault: Double,
    val percentMax: Double,
    val percentStep: Double,
    val absoluteDefault: Double,
    val absoluteMax: Double,
    val absoluteStep: Double,
    val absoluteDecimals: Int,
    val durationDefault: Double,
    val durationMax: Double,
    val durationStep: Double
)

data class TempBasalInputs(
    val isPercent: Boolean,
    val value: Double,
    val durationMin: Int
)

/**
 * Redesigned Temp Basal sheet. [onSubmit] runs the SAME constraint + `OKDialog` confirmation +
 * `commandQueue.tempBasalPercent` / `tempBasalAbsolute` path as the legacy dialog.
 */
@Composable
fun TempBasalSheet(state: TempBasalSheetState, onSubmit: (TempBasalInputs) -> Unit, onClose: () -> Unit) {
    val showToggle = state.percentAllowed && state.absoluteAllowed
    var isPercent by remember { mutableStateOf(state.defaultIsPercent) }
    var percent by remember { mutableStateOf(state.percentDefault) }
    var absolute by remember { mutableStateOf(state.absoluteDefault) }
    var duration by remember { mutableStateOf(state.durationDefault) }

    SheetSurface(title = "Temp basal", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
            if (showToggle) {
                SegmentedControl(
                    options = listOf("Percent", "Absolute"),
                    selectedIndex = if (isPercent) 0 else 1,
                    onSelect = { isPercent = it == 0 },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (isPercent) {
                NumberField(
                    label = "Rate", value = percent, onValue = { percent = it },
                    step = state.percentStep, min = 0.0, max = state.percentMax, decimals = 0, unit = "%",
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                NumberField(
                    label = "Rate", value = absolute, onValue = { absolute = it },
                    step = state.absoluteStep, min = 0.0, max = state.absoluteMax, decimals = state.absoluteDecimals, unit = "U/h",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            NumberField(
                label = "Duration", value = duration, onValue = { duration = it },
                step = state.durationStep, min = state.durationStep, max = state.durationMax, decimals = 0, unit = "min",
                modifier = Modifier.fillMaxWidth()
            )
            PrimaryButton(
                label = "Set temp basal",
                onClick = { onSubmit(TempBasalInputs(isPercent, if (isPercent) percent else absolute, duration.toInt())) }
            )
        }
    }
}

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
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/** Which glucose source the user tapped (mirrors the legacy meter/sensor/other radio group). */
enum class CareMeter { METER, SENSOR, MANUAL }

/**
 * Presentation params for the redesigned generic careportal [CareSheet]. Everything visibility-related
 * is derived by [app.aaps.ui.dialogs.CareDialog] from the [app.aaps.core.interfaces.ui.UiInteraction.EventType]
 * (same logic the legacy XML-driven dialog used).
 */
data class CareSheetState(
    val title: String,
    val submitLabel: String,
    val showBg: Boolean,
    val bgInitial: Double,
    val bgMin: Double,
    val bgMax: Double,
    val bgStep: Double,
    val bgDecimals: Int,
    val bgUnit: String,
    val showDuration: Boolean,
    val durationMax: Double,
    val durationStep: Double,
    val showNotes: Boolean
)

/** Values read back by [app.aaps.ui.dialogs.CareDialog.submit] instead of the legacy binding views. */
data class CareInputs(
    val bg: Double,
    val meter: CareMeter,
    val durationMin: Int,
    val notes: String
)

/**
 * Redesigned generic careportal event sheet. [onSubmit] runs the SAME `TE` construction +
 * `persistenceLayer.insertPumpTherapyEventIfNewByTimestamp` + `uel.log` + `OKDialog` confirmation path
 * as the legacy dialog; only the input source changes (Compose state → [CareInputs]).
 */
@Composable
fun CareSheet(state: CareSheetState, onSubmit: (CareInputs) -> Unit, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    var bg by remember { mutableStateOf(state.bgInitial) }
    // Default matches the legacy layout (sensor radio checked by default).
    var meter by remember { mutableStateOf(CareMeter.SENSOR) }
    var duration by remember { mutableStateOf(0.0) }
    var notes by remember { mutableStateOf("") }

    SheetSurface(title = state.title, onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
            if (state.showBg) {
                NumberField(
                    label = "Glucose", value = bg, onValue = { bg = it },
                    step = state.bgStep, min = state.bgMin, max = state.bgMax, decimals = state.bgDecimals, unit = state.bgUnit,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("SOURCE", style = AapsType.label, color = colors.textSecondary)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Meter", onClick = { meter = CareMeter.METER }, selected = meter == CareMeter.METER)
                    Chip("Sensor", onClick = { meter = CareMeter.SENSOR }, selected = meter == CareMeter.SENSOR)
                    Chip("Other", onClick = { meter = CareMeter.MANUAL }, selected = meter == CareMeter.MANUAL)
                }
            }

            if (state.showDuration)
                NumberField(
                    label = "Duration", value = duration, onValue = { duration = it },
                    step = state.durationStep, min = 0.0, max = state.durationMax, decimals = 0, unit = "min",
                    modifier = Modifier.fillMaxWidth()
                )

            if (state.showNotes) NotesField(notes, { notes = it })

            PrimaryButton(
                label = state.submitLabel,
                onClick = { onSubmit(CareInputs(bg = bg, meter = meter, durationMin = duration.toInt(), notes = notes)) }
            )
        }
    }
}

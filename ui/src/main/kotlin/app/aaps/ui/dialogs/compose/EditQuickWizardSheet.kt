package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Presentation params for the redesigned Edit Quick Wizard (config) sheet. All numeric/tri-state
 * fields are seeded from the [app.aaps.core.objects.wizard.QuickWizardEntry] by the host; the
 * from/to time-of-day pickers are owned by the host (MaterialTimePicker) and reflected back here via
 * [fromDisplay] / [toDisplay] (which the host updates through mutableStateOf).
 *
 * Tri-state values use the entry's raw ints (SegmentedControl index 0=Default, 1=Yes, 2=No maps to
 * [triFrom]/[triToInt]). [useTrend] keeps its fuller mapping (see the host).
 */
data class EditQuickWizardState(
    val buttonText: String,
    val carbs: Double,
    val maxCarbs: Double,
    val carbTime: Double,
    val percentage: Double,
    val minPercentage: Double,
    val maxPercentage: Double,
    // tri-state ints (QuickWizardEntry.YES / NO)
    val useBG: Int,
    val useCOB: Int,
    val useIOB: Int,
    val usePositiveIOBOnly: Int,
    val useTrend: Int,
    val useSuperBolus: Int,
    val useTempTarget: Int,
    val useAlarm: Int,
    // eCarbs
    val useEcarbs: Int,
    val carbs2: Double,
    val timeOffset: Double,
    val durationHours: Double,
    // visibility toggles
    val showDevice: Boolean,
    val showSuperBolus: Boolean,
    val devicePhone: Boolean,
    val deviceWatch: Boolean,
    // from/to display strings (owned + updated by the host)
    val fromDisplay: String,
    val toDisplay: String
)

/** All edited values, mapped 1:1 back onto the entry by the host in the SAME order the legacy did. */
data class EditQuickWizardResult(
    val buttonText: String,
    val carbs: Int,
    val carbTime: Int,
    val percentage: Int,
    val useBG: Int,
    val useCOB: Int,
    val useIOB: Int,
    val usePositiveIOBOnly: Int,
    val useTrend: Int,
    val useSuperBolus: Int,
    val useTempTarget: Int,
    val useAlarm: Int,
    val useEcarbs: Int,
    val carbs2: Int,
    val time: Int,
    val duration: Int,
    val devicePhone: Boolean,
    val deviceWatch: Boolean
)

// Tri-state SegmentedControl options. Index 0=Default, 1=Yes, 2=No.
private val TRI_OPTIONS = listOf("Default", "Yes", "No")

// Tri-state (Yes/Positive/Negative/No) for the trend selector.
private val TREND_OPTIONS = listOf("No", "Yes", "Positive only", "Negative only")

@Composable
fun EditQuickWizardSheet(
    state: EditQuickWizardState,
    onSave: (EditQuickWizardResult) -> Unit,
    onClose: () -> Unit,
    onPickFrom: () -> Unit,
    onPickTo: () -> Unit
) {
    val colors = AapsTheme.colors

    var buttonText by remember { mutableStateOf(state.buttonText) }
    var carbs by remember { mutableStateOf(state.carbs) }
    var carbTime by remember { mutableStateOf(state.carbTime) }
    var percentage by remember { mutableStateOf(state.percentage) }

    var useBG by remember { mutableStateOf(triFrom(state.useBG)) }
    var useCOB by remember { mutableStateOf(triFrom(state.useCOB)) }
    var useIOB by remember { mutableStateOf(triFrom(state.useIOB)) }
    var usePositiveIOBOnly by remember { mutableStateOf(triFrom(state.usePositiveIOBOnly)) }
    var useTrend by remember { mutableStateOf(trendFrom(state.useTrend)) }
    var useSuperBolus by remember { mutableStateOf(triFrom(state.useSuperBolus)) }
    var useTempTarget by remember { mutableStateOf(triFrom(state.useTempTarget)) }
    var useAlarm by remember { mutableStateOf(triFrom(state.useAlarm)) }

    var useEcarbs by remember { mutableStateOf(triFrom(state.useEcarbs)) }
    var carbs2 by remember { mutableStateOf(state.carbs2) }
    var timeOffset by remember { mutableStateOf(state.timeOffset) }
    var duration by remember { mutableStateOf(state.durationHours) }

    var devicePhone by remember { mutableStateOf(state.devicePhone) }
    var deviceWatch by remember { mutableStateOf(state.deviceWatch) }

    SheetSurface(title = "Edit Quick Wizard", onClose = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {

            LabeledTextField(label = "BUTTON TEXT", value = buttonText, onValue = { buttonText = it }, placeholder = "e.g. Meal")

            NumberField(
                "Carbs", carbs, { carbs = it }, step = 1.0, min = 0.0, max = state.maxCarbs, decimals = 0, unit = "g",
                modifier = Modifier.fillMaxWidth()
            )

            // Valid time-of-day window — pickers live in the host.
            Text("VALID", style = AapsType.label, color = colors.textSecondary)
            TimeRow(label = "From", display = state.fromDisplay, onClick = onPickFrom)
            TimeRow(label = "To", display = state.toDisplay, onClick = onPickTo)

            NumberField(
                "Carb time", carbTime, { carbTime = it }, step = 5.0, min = -60.0, max = 60.0, decimals = 0, unit = "min",
                modifier = Modifier.fillMaxWidth()
            )
            ToggleRow("Alarm", useAlarm == TRI_YES, { useAlarm = if (it) TRI_YES else TRI_NO })

            NumberField(
                "Correction %", percentage, { percentage = it }, step = 5.0, min = state.minPercentage, max = state.maxPercentage,
                decimals = 0, unit = "%", modifier = Modifier.fillMaxWidth()
            )

            TriRow("Use BG", useBG) { useBG = it }
            TriRow("Use COB", useCOB) { useCOB = it }
            TriRow("Use IOB", useIOB) { useIOB = it }
            TriRow("Positive IOB only", usePositiveIOBOnly) { usePositiveIOBOnly = it }

            Text("USE TREND", style = AapsType.label, color = colors.textSecondary)
            SegmentedControl(TREND_OPTIONS, useTrend, { useTrend = it }, modifier = Modifier.fillMaxWidth())

            if (state.showSuperBolus) TriRow("Use super bolus", useSuperBolus) { useSuperBolus = it }
            TriRow("Use temp target", useTempTarget) { useTempTarget = it }

            ToggleRow("Extended carbs (eCarbs)", useEcarbs == TRI_YES, { useEcarbs = if (it) TRI_YES else TRI_NO })
            if (useEcarbs == TRI_YES) {
                NumberField(
                    "eCarbs", carbs2, { carbs2 = it }, step = 1.0, min = 0.0, max = state.maxCarbs, decimals = 0, unit = "g",
                    modifier = Modifier.fillMaxWidth()
                )
                NumberField(
                    "Time", timeOffset, { timeOffset = it }, step = 5.0, min = -7 * 24 * 60.0, max = 12 * 60.0, decimals = 0, unit = "min",
                    modifier = Modifier.fillMaxWidth()
                )
                NumberField(
                    "Duration", duration, { duration = it }, step = 1.0, min = 0.0, max = 10.0, decimals = 0, unit = "h",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (state.showDevice) {
                Text("VISIBLE ON", style = AapsType.label, color = colors.textSecondary)
                ToggleRow("Phone", devicePhone, { devicePhone = it })
                ToggleRow("Watch", deviceWatch, { deviceWatch = it })
            }

            PrimaryButton(
                label = "Save",
                enabled = carbs > 0.0 || (useEcarbs == TRI_YES && carbs2 > 0.0),
                onClick = {
                    onSave(
                        EditQuickWizardResult(
                            buttonText = buttonText,
                            carbs = carbs.toInt(),
                            carbTime = carbTime.toInt(),
                            percentage = percentage.toInt(),
                            useBG = triToInt(useBG),
                            useCOB = triToInt(useCOB),
                            useIOB = triToInt(useIOB),
                            usePositiveIOBOnly = triToInt(usePositiveIOBOnly),
                            useTrend = trendToInt(useTrend),
                            useSuperBolus = triToInt(useSuperBolus),
                            useTempTarget = triToInt(useTempTarget),
                            useAlarm = triToInt(useAlarm),
                            useEcarbs = triToInt(useEcarbs),
                            carbs2 = carbs2.toInt(),
                            time = timeOffset.toInt(),
                            duration = duration.toInt(),
                            devicePhone = devicePhone,
                            deviceWatch = deviceWatch
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun TriRow(title: String, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val colors = AapsTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AapsType.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
        SegmentedControl(TRI_OPTIONS, selectedIndex, onSelect)
    }
}

@Composable
private fun TimeRow(label: String, display: String, onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AapsShape.cardSmall)
            .background(colors.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = AapsType.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
        Text(display, style = AapsType.cardValue, color = colors.accent)
    }
}

@Composable
private fun LabeledTextField(label: String, value: String, onValue: (String) -> Unit, placeholder: String) {
    val colors = AapsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = AapsType.label, color = colors.textSecondary)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(AapsShape.cardSmall)
                .background(colors.surface2)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .heightIn(min = 24.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) Text(placeholder, style = AapsType.body, color = colors.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = AapsType.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// --- tri-state <-> QuickWizardEntry int mapping ---------------------------------------------------
// SegmentedControl index: 0 = Default, 1 = Yes, 2 = No.
private const val TRI_DEFAULT = 0
private const val TRI_YES = 1
private const val TRI_NO = 2

/** entry int (YES=0 / NO=1) -> segmented index. Anything != NO is treated as Yes (legacy checkbox on). */
private fun triFrom(entryInt: Int): Int = if (entryInt == 1 /* QuickWizardEntry.NO */) TRI_NO else TRI_YES

/** segmented index -> entry int (QuickWizardEntry.YES=0 / NO=1). Default persists as YES like the legacy checkbox. */
private fun triToInt(index: Int): Int = when (index) {
    TRI_NO -> 1 /* QuickWizardEntry.NO */
    else   -> 0 /* QuickWizardEntry.YES (both Default and Yes) */
}

// --- trend selector <-> QuickWizardEntry int mapping ---------------------------------------------
// TREND_OPTIONS index: 0 = No, 1 = Yes, 2 = Positive only, 3 = Negative only.
private fun trendFrom(entryInt: Int): Int = when (entryInt) {
    0 -> 1 // YES
    2 -> 2 // POSITIVE_ONLY
    3 -> 3 // NEGATIVE_ONLY
    else -> 0 // NO
}

private fun trendToInt(index: Int): Int = when (index) {
    1 -> 0 // YES
    2 -> 2 // POSITIVE_ONLY
    3 -> 3 // NEGATIVE_ONLY
    else -> 1 // NO
}

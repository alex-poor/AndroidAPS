package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable

enum class TtReason { EATING_SOON, ACTIVITY, HYPO, CUSTOM }

@Immutable
data class TtPreset(
    val reason: TtReason,
    val label: String,
    val target: Double,          // display units
    val durationMin: Int,
    val targetText: String,
    val durationText: String
)

@Immutable
data class TempTargetSheetState(
    val presets: List<TtPreset> = emptyList(),
    val initialTarget: Double = 0.0,     // display units
    val initialDuration: Int = 0,
    val unitStep: Double = 0.1,          // 0.1 mmol / 1 mgdl
    val unitLabel: String = "mmol/L",
    val targetMin: Double = 4.0,
    val targetMax: Double = 15.0,
    val durationStep: Int = 5,
    val decimals: Int = 1,
    val hasActive: Boolean = false
)

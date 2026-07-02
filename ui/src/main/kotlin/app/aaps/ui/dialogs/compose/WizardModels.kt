package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable

/** User-editable wizard inputs. Carbs + the factor toggles the design exposes as "included in this dose". */
@Immutable
data class WizardInputs(
    val carbs: Int = 0,
    val useBg: Boolean = true,
    val useIob: Boolean = true,
    val useTrend: Boolean = false,
    val useSuperBolus: Boolean = false
)

/** Computed result of running [WizardInputs] through the existing BolusWizard (strings pre-formatted). */
@Immutable
data class WizardResult(
    val bgText: String = "--",
    val bgTrendArrow: String = "",
    val bgFromText: String = "",
    val bgInRange: Boolean = true,
    val carbsInsulin: String = "+0.00 U",
    val bgInsulin: String = "+0.00 U",
    val iobInsulin: String = "0.00 U",
    val trendInsulin: String = "+0.00 U",
    val superBolusInsulin: String = "+0.00 U",
    val total: Double = 0.0,
    val totalText: String = "0.00 U",
    val deliverable: Boolean = false,
    val carbsOnly: Boolean = false,
    val note: String = "",
    val superBolusAvailable: Boolean = false
)

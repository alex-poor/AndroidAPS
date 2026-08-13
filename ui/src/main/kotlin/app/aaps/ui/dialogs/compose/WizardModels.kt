package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable

/** User-editable wizard inputs. Carbs + the factor toggles the design exposes as "included in this dose". */
@Immutable
data class WizardInputs(
    val carbs: Int = 0,
    /**
     * Pre-bolus: minutes from NOW until the carbs are actually eaten (stock AAPS "carb time"). The bolus is
     * delivered immediately and [BolusWizard] timestamps the carbs at `now + carbTime`, so fast carbs get the
     * insulin head start they need. Negative = carbs already eaten. Range ±60, matching EditQuickWizardSheet.
     */
    val carbTime: Int = 0,
    /**
     * Extended carbs: hours the carbs are declared to absorb over (0 = all at once). Slow fat/protein meals
     * genuinely absorb over hours; declaring that per-meal is the correct fix, because the model's absorption
     * constant (tMaxG) is GLOBAL and drains every meal through one shared gut compartment — so it cannot
     * describe a fast and a slow meal at once. AAPS expands the entry into 15-min chunks, which is what the
     * APS/COB path already reads.
     */
    val carbDurationHours: Int = 0,
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
    val cappedWarning: String = "",
    /**
     * Advisory shown when the cannula is new. Insulin peaks about twice as slowly at a fresh site
     * (time-to-peak 110 min on day 1 vs 56 min on day 4, Hildebrandt 1991), and a single large bolus
     * makes it worse still: the same dose split into smaller ones gave a 1.8x higher depot
     * surface-to-volume ratio and significantly faster onset (Diabetes Care 2013). The two compound,
     * which is exactly the failure seen on 2026-08-13 -- a correctly-dosed 6.70 U lunch bolus into a
     * ~6h-old site ran glucose to 15.9, and the corrections added while waiting all landed at once.
     *
     * Advisory only. Splitting the dose is the user's call and the loop cannot do it for them, so
     * this is the only place the finding can actually reach the decision.
     */
    val siteWarning: String = "",
    val superBolusAvailable: Boolean = false
)

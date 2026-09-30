package app.aaps.plugins.aps.camaps

import kotlin.math.max
import kotlin.math.min

/**
 * CamAPS's total-daily-dose adaptation — the layer the fork has always lacked.
 *
 * `MPC::AdjustTDDbasedOnCGM` (0x4b068) summarises a 23-hour window ending at 08:00 via
 * `GetHourlyRecordSummary`, requires at least `minEntriesAdjust` valid entries, and forms a multiplicative
 * correction from the window's mean glucose and its standard deviation:
 * ```
 *   dG     = meanGlucose − target − thresholdOffsetToCalculateTDD
 *   amp    = (dG < 0) ? 1.2 : amplifierAdjust                       // 1.2 below, 1.1 above
 *   factor = (1 + amp · bAdjust · dG) · (1 − sdReduceAdjust · sd)
 *   factor = clamp(factor, minCorrectionAdjust, maxCorrectionAdjust)
 * ```
 * with a hypoglycaemia override: any glucose below `minCGMThresholdAdjust` in the window multiplies by
 * `corrHypoAdjust`, and the correction is suppressed entirely below
 * `minCGMThresholdNoCorrectionAdjust`.
 *
 * All the constants are decoded. What is NOT certain is which of `GetHourlyRecordSummary`'s eight out
 * parameters is the mean and which the sd — the ordering was inferred from how they are consumed, not
 * proven — so the DIRECTION and the CLAMPS are trustworthy and the exact gain is not. That is why
 * `CamapsTddFit` scores this against the user's own measured daily totals rather than assuming it.
 *
 * Contrast the fork's own `TddAdapterV2`, which uses a 7-day window with a 0.75 decay and a x0.90 hypo
 * step. This is a single-window regression on mean glucose with a +40%/-30% clamp, recomputed daily.
 *
 * ⚠️ MEASURED ON THIS PATIENT AND DEFAULT OFF. Run over 30 days of their own data it is well behaved in
 * the abstract — mean factor 0.982, at a clamp on only 1 of 29 days, so not the ratchet TddAdapterV2 was —
 * but their CGM dips below `minCGMThresholdAdjust` (3.3) on **18 of 29 days**, which triggers the hypo
 * override and caps the correction at 1.0. The result is that 15 of 29 days ask for LESS insulin while
 * mean glucose is 7.2 against a 5.8 target, i.e. on this CGM the safety override dominates the signal and
 * drives the operating point down. Whether those minima are real hypoglycaemia or sensor artefacts is not
 * established, and until it is, enabling this would repeat the defect it was meant to replace.
 * Validation tool: hovorka-mpc/CamapsTddFit.kt.
 */
object CamapsTddAdapter {

    /** `minEntriesAdjust` 0x22df8. */
    const val MIN_ENTRIES = 4
    /** `thresholdOffsetToCalculateTDD` 0x22dd8 — shifts the neutral point just below target. */
    const val THRESHOLD_OFFSET = -0.2
    /** `bAdjust` 0x22ddc — gain per mmol/L of mean-glucose deviation. */
    const val B_ADJUST = 0.1324
    /** `amplifierAdjust` 0x2280c when the deviation is positive; 1.2 when negative. */
    const val AMP_ABOVE = 1.1
    const val AMP_BELOW = 1.2
    /** `sdReduceAdjust` 0x22dfc — variability discounts the correction. */
    const val SD_REDUCE = 0.03
    /** `minCorrectionAdjust` / `maxCorrectionAdjust`, 0x22de4 / 0x22de0. */
    const val MIN_CORRECTION = 0.7
    const val MAX_CORRECTION = 1.4
    /** `corrHypoAdjust` 0x22df4 — applied when the window contains a low. */
    const val CORR_HYPO = 0.8
    /** `minCGMThresholdAdjust` 0x22df0 — below this counts as a low. */
    const val HYPO_THRESHOLD = 3.3
    /** `minCGMThresholdNoCorrectionAdjust` 0x22dec — below this, no upward correction at all. */
    const val NO_CORRECTION_THRESHOLD = 4.4
    /** `maxIncreaseCalcTdd` 0x22de8. */
    const val MAX_INCREASE = 3.5
    /** The window `AdjustTDDbasedOnCGM` summarises: 23 h ending 08:00. */
    const val WINDOW_H = 23
    const val WINDOW_END_HOUR = 8

    /**
     * The multiplicative correction for one day's window.
     *
     * @param glucose all CGM values in the window, mmol/L
     * @param targetMmol the glucose target the window is judged against
     */
    fun factor(glucose: List<Double>, targetMmol: Double): Double {
        if (glucose.size < MIN_ENTRIES) return 1.0
        val mean = glucose.average()
        val sd = kotlin.math.sqrt(glucose.sumOf { (it - mean) * (it - mean) } / glucose.size)
        val minG = glucose.min()

        val dG = mean - targetMmol - THRESHOLD_OFFSET
        val amp = if (dG < 0) AMP_BELOW else AMP_ABOVE
        var f = (1.0 + amp * B_ADJUST * dG) * (1.0 - SD_REDUCE * sd)

        // a low in the window discounts the correction; a deep low blocks any increase
        if (minG < HYPO_THRESHOLD) f *= CORR_HYPO
        if (minG < NO_CORRECTION_THRESHOLD) f = min(f, 1.0)

        return max(MIN_CORRECTION, min(MAX_CORRECTION, f))
    }

    /**
     * Apply [factor] to a running TDD estimate. `SubModel1::ModifyBIR` (0x535f8) blends the stored value
     * toward the new one as `(5·stored + w·new)/(w + 5)` with `w = clamp(x, 0.1, 5)`, i.e. at most a
     * half-weight step; `GetBIRHalf` = 1440 min and `GetBIRHalfShort` = 120 min are its two timescales.
     * `w` itself is an unrecovered state field, so the blend here uses the slow arm's weight.
     */
    fun blend(storedTdd: Double, newTdd: Double, w: Double = 5.0): Double =
        (5.0 * storedTdd + w.coerceIn(0.1, 5.0) * newTdd) / (w.coerceIn(0.1, 5.0) + 5.0)
}

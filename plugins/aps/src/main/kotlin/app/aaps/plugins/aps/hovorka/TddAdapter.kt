package app.aaps.plugins.aps.hovorka

import kotlin.math.max
import kotlin.math.min

/**
 * Day-to-day ADAPTIVE-GAIN layer (improvement 2d) — the slow "learns as it goes" loop.
 *
 * Clean-room reimplementation matching the SHAPE of the decoded CamAPS subsystem (MPC::Learn /
 * UpdateTDDandPerfomance / GetTotalDailyDose / AdjustTDDbasedOnCGM / UpdateTDD — see
 * report/hovorka-plugin-plan.md §"TDD ADAPTATION"). It adapts ONLY the operating point (the nominal
 * basal the MPC is shaped around) — NOT a new actuation channel (3b/SMB) and NOT the per-tick estimator
 * (3a/IMM). 2a sets the STARTING physiology from the profile; 2d then MOVES the operating basal as real
 * outcomes accumulate.
 *
 * The signal is the basal the loop actually enacted (a robust, recency-weighted order statistic over
 * recent days — one bad day can't swing it), corrected by trailing glucose performance (persistent highs
 * nudge up; any hypo nudges DOWN harder — asymmetric, safety-first), then clamped per day (bounded,
 * monotone, reversible), floored at minTDD/kg and capped at maxBasal.
 *
 * Stateless-friendly: [endOfDay] takes the day's summary and returns nothing new to persist beyond the
 * per-day enacted-basal ledger, which the plugin reconstructs from history each boot (no new schema).
 */
class TddAdapter(
    private val weightKg: Double,
    initialBasalUhr: Double,
    private val targetMmol: Double = 6.0,
    private val maxBasalUhr: Double = Double.MAX_VALUE,
    private val maxUpFrac: Double = 0.12,       // ≤ +12%/day
    private val maxDownFrac: Double = 0.20,     // ≤ −20%/day (asymmetric: back off faster than ramp up)
    private val minTddPerKg: Double = 0.15,     // U/kg/day floor (decoded minTDDperKg_T1D)
    private val basalFractionOfTdd: Double = 0.5 // basal is ~half of TDD → only that share floors the basal
) {
    /** Current adapted operating basal (U/hr) the MPC should be shaped around. */
    var operatingBasalUhr = initialBasalUhr; private set

    /** Per-completed-day mean ENACTED basal (U/hr) — the delivered-dose ledger (analogue of tagHourlyTDDRec). */
    private val enactedBasalHistory = ArrayList<Double>()

    /**
     * Basal floor (U/hr) from the decoded minimum daily dose per kg. minTDD bounds TOTAL insulin
     * (basal + boluses), so only the basal share (~half) floors the operating basal — otherwise a very
     * insulin-sensitive user (low true basal) would be pinned to an inflated operating point.
     */
    private val basalFloorUhr get() = minTddPerKg * weightKg * basalFractionOfTdd / 24.0

    /**
     * Fold one completed day into the gain. Returns a human-readable reason string (for the adaptation log).
     * @param meanEnactedBasalUhr the day's mean enacted basal (basal TBRs actually delivered)
     * @param meanGlucoseMmol      the day's mean glucose
     * @param tbrFrac              fraction of the day below range (< 3.9 mmol/L)
     * @param minGlucoseMmol       the day's minimum glucose
     */
    fun endOfDay(meanEnactedBasalUhr: Double, meanGlucoseMmol: Double, tbrFrac: Double, minGlucoseMmol: Double): String {
        enactedBasalHistory.add(meanEnactedBasalUhr)
        // 1. robust, recency-weighted delivered basal over the last few days (weighted median, not mean).
        val robust = recencyWeightedMedian(enactedBasalHistory)
        // 2. trailing-glucose performance correction (asymmetric — a hypo dominates everything).
        val hadHypo = tbrFrac > 0.01 || minGlucoseMmol < 3.5
        val err = meanGlucoseMmol - targetMmol
        val scale = when {
            hadHypo    -> 1.0 - 0.10                                   // any hypo → cut hard
            err > 1.0  -> 1.0 + min(maxUpFrac, 0.03 * err)             // persistent high → nudge up
            err < -0.5 -> 1.0 - min(maxDownFrac, 0.05 * (-err))        // persistent low → nudge down harder
            else       -> 1.0
        }
        // 3. per-day clamp relative to the current operating point (bounded, monotone, reversible).
        val target = robust * scale
        val lo = operatingBasalUhr * (1.0 - maxDownFrac)
        val hi = operatingBasalUhr * (1.0 + maxUpFrac)
        // 4. clamp, then floor at minTDD/kg and cap at maxBasal.
        val next = target.coerceIn(lo, hi).coerceIn(basalFloorUhr, maxBasalUhr)
        val reason = "TDD-adapt: %.3f→%.3f U/hr (robust=%.3f scale=%.2f | meanG=%.1f tbr=%.0f%% min=%.1f%s)".format(
            operatingBasalUhr, next, robust, scale, meanGlucoseMmol, tbrFrac * 100, minGlucoseMmol,
            if (hadHypo) " HYPO→down" else "")
        operatingBasalUhr = next
        return reason
    }

    /**
     * Recency-weighted median over the last ≤4 days (weights [.5,.3,.15,.05], most-recent first — the
     * decoded weightsLookAheadFracTDD). A robust order statistic: the value at which cumulative weight
     * first reaches half the total. One anomalous day cannot move it the way a mean would.
     */
    private fun recencyWeightedMedian(history: List<Double>): Double {
        val recent = history.takeLast(4).reversed()                   // most recent first
        if (recent.isEmpty()) return operatingBasalUhr
        val w = doubleArrayOf(0.5, 0.3, 0.15, 0.05)
        val pairs = recent.mapIndexed { i, v -> v to w[i] }.sortedBy { it.first }
        val totalW = pairs.sumOf { it.second }
        var cum = 0.0
        for ((v, wt) in pairs) {
            cum += wt
            if (cum >= 0.5 * totalW) return v
        }
        return pairs.last().first
    }
}

package hovorka.mpc

import kotlin.math.max
import kotlin.math.min

/**
 * TDD adaptation, corrected to the decoded CamAPS shape (`report/hovorka-plugin-plan.md`
 * §"TDD ADAPTATION", outside this repo). Replaces the original `TddAdapter`, which deviated from that
 * design in FIVE load-bearing ways — every one of them measured on 60 days of real recorded history,
 * not argued from the spec. Together they walked the operating point from 97% of the titrated profile
 * to 54% over three weeks, removing 63% of the dawn basal and degrading the 30-min forecast:
 *
 *  1. IT MUST LEARN FROM TOTAL INSULIN. The decoded ledger is `MPC::GetTotalDailyDose` over
 *     `tagCalculatedTDDRec`, and the plan's own step 1 says "insulin actually delivered (basal TBRs
 *     AND boluses)". The shipped adapter folds `meanEnactedBasalUhr` only. On 59 days here the
 *     basal-only signal correlates -0.01 with total daily dose and -0.38 with bolus load: it carries
 *     no information about what the user needed, only about what the loop chose to give. Learning a
 *     gain from your own output is a feedback loop with no external reference. Boluses are the
 *     external reference - they are driven by eating and correcting, not by the controller.
 *
 *  2. IT MUST BE A GAIN ON THE PROFILE, NOT A REPLACEMENT FOR IT. In CamAPS the circadian basal
 *     profile lives in the patient file (`BASAL:%f%300s%f` between START/END - a block profile) and
 *     TDD scales the dose. The shipped adapter emits one scalar that REPLACES the profile for all 24
 *     hours, so the 03:00-09:00 block (0.50 vs 0.35 overnight) disappears. A dimensionless gain keeps
 *     the titrated shape and makes adaptation a relative correction, which is also the only form in
 *     which "the model's operating point" and "the profile" can stay consistent.
 *
 *  3. IT MUST COMPARE LIKE WITH LIKE. The shipped adapter seeds from `profile.getBasal()` at the
 *     moment of the call - a single BLOCK - and then folds 24-hour MEANS into it. Comparing a
 *     peak-hour block against daily averages is structurally guaranteed to read low: seeded at 0.45
 *     (the block live at the UTC-day rollover, 12:00 NZST) and folding means of ~0.36, it walks down
 *     to ~0.30, which then governs a dawn window whose profile value is 0.50. That arithmetic alone
 *     produces the observed 55-60%. Here both sides of the ratio are whole-day totals.
 *
 *  4. THE PERFORMANCE CORRECTION IS AN 8-HOUR WINDOW. `MPC::AdjustTDDbasedOnCGM` builds an 8-hour
 *     CTimeSpan. The shipped adapter uses whole-day mean glucose, which averages a dawn excursion
 *     against afternoon time-in-range and leaves the correction term pinned at 1.0. [perfScale] is
 *     the 8-hour analogue; [hourlyGain] applies it as the decoded hourly ledger would.
 *
 *  5. THE HYPO RESPONSE MUST BE PROPORTIONAL, NOT A BINARY DAILY FLAG. This is the largest single
 *     defect and it is what actually drove the drift. The original `TddAdapter` cut a flat x0.90 whenever
 *     `tbrFrac > 0.01 || minGlucose < 3.5` - and on this user's 58 scoreable days that test fires on
 *     **42 of them (72%)**: their median day has a minimum of 3.3 mmol/L and 3.1% below 3.9. A fixed
 *     multiplicative cut applied three days in four is a RATCHET, not a safety response. Worse, the
 *     `when` chain puts the hypo arm first, so a day that averaged 9.1 mmol/L with one compression low
 *     gets cut 10% and the "persistently high" arm never runs. There is no counterbalancing force, so
 *     the operating point can only walk down - measured, 97% of profile on 2026-08-21 to 54% by
 *     2026-09-06, and 5 of the last 6 days fired.
 *     Here the response is CONTINUOUS in time-below-range with a deadband at the consensus <4% target,
 *     the two arms compose additively instead of one vetoing the other, and a genuinely severe day
 *     (min < 3.0) still gets a floor on the cut. Asymmetry is preserved through the gains, not through
 *     an all-or-nothing switch.
 *
 * Retained from the decoded design unchanged: the recency-weighted order statistic
 * (`weightsLookAheadFracTDD = [.5,.3,.15,.05]`, a weighted MEDIAN so one bad day cannot swing it),
 * the bounded multiplicative per-update clamp (`fmul`/`fcsel`), asymmetric safety (any hypo cuts
 * harder and faster than any high raises), and the `minTDDperKg_T1D = 0.15 U/kg/day` floor - which is
 * applied here to TOTAL daily dose, its actual meaning, rather than to basal via an assumed 50% basal
 * fraction (this user's real basal fraction is 30%).
 */
class TddAdapterV2(
    private val weightKg: Double,
    private val targetMmol: Double = 7.0,
    private val maxUpFrac: Double = 0.12,        // <= +12%/day
    private val maxDownFrac: Double = 0.20,      // <= -20%/day (back off faster than ramp up)
    private val minTddPerKg: Double = 0.15,      // decoded minTDDperKg_T1D, on TOTAL insulin
    private val gainMin: Double = 0.60,          // absolute bounds on the gain: adaptation is a
    private val gainMax: Double = 1.60,          // correction to a titration, never a replacement for it
    /**
     * Length of the RECENT window whose robust dose becomes the numerator, and the geometric decay of
     * its weights. `decay <= 0` selects the decoded `weightsLookAheadFracTDD = [.5,.3,.15,.05]`, which
     * requires [recentDays] = 4.
     *
     * These are the responsiveness knob. The decoded 4-day weights put half the mass on yesterday, so
     * on a signal whose daily CV is 16.8% the numerator is essentially "yesterday's dose" and only the
     * compounding clamp damps it. Longer/flatter windows trade responsiveness for steadiness — and,
     * more importantly here, for how much of the DAY-TO-DAY CARB variation leaks into a basal gain.
     * SWEPT on the user's own data (GainWindowSweep, 30 scored days). The decoded 4-day/last-day
     * settings are NOT the best available here; 7-day exponential + [PerfMode.WINDOW] is:
     *
     *   config              vol   range  meanGain  r(slow)  r(carbs)
     *   4d decoded/day     .098    .57      .914      .25      -.20     <- decoded
     *   7d exp.75/window   .056    .37      .972      .22      -.10     <- default here
     *   14d exp.85/window  .039    .26      .970     -.20      -.58     <- too long: tracks nothing
     *
     * Day-to-day churn halves and the range narrows 35% with drift tracking intact, while 10-14 day
     * windows lose it entirely and start tracking carbohydrate instead. Note meanGain: the noisy
     * config sits 6% LOWER. That is not a coincidence — the per-update clamp is asymmetric (-20% down
     * vs +12% up), so noise on the target does not average out, it RATCHETS DOWN. Reducing the noise
     * removes a systematic bias, not just variance. (n=30 days: the vol/range columns are solid, the
     * correlations have SE about 0.19 and are directional only.)
     */
    private val recentDays: Int = 7,
    private val decay: Double = 0.75,
    /**
     * Where the performance correction gets its glucose from. LAST_DAY reads only the most recent
     * completed day — one day of CGM is a thin, noisy basis for a multiplicative move on the operating
     * point, and the window sweep showed it, not the dose window, is what makes the gain churn.
     * WINDOW aggregates the whole recent window (mean glucose, mean time-below-range, MEDIAN of the
     * daily minima so a single compression low cannot dominate). NONE disables it, to separate the
     * dose signal from the glucose signal.
     */
    private val perfMode: PerfMode = PerfMode.WINDOW
) {
    enum class PerfMode { LAST_DAY, WINDOW, NONE }

    init { require(decay > 0.0 || recentDays == 4) { "decoded weights are 4 long; got $recentDays" } }

    /** One completed day of the ledger. [tddU] is basal delivered PLUS boluses. */
    data class Day(val tddU: Double, val meanG: Double, val minG: Double, val tbrFrac: Double)

    /** Dimensionless multiplier on the profile basal. 1.0 = "your titration is right". */
    var gain = 1.0; private set

    private val tddFloor get() = minTddPerKg * weightKg

    /**
     * Fold the trailing ledger and return the gain.
     *
     * @param recent completed days, oldest first, ending yesterday - the numerator
     * @param baseline the longer trailing window that defines "what this person usually needs" - the
     *        denominator. Kept separate and older so the ratio measures DRIFT, not noise: if the same
     *        days fed both sides the gain would be 1.0 by construction.
     */
    /**
     * Fold the trailing [foldDays] of a ledger (oldest first; null = a day with too little CGM to score).
     *
     * ONE day at a time, deliberately: the per-update clamp is what damps this estimator, and a clamp
     * only damps when it COMPOUNDS. Taking a single clamped step from a fixed start just returns the
     * bound, which is bang-bang rather than adaptation — that bug produced a gain alternating between
     * 0.80 and 1.12 on consecutive days before it was caught. Lives here so the plugin, the replay
     * harness and the tests cannot drift apart on it.
     */
    fun foldTrailing(ledger: List<Day?>, foldDays: Int = FOLD_DAYS): String {
        var reason = ""
        for (k in foldDays downTo 1) {
            // +1 so the LAST fold's recent window ends at the most recent completed day. Without it,
            // subList(rf, size-1) drops that day entirely and the gain is a day stale — which defeats
            // the recency weights, since half their mass sits on exactly the day being dropped. Caught
            // by the harness's asymmetric-safety check: a hypo day appended to the ledger moved the
            // gain not at all.
            val i = ledger.size - k + 1
            if (i <= 0) continue
            val rf = max(0, i - recentDays)
            reason = fold(ledger.subList(rf, i).filterNotNull(), ledger.subList(0, rf).filterNotNull())
        }
        return reason
    }

    /** Diagnostics from the last [fold]: the raw dose ratio and the performance multiplier. */
    var lastRaw = 1.0; private set
    var lastScale = 1.0; private set

    fun fold(recent: List<Day>, baseline: List<Day>): String {
        if (recent.isEmpty() || baseline.size < MIN_BASELINE_DAYS) return "TDD-v2: gain 1.000 (baseline too thin: ${baseline.size}d)"
        val recentTdd = max(tddFloor, robust(recent.map { it.tddU }))
        val baseTdd = max(tddFloor, median(baseline.map { it.tddU }))
        val raw = recentTdd / baseTdd
        val scale = when (perfMode) {
            PerfMode.NONE -> 1.0
            PerfMode.LAST_DAY -> perfScale(recent.last())
            PerfMode.WINDOW -> perfScale(Day(
                0.0,
                recent.map { it.meanG }.average(),
                recent.map { it.minG }.sorted()[recent.size / 2],
                recent.map { it.tbrFrac }.average()))
        }
        // (the caller folds one day at a time across the trailing window, so this clamp COMPOUNDS -
        //  a single clamped step from a fixed start is just the bound, which is bang-bang, not a walk)
        val target = raw * scale
        lastRaw = raw; lastScale = scale
        val next = target.coerceIn(gain * (1.0 - maxDownFrac), gain * (1.0 + maxUpFrac)).coerceIn(gainMin, gainMax)
        val reason = "TDD-v2: gain %.3f->%.3f (recentTDD %.1f / baseTDD %.1f = %.3f, perf x%.2f)".format(
            gain, next, recentTdd, baseTdd, raw, scale)
        gain = next
        return reason
    }

    /**
     * The 8-hour performance correction (`AdjustTDDbasedOnCGM` analogue), applied on top of the daily
     * gain. Asymmetric by construction: any hypo in the window cuts, and cuts more than any high
     * raises. Returns a multiplier, 1.0 when the trailing window is unremarkable or too sparse.
     */
    fun hourlyGain(mean8h: Double, min8h: Double, tbr8h: Double, n: Int): Double {
        if (n < MIN_8H_SAMPLES) return 1.0
        return perfScale(Day(0.0, mean8h, min8h, tbr8h))
    }

    /**
     * Performance correction, continuous in both directions. See defect 5 in the class doc: the shipped
     * binary hypo flag fires on 72% of this user's days and can only ratchet down.
     *
     * The low arm is proportional to time-below-range past [TBR_DEADBAND] (the consensus <4% target, so
     * an ordinary day contributes nothing) and reaches the full [maxDownFrac] at ~10%. The high arm is
     * proportional to how far mean glucose sits above target. They ADD, so a day that ran high and also
     * clipped a low gets the net of the two rather than the cut alone. Asymmetry survives in the gains:
     * 6 points of extra time-below-range costs the full 20%, whereas 4 mmol/L above target earns 12%.
     */
    private fun perfScale(d: Day): Double {
        var lo = min(maxDownFrac, HYPO_GAIN * max(0.0, d.tbrFrac - TBR_DEADBAND))
        if (d.minG < SEVERE_MMOL) lo = max(lo, SEVERE_CUT)     // a genuinely severe day always cuts
        val hi = min(maxUpFrac, 0.03 * max(0.0, d.meanG - targetMmol - HIGH_DEADBAND_MMOL))
        return (1.0 + hi - lo).coerceIn(1.0 - maxDownFrac, 1.0 + maxUpFrac)
    }

    /** Plain median over the whole window - the stable denominator ("what this person usually needs"). */
    private fun median(values: List<Double>): Double {
        val v = values.sorted()
        return if (v.size % 2 == 1) v[v.size / 2] else 0.5 * (v[v.size / 2 - 1] + v[v.size / 2])
    }

    /** Weights over the recent window, most recent first. */
    private val weights: DoubleArray = if (decay <= 0.0) doubleArrayOf(0.5, 0.3, 0.15, 0.05)
        else DoubleArray(recentDays) { Math.pow(decay, it.toDouble()) }.let { w ->
            val t = w.sum(); DoubleArray(w.size) { w[it] / t } }

    /** Recency-weighted median over the recent window, most recent heaviest. A weighted ORDER statistic,
     *  so one anomalous day cannot move it the way a weighted mean would. */
    private fun robust(values: List<Double>): Double {
        val recent = values.takeLast(weights.size).reversed()
        val w = weights
        val pairs = recent.mapIndexed { i, v -> v to w[i] }.sortedBy { it.first }
        val totalW = pairs.sumOf { it.second }
        var cum = 0.0
        for ((v, wt) in pairs) { cum += wt; if (cum >= 0.5 * totalW) return v }
        return pairs.last().first
    }

    companion object {
        const val MIN_BASELINE_DAYS = 7
        const val MIN_8H_SAMPLES = 48        // ~4 h of 5-min CGM; below this the window is not evidence
        /** Decoded window length (`weightsLookAheadFracTDD` is 4 weights long). Kept for reference. */
        const val RECENT_DAYS_DECODED = 4
        /** The window this class DEFAULTS to — chosen by GainWindowSweep on real data, see [recentDays]. */
        const val RECENT_DAYS_DEFAULT = 7
        const val BASELINE_FROM = 28         // baseline window: days [-28, -recentDays) back
        const val FOLD_DAYS = 7              // trailing days folded in sequence (plugin's ADAPT_DAYS)
        const val TBR_DEADBAND = 0.04        // consensus target is <4% below 3.9 - an ordinary day is free
        const val HYPO_GAIN = 3.33           // full maxDownFrac by ~10% time-below-range
        const val SEVERE_MMOL = 3.0
        const val SEVERE_CUT = 0.10
        const val HIGH_DEADBAND_MMOL = 0.5
    }
}

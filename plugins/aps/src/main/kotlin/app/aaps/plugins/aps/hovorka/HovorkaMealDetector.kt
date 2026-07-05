package app.aaps.plugins.aps.hovorka

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Bayesian unannounced-meal detector — the CamAPS FX capability that reacts to carbs you didn't announce
 * (report/algorithm-spec.md §3: Model1::GetRunningMealProb / GetRunningMealBio / UpdateRunningMealProbAndBio
 * / CHOleft, gated by mealPeriodsGlb + the weight-scaled mealSizeForWeightCategory priors).
 *
 * Clean-room mechanics (standard unknown-input estimation + our decoded priors): each CGM tick the caller
 * supplies the INNOVATION (measured glucose − the estimator's one-step prediction from KNOWN insulin +
 * announced carbs). A persistent POSITIVE innovation = glucose rising faster than known inputs explain =
 * an unannounced meal. We (1) accumulate a candidate carb amount, (2) once it clears a weight-scaled
 * confirmation floor declare a meal (running meal probability), then (3) drive an integral estimate of the
 * carb-appearance rate off the ongoing innovation and emit grams to inject into the estimator — so the
 * controller's COB (and hence the SMB deficit / MPC forecast) reflects the meal. Self-limiting: once enough
 * carbs are injected the model's prediction catches up, the innovation falls to zero, injection stops.
 *
 * SAFETY (this feeds insulin via SMB, so false positives are hypo risk — bias hard toward MISSING a meal):
 *  - only POSITIVE innovation ever counts (a fall never looks like a meal);
 *  - a confirmation floor (weight-scaled small-meal size) rejects CGM noise / slow drift;
 *  - HARD off at/below a glucose guard, and locked out for [recentLowLockMin] after any low (a rebound off
 *    a hypo — or the carbs used to treat it — must NOT trigger more insulin);
 *  - suppressed while ANNOUNCED carbs are still absorbing (the model already has those — no double count);
 *  - time-of-day prior (meals are rare overnight);
 *  - cumulative inference capped at a weight-scaled max meal.
 * All output still flows through the existing SMB gates (Objective 8 + both hypo suspends), so a bad
 * inference can raise basal/withhold, but can only bolus within the already-conservative SMB envelope.
 */
class HovorkaMealDetector(
    weightKg: Double,
    private val tickMin: Double = 5.0,
    private val ki: Double = 0.6,                  // carb-rate integral gain (g/min per mmol/L innovation)
    private val slackMmol: Double = 0.3,           // innovation deadzone (absorbs CGM noise)
    private val hypoGuardMmol: Double = 5.0,       // never infer a meal at/below this glucose
    private val recentLowLockMin: Double = 45.0,   // lock-out held after a low until [recentLowLockMin] of recovery
    private val lowMmol: Double = 3.9,             // "a low happened" threshold that arms the lock-out
    private val recoveredMmol: Double = 6.0,       // lock-out counts down only once glucose is back above this
    private val maxRateGPerMin: Double = 1.1       // cap on the appearance-rate estimate (g/min)
) {
    // weight-scaled cap on a single inferred meal, interpolated between the decoded lightest ([… large 36])
    // and heaviest ([… large 81]) mealSizeForWeightCategory rows (algorithm-spec.md §3).
    private val frac = ((weightKg - 13.0) / (85.0 - 13.0)).coerceIn(0.0, 1.0)
    private val maxMealG = 36.0 + frac * (81.0 - 36.0)   // ~36 g … 81 g

    private var unexplainedMmol = 0.0 // leaky integral of the deadzoned positive innovation (mmol/L) — the
                                      // physically-calibrated "glucose above what known inputs predict" signal
    private var carbRateG = 0.0      // confirmed carb-appearance-rate estimate (g/min)
    private var injectedG = 0.0      // cumulative grams injected this episode (for the cap)
    private var lowLockTicks = 0     // remaining lock-out ticks after a low
    var running = false; private set // a meal is currently being tracked (GetRunningMealProb > 0)
    var pMeal = 0.0; private set     // running meal probability (for logging / open-loop review)
    var choLeftG = 0.0; private set  // running inferred carbs-on-board estimate (CHOleft, decays as it absorbs)
    var totalInjectedG = 0.0; private set  // lifetime grams inferred+injected (diagnostics)

    private val confirmRiseMmol = 1.0  // sustained glucose-above-prediction (mmol/L) to declare a meal
    private val leak = 0.85            // per-tick leak of the unexplained-rise integral (old evidence fades)
    private var baselineInnov = 0.0    // slow EWMA of the innovation = persistent model-mismatch bias (high-pass ref)
    private var baselineInit = false   // seed the baseline from the first innovation (capture the bias at t0)
    private val baselineAdaptRate = 0.06  // baseline adaptation per tick (~80-min τ) — slow vs a real meal's rise

    /**
     * @param innovMmol      measured glucose − estimator's prior (post-predict, pre-update) glucose (mmol/L)
     * @param gMeasMmol      current CGM (mmol/L)
     * @param minuteOfDay    0..1439, for the time-of-day meal prior
     * @param announcedActive true while announced carbs are still absorbing (suppress — no double count)
     * @return grams CHO to inject into the estimator this tick (0 = nothing)
     */
    fun update(innovMmol: Double, gMeasMmol: Double, minuteOfDay: Int, announcedActive: Boolean): Double {
        choLeftG *= mealDecay()                                        // absorb previously-inferred carbs
        // Post-low lock-out: arm on a genuine low, then HOLD (don't count down) until glucose has climbed
        // back above [recoveredMmol] — so the whole rebound off a hypo (and any rescue-carb rise) is immune,
        // not just a fixed 30 min that expires mid-recovery.
        if (gMeasMmol <= lowMmol) lowLockTicks = (recentLowLockMin / tickMin).toInt()
        else if (lowLockTicks > 0 && gMeasMmol >= recoveredMmol) lowLockTicks--

        val prior = periodPrior(minuteOfDay)
        val gated = gMeasMmol > hypoGuardMmol && !announcedActive && lowLockTicks == 0 && prior > 0.0
        if (!gated) { reset(); return 0.0 }

        // A persistent model MISMATCH (plant ≠ controller) shows up as a STANDING positive innovation, which
        // the raw integral would accumulate into a phantom meal on a quiet day (verified: worst-min 5.2→4.7).
        // High-pass it: seed a baseline from the first innovation, adapt it slowly while no meal is CONFIRMED,
        // and count only the FRESH rise ABOVE it — a real meal is a fast deviation from the slow baseline; a
        // standing offset gets absorbed into it. In-silico: phantom fixed (5.2→5.1) with meal detection intact.
        if (!baselineInit) { baselineInnov = innovMmol; baselineInit = true }
        else if (!running) baselineInnov += (innovMmol - baselineInnov) * baselineAdaptRate
        // leaky integral of the deadzoned innovation ABOVE the mismatch baseline: a sustained fresh rise (a
        // meal) accumulates; zero-mean noise, one-off blips, and standing bias leak away and never confirm.
        unexplainedMmol = max(0.0, unexplainedMmol * leak + ((innovMmol - baselineInnov) - slackMmol))
        // confirm harder when the time-of-day prior is low (overnight needs more evidence)
        val threshold = confirmRiseMmol / prior
        pMeal = 1.0 / (1.0 + exp(-3.0 * (unexplainedMmol - threshold)))
        if (unexplainedMmol < threshold) { carbRateG *= 0.7; running = false; return 0.0 }
        running = true
        // confirmed: integral estimate of the carb-appearance rate off the ongoing innovation (self-limits
        // as injected carbs make the model catch up → innov → 0 → rate stops growing / decays)
        carbRateG = (carbRateG + ki * innovMmol).coerceIn(0.0, maxRateGPerMin)
        var dCho = carbRateG * tickMin
        dCho = dCho.coerceAtMost(maxMealG - injectedG).coerceAtLeast(0.0)  // cap the episode at a weight-scaled max
        injectedG += dCho
        choLeftG += dCho
        totalInjectedG += dCho
        return dCho
    }

    private fun reset() { unexplainedMmol = 0.0; carbRateG *= 0.5; injectedG *= 0.7; running = false; pMeal *= 0.5 }

    /** Per-tick absorption decay of the running inferred COB (~40-min characteristic gut emptying). */
    private fun mealDecay(): Double = exp(-tickMin / 40.0)

    /**
     * Time-of-day meal prior ∈ (0,1] — a compressed stand-in for the decoded priorMealProb × mealPeriodsGlb
     * (32 floats over night/morning/afternoon/evening half-hour periods). Meals are rare overnight, common
     * around the day's meal windows. Scales detector sensitivity; 0 would hard-gate a period (none here —
     * an unannounced overnight meal is still possible, just needs more evidence).
     */
    private fun periodPrior(minuteOfDay: Int): Double {
        val h = (minuteOfDay / 60) % 24
        return when (h) {
            in 0..4   -> 0.35            // deep night — unlikely
            5, 22, 23 -> 0.6             // shoulders
            else      -> 1.0             // daytime meal windows
        }
    }
}

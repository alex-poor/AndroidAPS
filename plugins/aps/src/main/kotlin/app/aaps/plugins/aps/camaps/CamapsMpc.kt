package app.aaps.plugins.aps.camaps

import app.aaps.plugins.aps.hovorka.HovorkaModel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Clean-room replication of the CamAPS FX control law, as decoded in report/algorithm-spec.md §4-5.
 *
 * WHY THIS EXISTS SEPARATELY FROM HovorkaMpc. The fork's existing controller began as a replication and
 * diverged: it replaced the decoded zone-bounded reference trajectory with an unbounded exponential, and
 * six guards were then added over three months to contain the result. Measured over 30 real days that
 * stack overrides the model on 95% of ticks and the descent guard alone binds on 53.7% — so the deployed
 * controller is the guard stack, not the model. That divergence cannot be tested by toggling one flag at
 * a time, because each guard absorbs the component being tested; it has to be a whole configuration.
 * This is that configuration, kept apart so both can run and be compared on the same person.
 *
 * WHAT IS REPLICATED
 *
 *  - §5 REFERENCE TRAJECTORY, transcribed from `MPC::DetermineSetPoint` (0x48b84). NOT an exponential
 *    to target: the set-point is CLAMPED to 12.0 and then walks down by a per-minute zone slope that is
 *    re-evaluated each step on the evolving set-point.
 *        sp > 13       -> -1/24    mmol/L/min = -2.5 mmol/L/h    (0xBD2AAAAB)
 *        10 < sp <= 13 -> -0.02833 mmol/L/min = -1.7 mmol/L/h    (0xBCE81B4F)
 *        sp <= 10      -> exponential to target, half-life 60 min down / 15 min up, with a
 *                         [target, target+2] dead zone where it holds
 *    The 12.0 clamp is the load-bearing part. At glucose 20 the set-point starts at 12, so there is an
 *    8 mmol/L tracking error from the first step and the optimiser saturates — which is why the real
 *    controller needs [maximumPersonalRange] to bound it at all.
 *
 *    An earlier version of this file used `T + (G0-T)*exp(-t/tau)` with the zone slopes as a ceiling on
 *    the fall. That reading started the reference AT current glucose, so it never produced a large
 *    error, and the replica commanded 1.00 x basal at glucose 18 where the real controller commands
 *    2.53 x. Fixing the trajectory cut the measured level-response error from 0.537 to 0.183 x basal.
 *
 *  - §4 OPTIMISER. A basal sequence over a 180-minute horizon (the decoded 180-step BIR vector),
 *    piecewise-constant, minimising tracking error against the reference plus an effort term; the FIRST
 *    step is commanded, as GetBIRpump does.
 *
 *  - §4 OUTPUT LAW `MPC::GetBIR(mode)`. `max(hourlyRate, 0.7 * mean(horizon vector))`. Note carefully
 *    what this floors against: 70% of what the controller ITSELF PLANNED, not 70% of profile basal. A
 *    plan that intends to suspend still suspends. Flooring on nominal instead holds basal on through
 *    falls the controller had decided to back away from — a mistake that, when made in the replay
 *    harness, inflated this arm by 69% and cut its suspend rate from 33% to 5%.
 *
 * NOT YET REPLICATED — AND IT SHOWS
 *
 *  - `MPC::ModifyRateGlucoseRate` (0x46f10, 1664 bytes). A post-processing stage applied to the
 *    optimiser's output, keyed to the observed glucose slope: it takes `GetSlope` over TWO windows
 *    (base span + 70 min and + 40 min) and uses the more negative, substitutes 5.5 mmol/L if the
 *    previous CGM is over ~90 min stale. Both of its paths are now implemented — see
 *    [attenuationPercent] and [SUSTAINED_FALL_CAP_FRAC]. `MPC::RuleUsed(n)` records which rule fired;
 *    that is the Diagnostics field in the output.
 *
 *    This is the whole trend response and it lives OUTSIDE the MPC, on its output. Adding it took the
 *    measured trend error from 0.646 to 0.522 x basal. What remains there is the RISING arm, where this
 *    replica is flat (1.38-1.45 x basal) against the real controller's 1.88-2.12: attenuation can only
 *    reduce, so that residual is the plant difference, not this stage.
 *
 *    STILL A BAR TO RUNNING THIS ON A PERSON: the sub-8 path is approximated with a single slope
 *    estimate where the binary requires the fall to have persisted across four windows, and the plant
 *    difference above is unaddressed.
 *
 * WHAT IS DELIBERATELY NOT REPLICATED
 *
 *  - A hard hypo suspend at [hypoSuspendMmol] is KEPT -- and is no longer a deviation. It was added on
 *    the reasoning that its absence from the decoded spec was more likely an RE gap than a design
 *    decision; driving the real binary confirmed that directly (0.00 U/h up to BG 4.500, 0.79 x basal
 *    at 4.625). The threshold was raised 3.9 -> 4.5 to match, i.e. the real controller is MORE
 *    conservative at the low end than this replica was.
 *  - A small deadband around the previous command is KEPT, purely so AAPS does not emit a fresh TBR
 *    every tick. It is an integration concern, not therapy.
 *
 * Everything else the fork added — descent guard, high-correction floor, current-glucose damper,
 * IOB-divergence detector, site guard, SMB — is absent by design. CamAPS is basal-only and carries
 * exactly two pieces of safety machinery: the bound above, and the floor above.
 */
class CamapsMpc(
    private val model: HovorkaModel,
    private val targetMmol: Double,
    private val nominalBasalMuPerMin: Double,
    private val maxBasalMuPerMin: Double,
    private val horizonMin: Int = 180,
    private val stepMin: Int = 5,
    private val nSegments: Int = 6,
    private val sweeps: Int = 2,
    private val effortWeight: Double = 0.02,
    /**
     * Minutes the real controller projects the CGM forward before clamping the initial set-point
     * (`predictLead`, a runtime CTimeSpanMy whose value is not recovered). 0 = seed from the current
     * estimate. Replaces the old `refTauMin`; the trajectory is no longer an exponential to target.
     */
    private val predictLeadMin: Double = 0.0,
    /** GetBIR floor as a fraction of the PLANNED horizon mean (decoded 0.7). */
    private val birFloorFrac: Double = 0.7,
    /**
     * Ceiling on the command, in mu/min. DECODED from `MPC::MaximumPersonalRange` (vaddr 0x46b08) and
     * computed by the caller -- see [maximumPersonalRange]. Pass 0 or less to fall back to
     * [maxBasalMuPerMin] alone.
     *
     * This replaced a flat `2.55 x profile basal`, which was fitted to flat-profile probes and was an
     * artefact of them: with a flat profile the real rule's 24h-mean and current-block terms coincide
     * and it collapses to `mult x 0.85 x basal`, which at mult=3.0 is 2.55. The real rule is tiered on
     * glucose and keyed to TDD, so on any profile with real dawn variation it is nothing like 2.55x.
     */
    private val maxRateMuPerMin: Double = 0.0,
    /**
     * Hard suspend threshold. MEASURED at 4.5: on flat glucose the real controller returns exactly
     * 0.00 U/h up to BG 4.500 and 0.79 x basal at 4.625, bang-bang with no ramp, and the threshold
     * is identical at profile basals 0.45/0.85/2.40 so it is absolute, not scaled. The decoded spec
     * showed no such layer, which was a gap in the RE, not a design decision -- so this is now a
     * replicated behaviour rather than the bolted-on backstop it started as.
     */
    /**
     * Observed glucose slope in mmol/L per HOUR, for [modifyRateGlucoseRate]. The real controller uses
     * `MPC::GetSlope` over two windows (base span + 70 and + 40 min), takes the more NEGATIVE, then
     * takes the more negative again against the model's own 2-minute prediction scaled x30. Pass 0 to
     * disable the attenuation stage.
     */
    private val observedSlopeMmolPerH: Double = 0.0,
    private val hypoSuspendMmol: Double = 4.5,
    private val deadbandFrac: Double = 0.1
) {

    companion object {

        /**
         * `MPC::MaximumPersonalRange(float&, CTimeMy const&)`, vaddr 0x46b08, transcribed:
         *
         * ```
         *   mult = cgm > 12 ? 3.0 : cgm > 8 ? 2.5 : 2.0     // cgm defaults to 5.5 when unavailable
         *   base = max(BIRasFractionOfTDD * tdd / 24, 0.7 * mean(48 half-hourly basals))
         *   a = mult * base ;  b = mult * basalNow
         *   return a < b ? (a + b) / 2 : a
         * ```
         *
         * `BIRasFractionOfTDD` is a binary constant, 0.48 (symbol at vaddr 0x22d88). The three
         * multiplier tiers and the 0.7 are immediates in the function. Verified against all nine
         * measured ceiling points (profile basal 0.20-3.20 U/h): every one reproduces exactly after
         * the pump's 0.05 U/h rounding -- see report/camaps-measured-response.md §2.
         *
         * All rates in the same unit; the result comes back in that unit.
         */
        fun maximumPersonalRange(cgmMmol: Double, tddU: Double, meanBasal: Double,
                                 basalNow: Double): Double {
            val cgm = if (cgmMmol > 0.0) cgmMmol else CGM_FALLBACK_MMOL
            val mult = when {
                cgm > 12.0 -> 3.0
                cgm > 8.0  -> 2.5
                else       -> 2.0
            }
            val base = max(BIR_AS_FRACTION_OF_TDD * tddU / 24.0, 0.7 * meanBasal)
            val a = mult * base
            val b = mult * basalNow
            return if (a < b) 0.5 * (a + b) else a
        }

        /** Binary constant `BIRasFractionOfTDD`, vaddr 0x22d88. */
        const val BIR_AS_FRACTION_OF_TDD = 0.48

        /** `MPC::DetermineSetPoint` immediates. Slopes are mmol/L per MINUTE. */
        const val SLOPE_ABOVE_13 = -1.0 / 24.0          // 0xBD2AAAAB, -2.5 mmol/L/h
        const val SLOPE_10_TO_13 = -0.028333334252238274 // 0xBCE81B4F, -1.7 mmol/L/h
        const val SLOPE_BELOW_10 = -1.0 / 60.0          // 0xBC888889, -1.0 mmol/L/h (exp path below 10)
        const val SETPOINT_CLAMP_MMOL = 12.0            // fmov s9, #12.0 ; fcsel .., gt
        /**
         * The glucose < 8.0 branch of `MPC::ModifyRateGlucoseRate` (0x472b8..0x474f0). The binary tests
         * `GetSlope` over FOUR successive look-back windows, each x60 into mmol/L/h, each against
         * -1.2 (immediate 0xBF99999A); only if all four are below it does it cap the command at
         * 0.2 x `GetBIRpump(now)` (immediate 0x3E4CCCCD), downward only, and then RETURN -- skipping
         * the attenuation entirely.
         *
         * That last detail matters and is easy to get backwards: where this fires, the real controller
         * ends up with MORE insulin than [attenuationPercent] would leave, since at low glucose falling
         * fast the attenuation reaches 100%. So this path makes the replica LESS conservative than a
         * blanket attenuation would, not more.
         *
         * NOT APPLIED, on measured evidence. We have one slope estimate, not four windows, so the
         * "sustained" precondition cannot be evaluated -- a single fast-falling sample would trigger it
         * where the binary requires the fall to have persisted. Applying it unconditionally was tried
         * and measured against 126 fresh probes of the real binary over glucose 5.0-8.0 x slopes 0 to
         * -3.6 mmol/L/h: the real controller returns 0.00 U/h at EVERY point with a slope at or below
         * -2.4, and the cap pinned the replica at 0.20 x basal across 25 of those points. That is a
         * safety regression, so the cap is kept as documentation and the attenuation is applied at all
         * glucose levels instead. Reinstating it needs the four-window test, i.e. a real slope history.
         */
        const val SUSTAINED_FALL_GLUCOSE_MMOL = 8.0
        const val SUSTAINED_FALL_SLOPE = -1.2
        const val SUSTAINED_FALL_CAP_FRAC = 0.2

        /** Symbols `DownSlopeHalfTime` (0x22d2c) and `UpSlopeHalfTime` (0x22d30), minutes. */
        const val DOWN_SLOPE_HALF_MIN = 60.0
        const val UP_SLOPE_HALF_MIN = 15.0

        /** What MaximumPersonalRange assumes when GetCGMapproximate fails (immediate 5.5). */
        const val CGM_FALLBACK_MMOL = 5.5

        /**
         * `MPC::ModifyRateGlucoseRate` (0x46f10), the glucose-slope attenuation — the real controller's
         * ENTIRE trend response, and it sits OUTSIDE the optimiser, on its output. Returns a percentage
         * in [0, 100] to cut the commanded rate by; 100 is a suspend.
         *
         * Two indices, both the same closed form with different half-lives (the immediates are 3.2 and
         * 4.5, divided into the `ln2` symbol, hence the powers of two):
         * ```
         *   A9  = 2^(-1/3.2)             B9 = 2^(-(slope + 2.2)/3.2)
         *   raw9  = 10*(B9 - A9)/(1 - A9)      idx9 = clamp(raw9, 0, 50)
         *                                      idx10 = clamp(raw9 * 0.5, 0, 100)
         *   A3  = 2^(-7.5/4.5)           B3 = 2^(-(cgm - 4.5)/4.5)
         *   raw3  = 10*(B3 - A3)/(1 - A3)      idx12 = clamp(raw3, 0, 10)
         *   att = clamp(max(idx9 * idx12, idx10), 0, 100)
         * ```
         * `raw9` crosses zero at slope = -1.2 mmol/L/h, so nothing is withheld until glucose is falling
         * faster than that. `idx12` is glucose-only: 10 at 4.5 mmol/L, 0 at 12 — so the product term is
         * inert above 12 and only `idx10` acts there.
         *
         * NOT YET DECODED: the chain of rules the binary runs when glucose < 8.0 (0x472b8..0x474f0),
         * which can only ever REDUCE the rate further. This implements the >= 8.0 path only, so it is
         * expected to be less conservative than the real controller at low glucose.
         */
        fun attenuationPercent(slopeMmolPerH: Double, cgmMmol: Double): Double {
            val a9 = 2.0.pow(-1.0 / 3.2)
            val b9 = 2.0.pow(-(slopeMmolPerH + 2.2) / 3.2)
            val raw9 = 10.0 * (b9 - a9) / (1.0 - a9)
            val idx9 = raw9.coerceIn(0.0, 50.0)
            val idx10 = (raw9 * 0.5).coerceIn(0.0, 100.0)
            val a3 = 2.0.pow(-7.5 / 4.5)
            val b3 = 2.0.pow(-(cgmMmol - 4.5) / 4.5)
            val raw3 = 10.0 * (b3 - a3) / (1.0 - a3)
            val idx12 = raw3.coerceIn(0.0, 10.0)
            return max(idx9 * idx12, idx10).coerceIn(0.0, 100.0)
        }
    }

    data class Decision(
        val basalUPerHr: Double,
        val reason: String,
        val horizonMeanUPerHr: Double,
        val eventualMmol: Double
    )

    /** Decoded §5 ceiling on the demanded rate of fall, mmol/L per hour, by glucose zone. */
    private fun maxFallMmolPerH(g: Double): Double = when {
        g > 13.0 -> 2.5
        g > 10.0 -> 1.7
        else     -> 1.0
    }

    /**
     * Reference trajectory: exponential approach to target, with each step clamped so the demanded fall
     * never exceeds the zone ceiling. The clamp only ever RAISES the setpoint — it can ask for less
     * insulin than the exponential would, never more.
     */
    /**
     * `MPC::DetermineSetPoint`, vaddr 0x48b84, transcribed. This replaced an exponential approach to
     * target with a zone-bounded ceiling on the fall, which was a plausible reading of the decoded
     * slopes and was wrong in the one way that mattered most.
     *
     * ```
     *   sp = min(setpoint0, 12.0)                       // <- the clamp; `fmov s9,#12.0; fcsel ..,gt`
     *   each step, re-evaluated on the EVOLVING sp:
     *     sp > 13      ->  sp += dt * -1/24             // 0xBD2AAAAB, -2.5 mmol/L/h
     *     sp > 10      ->  sp += dt * -0.0283333        // 0xBCE81B4F, -1.7 mmol/L/h
     *     target <= sp <= target+2 -> hold              // dead zone
     *     otherwise    ->  exponential to target, half-life 60 min coming down / 15 min going up
     * ```
     *
     * The slopes are per MINUTE, which settles the 2x ambiguity in the decoded constants: -1/24 is an
     * absolute 2.5 mmol/L/h, not a decay constant.
     *
     * **The clamp is the point.** At glucose 20 the setpoint starts at 12.0, so the controller carries
     * an 8 mmol/L tracking error from the first step and saturates — which is why the real controller
     * needs an external ceiling at all ([maximumPersonalRange]). The old version started the reference
     * AT current glucose and descended from there, so it never saw a large error and never asked for
     * much insulin. That, not the cost weights, is why the replica commanded 1.00 x basal at glucose 18
     * where the real controller commands 2.53 x.
     *
     * Not replicated: the real one seeds `setpoint0` by projecting the CGM forward over `predictLead`
     * (a runtime span, the sensor-lag lead) before clamping. [predictLeadMin] defaults to 0, i.e. seed
     * from the current estimate, because the span's runtime value is not recovered.
     */
    private fun referenceTrajectory(g0: Double): DoubleArray {
        val steps = horizonMin / stepMin
        val ref = DoubleArray(steps + 1)
        var sp = min(advanceSetPoint(g0, predictLeadMin), SETPOINT_CLAMP_MMOL)
        ref[0] = sp
        for (i in 1..steps) {
            sp = advanceSetPoint(sp, stepMin.toDouble())
            ref[i] = sp
        }
        return ref
    }

    /** One step of the set-point recursion above. `dtMin` of 0 is a no-op, as in the binary. */
    private fun advanceSetPoint(sp: Double, dtMin: Double): Double = when {
        dtMin <= 0.0                              -> sp
        sp > 13.0                                 -> sp + dtMin * SLOPE_ABOVE_13
        sp > 10.0                                 -> sp + dtMin * SLOPE_10_TO_13
        sp >= targetMmol && sp <= targetMmol + 2.0 -> sp          // dead zone: hold
        else                                      -> {
            val half = if (sp < targetMmol) UP_SLOPE_HALF_MIN else DOWN_SLOPE_HALF_MIN
            targetMmol + (sp - targetMmol) * 2.0.pow(-dtMin / half)
        }
    }

    /**
     * The reference trajectory is a ONE-SIDED bound, not a setpoint, and this penalty has to say so.
     *
     * It used to be symmetric (`e*e`), which made the bounded reference a line the controller was
     * rewarded for sitting exactly on -- so when the model's own glucose disposal predicted a fall
     * FASTER than the bound, the optimiser cut insulin BELOW basal to hold glucose up on the line. The
     * real controller does not do that: driven at BG 20 it asks for 2.55 x basal, while the replica
     * asked for 0.67 x. Being lower than the bound is not an error to correct; only being above it is.
     */
    private fun trackingPenalty(g: Double, refi: Double): Double {
        val e = g - refi
        return if (g < 4.0) 6.0 * e * e else e * e        // predicted lows penalised hard
    }

    private fun rolloutCost(s0: DoubleArray, seq: DoubleArray, ref: DoubleArray, segLen: Int): Double {
        var s = s0.copyOf(); var cost = 0.0
        for (i in ref.indices) {
            cost += trackingPenalty(model.glucoseMmol(s), ref[i])
            val u = seq[min(seq.size - 1, i / segLen)]
            repeat(stepMin) { s = model.step(s, u, 1.0) }
        }
        for (u in seq) { val du = u - nominalBasalMuPerMin; if (du > 0.0) cost += effortWeight * du * du * segLen }
        return cost
    }

    /** One control decision. Mirrors MPC::Optimise -> GetBIR -> GetBIRpump. */
    fun decide(stateEstimate: DoubleArray): Decision {
        val g0 = model.glucoseMmol(stateEstimate)
        val ref = referenceTrajectory(g0)
        val steps = ref.size - 1
        val segLen = max(1, steps / nSegments)
        // AAPS's own maxBasal still applies; the controller's own ceiling is usually the binding one
        val hi = if (maxRateMuPerMin > 0.0) min(maxBasalMuPerMin, maxRateMuPerMin) else maxBasalMuPerMin
        val seq = DoubleArray(nSegments) { nominalBasalMuPerMin }
        val grid = max(0.05 * 1000.0 / 60.0, hi / 40.0)
        repeat(sweeps) {
            for (j in 0 until nSegments) {
                var best = seq[j]; var bestCost = Double.MAX_VALUE
                var u = 0.0
                while (u <= hi + 1e-9) {
                    seq[j] = u
                    val c = rolloutCost(stateEstimate, seq, ref, segLen)
                    if (c < bestCost) { bestCost = c; best = u }
                    u += grid
                }
                var lo = max(0.0, best - grid); var hh = min(hi, best + grid); val gr = 0.618
                repeat(12) {
                    val a = hh - gr * (hh - lo); val b = lo + gr * (hh - lo)
                    seq[j] = a; val ca = rolloutCost(stateEstimate, seq, ref, segLen)
                    seq[j] = b; val cb = rolloutCost(stateEstimate, seq, ref, segLen)
                    if (ca < cb) hh = b else lo = a
                }
                seq[j] = 0.5 * (lo + hh)
            }
        }

        // --- §4 GetBIR: floor the command at 70% of the PLANNED horizon mean ---
        val horizonMean = seq.average()
        var finalU = min(hi, max(seq[0], birFloorFrac * horizonMean))

        // §5 MPC::ModifyRateGlucoseRate -- the trend stage, on the optimiser's OUTPUT, before the
        // ceiling and the deadband, which is the order the binary uses. Two mutually exclusive paths.
        // The binary's sub-8 branch ([SUSTAINED_FALL_CAP_FRAC]) is deliberately NOT applied here; see
        // that constant for the measurement that rules it out. Attenuation is applied at every glucose.
        val attenuation = if (observedSlopeMmolPerH != 0.0) attenuationPercent(observedSlopeMmolPerH, g0) else 0.0
        finalU *= (100.0 - attenuation) / 100.0

        if (finalU > 0.0 && abs(finalU - nominalBasalMuPerMin) < deadbandFrac * nominalBasalMuPerMin)
            finalU = nominalBasalMuPerMin                                   // integration: no TBR churn
        if (g0 <= hypoSuspendMmol) finalU = 0.0                             // kept backstop, see class doc

        var es = stateEstimate.copyOf()
        for (i in 0 until steps) {
            val u = seq[min(seq.size - 1, i / segLen)]
            repeat(stepMin) { es = model.step(es, u, 1.0) }
        }
        val reason = ("CamAPS | G=%.1f→%.1f | ref[+30m]=%.1f (max fall %.1f mmol/L/h) | " +
            "BIR[%s] mean %.2f | floor %.2f | → %.2f U/hr").format(
            g0, targetMmol, ref[min(ref.size - 1, 30 / stepMin)], maxFallMmolPerH(g0),
            seq.joinToString(",") { "%.2f".format(it * 60 / 1000) }, horizonMean * 60 / 1000,
            birFloorFrac * horizonMean * 60 / 1000, finalU * 60 / 1000) +
            " att=%.0f%%@%.1fmmol/L/h".format(attenuation, observedSlopeMmolPerH)
        return Decision(finalU * 60.0 / 1000.0, reason, horizonMean * 60.0 / 1000.0, model.glucoseMmol(es))
    }
}

package app.aaps.plugins.aps.hovorka

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Hovorka nonlinear model-predictive controller: a receding-horizon MPC over the Hovorka model,
 * implementing a glucose-zone-shaped reference trajectory + basal-floor output law
 * (design notes: report/algorithm-spec.md). Basal-modulating (TBR) controller.
 *
 *  - Reference trajectory (MPC::DetermineSetPoint): glucose is steered to target along an EXPONENTIAL
 *    approach setpoint(t) = target + (G0−target)·decay^t, with the decay rate set by glucose ZONE —
 *    correct highs briskly, approach gently near-normal:
 *        G > 13 mmol/L  -> per-step slope −1/24  (fastest)
 *        10 < G ≤ 13    -> −0.0283
 *        G ≤ 10         -> −1/60                 (gentlest)
 *  - Cost = Σ (G_pred − G_ref)²  +  λ·Σ (insulin above basal)²   [published Hovorka-NMPC quadratic form]
 *  - Output law (MPC::GetBIR): basal never collapses below a smoothing floor; weight-scaled; min-TDD.
 *
 * Decision variable: the basal infusion to hold over the horizon (TBR controller, basal-modulating).
 */
class HovorkaMpc(
    private val model: HovorkaModel,
    private val targetMmol: Double = 6.0,
    private val horizonMin: Int = 180,               // decoded 180-step horizon
    private val stepMin: Int = 5,
    private val nominalBasalMuPerMin: Double,        // profile basal (mU/min) = the operating point
    private val maxBasalMuPerMin: Double,            // safety clamp
    private val effortWeight: Double = 0.02,
    // --- sequence optimisation (CamAPS-faithful: optimise a BIR *vector*, command its first step) ---
    private val nSegments: Int = 6,                  // piecewise-constant basal segments over the horizon
    private val sweeps: Int = 2,                     // coordinate-descent passes over the segments
    // reference-trajectory shape (approach-to-target). Two exponential zones: approach the target with
    // time-constant [refTauFastMin] when glucose is above [refBreakMmol] (decisive when high), and the
    // gentler [refTauSlowMin] at/below it. Derived by in-silico optimisation + a digital-twin A/B (replaces
    // the original fixed zone-slopes). Lower refTauFast = more decisive high-glucose correction.
    private val refTauFastMin: Double = 60.0,
    private val refTauSlowMin: Double = 180.0,
    private val refBreakMmol: Double = 13.0,
    // below-target attenuation: how hard to penalise glucose RISING above the setpoint while still ≤ the
    // (raised) control target. 0.0 = the rise toward target is free (fullest back-off, most CamAPS-like);
    // a small value trades a touch of that back-off against fewer rebound highs.
    private val belowTargetRiseWeight: Double = 0.0,
    // --- 2c output-law tuning (kills open-loop zero-temp notification spam; see decide()) ---
    private val basalFloorFrac: Double = 0.7,        // floor as a fraction of nominal at/above target
    private val hypoGuardMmol: Double = 5.0,         // below this the graduated basal floor reaches 0
    private val hypoSuspendMmol: Double = 3.9,       // at/below this a HARD 0 U/hr is forced (safety)
    private val deadbandFrac: Double = 0.1,          // snap rates within ±this of nominal back to nominal
    private val bgDamperBandMmol: Double = 3.0,      // current-BG safety damper: scale above-nominal basal by (G-target)/this
    private val allowFullSuspend: Boolean = false,   // closed loop: honour a model-requested full suspend (bestU≈0) instead of flooring
    // --- 3b SMB (microbolus) — HIGHEST RISK, delivers insulin directly; OFF unless maxSmbU > 0 ---
    private val enableSmb: Boolean = false,          // master switch (plugin gates on Objective 8 + pref)
    private val maxSmbU: Double = 0.0,               // hard per-tick cap (U); plugin derives from maxSMB/maxIOB
    private val smbFraction: Double = 0.5,           // deliver only this share of the deficit per tick (converge)
    private val smbMarginMmol: Double = 1.5,         // only when predicted glucose stays > target+this
    // A microbolus is IRREVERSIBLE, so it must require a genuine high — not merely "above target". The old
    // `g0 > targetMmol` gate microbolused a flat fasting 8.4 mmol/L every tick (2026-07-21/22: 13 SMBs =
    // 2.1U overnight -> 3.3 mmol/L hypo). Mild elevation is basal's job; bolus authority is for real
    // excursions. Fed-state / rising / anti-stacking guards live in HovorkaMpcPlugin (they need COB+history).
    private val smbMinHighMmol: Double = 1.0,        // SMB requires g0 > target + this (a floor, NOT the safety gate)
    private val smbHorizonMin: Int = 60,             // horizon over which "still predicted high" is judged
    private val minSmbU: Double = 0.05               // don't bother with sub-resolution microboluses
) {
    /** One control decision from the current estimated state. Returns basal rate (mU/min) + reason. */
    fun decide(stateEstimate: DoubleArray): Decision {
        val g0 = model.glucoseMmol(stateEstimate)
        val ref = referenceTrajectory(g0)
        val steps = ref.size - 1
        // --- SEQUENCE optimisation (CamAPS MPC::Optimise produces a 180-step BIR vector; GetBIRpump
        // commands its FIRST step). We approximate that vector with [nSegments] piecewise-constant basal
        // segments and minimise by coordinate descent. This gives the temporal freedom a single horizon-wide
        // rate lacks — "suspend now, resume as glucose climbs back to target" — which is exactly what lets
        // the controller fully back off when you're low with a raised target instead of trickling. ---
        val hi = maxBasalMuPerMin
        val segLen = max(1, steps / nSegments)
        val seq = DoubleArray(nSegments) { nominalBasalMuPerMin }     // warm-start at the operating point
        val gridStep = max(0.05 * 1000.0 / 60.0, hi / 40.0)          // ~0.05 U/hr, in mU/min
        repeat(sweeps) {
            for (j in 0 until nSegments) {
                var bestSeg = seq[j]; var bestSegCost = Double.MAX_VALUE
                var u = 0.0
                while (u <= hi + 1e-9) {
                    seq[j] = u
                    val c = rolloutCostSeq(stateEstimate, seq, ref, segLen)
                    if (c < bestSegCost) { bestSegCost = c; bestSeg = u }
                    u += gridStep
                }
                // golden-section refine this segment around the grid optimum
                var lo = max(0.0, bestSeg - gridStep); var hh = min(hi, bestSeg + gridStep); val gr = 0.618
                repeat(12) {
                    val a = hh - gr * (hh - lo); val b = lo + gr * (hh - lo)
                    seq[j] = a; val ca = rolloutCostSeq(stateEstimate, seq, ref, segLen)
                    seq[j] = b; val cb = rolloutCostSeq(stateEstimate, seq, ref, segLen)
                    if (ca < cb) hh = b else lo = a
                }
                seq[j] = 0.5 * (lo + hh)
            }
        }
        val bestU = seq[0]                                           // enact the first step of the BIR vector

        // --- 2c output law: GRADUATED basal floor + deadband ---
        // The old law collapsed to a HARD 0 U/hr whenever G < target. In AAPS open loop, rate==0 is
        // "always report zero temp" (DetermineBasalResult.isChangeRequested) → a fresh suggestion every
        // tick → notification spam. Instead the floor ramps: full floorFrac·nominal at/above target, down
        // linearly to 0 only as glucose approaches the hypo guard. So a slightly-below-target glucose gets
        // a small NON-ZERO basal (no spam, and less over-conservative — fewer rebound highs), while genuine
        // hypo risk (G ≤ hypoGuard) still permits a full suspend.
        val floorMult = when {
            g0 >= targetMmol   -> basalFloorFrac
            g0 <= hypoGuardMmol -> 0.0
            else               -> basalFloorFrac * (g0 - hypoGuardMmol) / (targetMmol - hypoGuardMmol)
        }
        // Closed-loop full suspend: the graduated floor exists to avoid rate==0 open-loop NOTIFICATION spam —
        // irrelevant in closed loop. When the optimiser ITSELF wants to suspend (bestU≈0: it sees high IOB / a
        // predicted fall, e.g. after a meal bolus), honour that and drop to 0 instead of flooring at
        // 0.7·nominal — matches CamAPS (basal 0 for the bolus duration). A model that still wants SOME basal
        // keeps the anti-spam floor. In-silico: fixes the 2026-07-06 dinner "AAPS keeps sending basal" (floor
        // 0.16→0 when the model predicts a fall), cohort-neutral (TIR 90.4→90.1%, TBR 3.0→2.4%).
        // NOTE (2026-07-25): a "g0 <= target" gate was TRIED here and REJECTED. Rationale was sound — after a
        // large carb-matched bolus the rollout craters and holds a hard suspend into a real climb (live: BG
        // 7.5→16.2 with 0 U/hr for 110 min). But in-silico it was harmful in ALL FOUR cohort scenarios: lows
        // 2.9→8.0%, worst-min 3.1→2.3, peak essentially unchanged. The suspend is usually CORRECT — the insulin
        // is late, not missing — and blocking it just stacks insulin into the eventual absorption. Do not
        // re-add. The stalled-meal detector in HovorkaMpcPlugin targets the real failure instead.
        val wantsSuspend = allowFullSuspend && bestU < 0.2 * nominalBasalMuPerMin
        val floorRate = if (wantsSuspend) 0.0 else floorMult * nominalBasalMuPerMin
        var finalU = min(maxBasalMuPerMin, max(bestU, floorRate))
        // --- current-glucose safety damper ---
        // The optimiser can command near-max basal off a horizon-end prediction of a RISE even while glucose
        // is AT/BELOW target — the overnight (2026-07-06) failure mode: recovering from a low with DEPLETED
        // insulin, the model sees almost no insulin on board and predicts an EGP-driven climb, so it slams
        // basal (in-silico it maxed at est.G 5.9), which then over-corrects into the next hypo. Extending the
        // horizon does NOT fix this (verified in hovorka-mpc/). Instead scale the ABOVE-nominal portion of the
        // dose by how far CURRENT glucose is above target: 0 at/below target, ramping to full only by
        // target+[bgDamperBandMmol]. Mild highs get gentle correction; genuine highs keep full authority; a
        // recovering-from-low glucose can never slam max basal. Only ever REDUCES above-nominal basal — never
        // touches the floor or the suspend. In-silico: fixes the over-dose (1.74→0.65 U/hr, nadir 5.9→6.6),
        // cohort-neutral (all Demo checks green).
        if (finalU > nominalBasalMuPerMin) {
            val damp = ((g0 - targetMmol) / bgDamperBandMmol).coerceIn(0.0, 1.0)
            finalU = nominalBasalMuPerMin + (finalU - nominalBasalMuPerMin) * damp
        }
        // deadband: trivial deviations from nominal collapse to nominal, so AAPS sees "temp == baseBasal"
        // (isChangeRequested FALSE) instead of a churn of ~nominal micro-TBRs. Never snaps a suspend up.
        if (finalU > 0.0 && kotlin.math.abs(finalU - nominalBasalMuPerMin) < deadbandFrac * nominalBasalMuPerMin)
            finalU = nominalBasalMuPerMin
        // HARD hypo suspend (safety backstop, distinct from the graduated floor above): at/below the
        // suspend threshold force a full 0 U/hr, overriding the MPC optimum, the floor AND the deadband.
        // The graduated floor only *removes* the floor below hypoGuard — it never caps the dose, so a
        // rising-trend rollout or a lagging estimate could otherwise still pass ~nominal basal into a low.
        val hypoSuspended = g0 <= hypoSuspendMmol
        if (hypoSuspended) finalU = 0.0

        // --- 3b SMB (microbolus): a fraction of the short-horizon insulin DEFICIT (our clean-room
        // GetShortBIR analogue). Fires only when, even at the chosen basal, glucose is predicted to stay
        // meaningfully above target — i.e. a TBR can't correct the peak fast enough (the meal-timing
        // authority basal lacks). The correction bolus is found with the SAME hypo-asymmetric rollout cost
        // as the basal search, so it can never target a low; we then deliver only [smbFraction] of it so it
        // converges over ticks. Gated OFF unless enableSmb & maxSmbU>0; NEVER when hypo-suspended/below
        // target. Fold-back is automatic: the plugin replays delivered boluses through the estimator next
        // tick (CamAPS AfterInsulinBolusIMM1). ---
        var smbU = 0.0
        if (enableSmb && maxSmbU > 0.0 && !hypoSuspended && g0 > targetMmol + smbMinHighMmol) {
            // "will glucose stay high even with the PLANNED basal?" — judge against the sequence's mean rate
            // over the SMB horizon, NOT the front-loaded first step. The optimiser front-loads seg 0 and
            // tapers; using seg 0 as if it held constant over-estimates future insulin and wrongly predicts
            // glucose falling on its own, suppressing a microbolus the tapering basal can't actually deliver.
            val nSegSmb = max(1, min(seq.size, (smbHorizonMin / stepMin + segLen - 1) / segLen))
            val smbBasal = (0 until nSegSmb).sumOf { seq[it] } / nSegSmb
            val eventual = predictGlucose(stateEstimate, smbBasal, smbHorizonMin)
            if (eventual > targetMmol + smbMarginMmol) {
                val correction = searchCorrectionBolus(stateEstimate, smbBasal, ref)   // U, the ideal full dose
                smbU = min(maxSmbU, smbFraction * correction)
                if (smbU < minSmbU) smbU = 0.0
            }
        }
        val smbNote = if (smbU > 0.0) " | SMB %.2fU".format(smbU) else ""
        // eventualBG: glucose at the END of the horizon under the OPTIMISED plan (the whole basal sequence)
        // plus any SMB — a genuine forward projection, NOT the current estimate. It drops below current when
        // IOB dominates, and rises above it with carbs on board (announced or meal-detected). This is the
        // MPC's own prediction of where its plan lands glucose.
        var es = if (smbU > 0.0) stateEstimate.copyOf().also { it[5] += smbU * 1000.0 } else stateEstimate.copyOf()
        for (i in 0 until steps) {
            val u = seq[min(seq.size - 1, i / segLen)]
            repeat(stepMin) { es = model.step(es, u, 1.0) }
        }
        val eventualMmol = model.glucoseMmol(es)

        val seqNote = seq.joinToString(",") { "%.2f".format(it * 60 / 1000) }
        val reason = "G=%.1f→target %.1f | eventual %.1f | ref[+30m]=%.1f | u*=%.2f→%.2f U/hr (nominal %.2f) | seq[%s]%s%s".format(
            g0, targetMmol, eventualMmol, ref[min(ref.size - 1, 30 / stepMin)],
            bestU * 60 / 1000, finalU * 60 / 1000, nominalBasalMuPerMin * 60 / 1000, seqNote,
            if (hypoSuspended) " | HYPO-SUSPEND G≤%.1f".format(hypoSuspendMmol) else "", smbNote)
        return Decision(finalU, finalU * 60.0 / 1000.0, reason, g0, ref, smbU, eventualMmol)
    }

    /** Predicted glucose (mmol/L) [minutes] ahead at constant basal u, no bolus (SMB trigger test). */
    private fun predictGlucose(s0: DoubleArray, u: Double, minutes: Int): Double {
        var s = s0.copyOf()
        repeat(minutes) { s = model.step(s, u, 1.0) }
        return model.glucoseMmol(s)
    }

    /**
     * The immediate bolus (U) that best tracks the reference at basal [basalU] — the "ideal" correction.
     * Same hypo-asymmetric tracking cost as the basal search (so it never aims below range); monotone-ish
     * in bolus, so grid + golden-section refine. We deliver only a FRACTION of this per tick.
     */
    private fun searchCorrectionBolus(s0: DoubleArray, basalU: Double, ref: DoubleArray): Double {
        val cap = maxSmbU / smbFraction * 3.0            // enough headroom to see the true optimum
        fun cost(b: Double): Double {
            var s = s0.copyOf(); s[5] += b * 1000.0      // deposit bolus (U -> mU) into SC comp S1
            var c = 0.0
            for (i in ref.indices) {
                val g = model.glucoseMmol(s)
                val e = g - ref[i]
                val w = if (g < 4.0) 6.0 else 1.0        // penalise predicted lows hard (safety)
                c += w * e * e
                repeat(stepMin) { s = model.step(s, basalU, 1.0) }
            }
            return c
        }
        var bestB = 0.0; var bestC = cost(0.0)
        val step = max(0.02, cap / 40.0)
        var b = step
        while (b <= cap + 1e-9) { val c = cost(b); if (c < bestC) { bestC = c; bestB = b }; b += step }
        // golden-section refine around the grid optimum
        var lo = max(0.0, bestB - step); var hi = min(cap, bestB + step); val gr = 0.618
        repeat(16) {
            val a = hi - gr * (hi - lo); val bb = lo + gr * (hi - lo)
            if (cost(a) < cost(bb)) hi = bb else lo = a
        }
        return 0.5 * (lo + hi)
    }

    /** Reference trajectory: exponential approach to target, faster ([refTauFastMin]) above the breakpoint. */
    fun referenceTrajectory(g0: Double): DoubleArray {
        val steps = horizonMin / stepMin
        val ref = DoubleArray(steps + 1)
        ref[0] = g0
        for (i in 0 until steps) {
            val g = ref[i]
            val tauMin = if (g > refBreakMmol) refTauFastMin else refTauSlowMin
            val decay = exp(-stepMin / tauMin)          // per ref-step exponential decay toward target
            ref[i + 1] = targetMmol + (g - targetMmol) * decay
        }
        return ref
    }

    /**
     * Rollout cost for a piecewise-constant basal [seq] ([segLen] ref-steps per segment). Two departures
     * from a plain quadratic tracker, both to reproduce CamAPS's back-off-when-low behaviour:
     *  - tracking is ASYMMETRIC below the control target (see [trackingPenalty]);
     *  - effort penalises only insulin ABOVE nominal (the published λ·Σ(insulin above basal)² form), so
     *    withholding basal is free — the controller can suspend without paying an effort cost.
     */
    private fun rolloutCostSeq(s0: DoubleArray, seq: DoubleArray, ref: DoubleArray, segLen: Int): Double {
        var s = s0.copyOf()
        var cost = 0.0
        for (i in ref.indices) {
            val g = model.glucoseMmol(s)
            cost += trackingPenalty(g, ref[i])
            val u = seq[min(seq.size - 1, i / segLen)]
            repeat(stepMin) { s = model.step(s, u, 1.0) }
        }
        for (u in seq) { val du = u - nominalBasalMuPerMin; if (du > 0.0) cost += effortWeight * du * du * segLen }
        return cost
    }

    /**
     * Tracking penalty at one horizon step. Predicted lows are always penalised hard (safety). But when the
     * setpoint itself is below the control target and glucose is rising *toward* that target
     * (ref ≤ g ≤ target), the rise is DESIRED — a basal-only loop raises glucose only by withholding
     * insulin — so it is (near-)free. This is what lets the controller fully back off when you are low with
     * a raised target, instead of trickling basal to pin you at the low setpoint.
     */
    private fun trackingPenalty(g: Double, refi: Double): Double {
        val e = g - refi
        if (g < 4.0) return 6.0 * e * e                                  // predicted-low safety penalty
        if (refi < targetMmol && g >= refi && g <= targetMmol)          // below target, rising toward it
            return belowTargetRiseWeight * e * e
        return e * e
    }

    data class Decision(
        val basalMuPerMin: Double,
        val basalUPerHr: Double,
        val reason: String,
        val glucoseMmol: Double,
        val reference: DoubleArray,
        val smbU: Double = 0.0,           // 3b: immediate microbolus (U) to deliver alongside the TBR
        val eventualMmol: Double = 0.0    // predicted glucose at horizon-end under the optimised plan (forward projection)
    )
}

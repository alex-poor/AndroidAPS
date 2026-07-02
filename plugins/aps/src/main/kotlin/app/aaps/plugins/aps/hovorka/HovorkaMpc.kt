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
    // --- 2c output-law tuning (kills open-loop zero-temp notification spam; see decide()) ---
    private val basalFloorFrac: Double = 0.7,        // floor as a fraction of nominal at/above target
    private val hypoGuardMmol: Double = 5.0,         // below this the graduated basal floor reaches 0
    private val hypoSuspendMmol: Double = 3.9,       // at/below this a HARD 0 U/hr is forced (safety)
    private val deadbandFrac: Double = 0.1           // snap rates within ±this of nominal back to nominal
) {
    /** One control decision from the current estimated state. Returns basal rate (mU/min) + reason. */
    fun decide(stateEstimate: DoubleArray): Decision {
        val g0 = model.glucoseMmol(stateEstimate)
        val ref = referenceTrajectory(g0)
        // 1-D search over candidate basal rates (glucose response is monotone in u over the horizon)
        var bestU = nominalBasalMuPerMin; var bestCost = Double.MAX_VALUE
        val hi = maxBasalMuPerMin
        var u = 0.0
        val gridStep = max(0.05, hi / 60.0)
        while (u <= hi + 1e-9) {
            val cost = rolloutCost(stateEstimate, u, ref)
            if (cost < bestCost) { bestCost = cost; bestU = u }
            u += gridStep
        }
        // refine around best with a golden-section pass
        bestU = refine(stateEstimate, ref, max(0.0, bestU - gridStep), min(hi, bestU + gridStep))

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
        var finalU = min(maxBasalMuPerMin, max(bestU, floorMult * nominalBasalMuPerMin))
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
        val reason = "G=%.1f→target %.1f | ref[+30m]=%.1f | u*=%.2f→%.2f U/hr (nominal %.2f)%s".format(
            g0, targetMmol, ref[min(ref.size - 1, 30 / stepMin)],
            bestU * 60 / 1000, finalU * 60 / 1000, nominalBasalMuPerMin * 60 / 1000,
            if (hypoSuspended) " | HYPO-SUSPEND G≤%.1f".format(hypoSuspendMmol) else "")
        return Decision(finalU, finalU * 60.0 / 1000.0, reason, g0, ref)
    }

    /** Reference trajectory: exponential approach to target with glucose-zone-dependent decay. */
    fun referenceTrajectory(g0: Double): DoubleArray {
        val steps = horizonMin / stepMin
        val ref = DoubleArray(steps + 1)
        ref[0] = g0
        for (i in 0 until steps) {
            val g = ref[i]
            val slopePerMin = when {                 // decoded per-step slopes (per ~1 unit); scaled to /min
                g > 13.0 -> -1.0 / 24.0
                g > 10.0 -> -0.0283
                else     -> -1.0 / 60.0
            }
            val decay = exp(slopePerMin * stepMin / 5.0)   // slopes at ~5-min cadence
            ref[i + 1] = targetMmol + (g - targetMmol) * decay
        }
        return ref
    }

    private fun rolloutCost(s0: DoubleArray, u: Double, ref: DoubleArray): Double {
        var s = s0.copyOf()
        var cost = 0.0
        val du = u - nominalBasalMuPerMin
        for (i in ref.indices) {
            val g = model.glucoseMmol(s)
            val e = g - ref[i]
            // asymmetric: penalise hypo (predicted low) harder — safety
            val w = if (g < 4.0) 6.0 else 1.0
            cost += w * e * e
            repeat(stepMin) { s = model.step(s, u, 1.0) }
        }
        cost += effortWeight * du * du * ref.size
        return cost
    }

    private fun refine(s0: DoubleArray, ref: DoubleArray, lo0: Double, hi0: Double): Double {
        var lo = lo0; var hi = hi0; val gr = 0.618
        repeat(20) {
            val a = hi - gr * (hi - lo); val b = lo + gr * (hi - lo)
            if (rolloutCost(s0, a, ref) < rolloutCost(s0, b, ref)) hi = b else lo = a
        }
        return 0.5 * (lo + hi)
    }

    data class Decision(
        val basalMuPerMin: Double,
        val basalUPerHr: Double,
        val reason: String,
        val glucoseMmol: Double,
        val reference: DoubleArray
    )
}

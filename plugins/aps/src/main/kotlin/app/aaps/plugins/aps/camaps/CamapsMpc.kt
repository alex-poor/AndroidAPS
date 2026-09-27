package app.aaps.plugins.aps.camaps

import app.aaps.plugins.aps.hovorka.HovorkaModel
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

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
 *  - §5 REFERENCE TRAJECTORY. setpoint(t) = T + (G0-T)*exp(-t/tau), with the decoded per-minute zone
 *    slopes acting as a CEILING on the demanded rate of fall:
 *        glucose > 13      -> 1/24    mmol/L/min = 2.5 mmol/L/h
 *        10 < glucose <= 13 -> 0.02833 mmol/L/min = 1.7 mmol/L/h
 *        glucose <= 10      -> 1/60    mmol/L/min = 1.0 mmol/L/h
 *    This is the property the fork dropped. Unbounded, the exponential demands (G0-target)/tau*60 — at
 *    15.9 mmol/L that is 8.9 mmol/L/h, 3.6x what CamAPS would ask for, and it is what every containment
 *    guard was subsequently built to undo.
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
    /** τ of the exponential approach (min). The zone ceiling below is what actually bounds it. */
    private val refTauMin: Double = 120.0,
    /** GetBIR floor as a fraction of the PLANNED horizon mean (decoded 0.7). */
    private val birFloorFrac: Double = 0.7,
    /**
     * Ceiling on the command as a multiple of the profile basal. MEASURED off the real controller,
     * not decoded: driving it at BG 20 rising hard over profile basals 0.20-3.20 U/h returns
     * 2.500, 2.500, 2.556, 2.583, 2.529, 2.542, 2.562, 2.542, 2.547 x basal -- every one consistent
     * with 2.55 after the pump's 0.05 U/h rounding. It never asks for more, at any glucose level.
     * See report/camaps-measured-response.md §2.
     */
    private val maxGainOverBasal: Double = 2.55,
    /**
     * Hard suspend threshold. MEASURED at 4.5: on flat glucose the real controller returns exactly
     * 0.00 U/h up to BG 4.500 and 0.79 x basal at 4.625, bang-bang with no ramp, and the threshold
     * is identical at profile basals 0.45/0.85/2.40 so it is absolute, not scaled. The decoded spec
     * showed no such layer, which was a gap in the RE, not a design decision -- so this is now a
     * replicated behaviour rather than the bolted-on backstop it started as.
     */
    private val hypoSuspendMmol: Double = 4.5,
    private val deadbandFrac: Double = 0.1
) {

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
    private fun referenceTrajectory(g0: Double): DoubleArray {
        val steps = horizonMin / stepMin
        val ref = DoubleArray(steps + 1)
        ref[0] = g0
        for (i in 1..steps) {
            val ideal = targetMmol + (g0 - targetMmol) * exp(-(i * stepMin).toDouble() / refTauMin)
            val ceiling = ref[i - 1] - maxFallMmolPerH(ref[i - 1]) * stepMin / 60.0
            ref[i] = max(ideal, min(ref[i - 1], ceiling))
        }
        return ref
    }

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
        // AAPS's own maxBasal still applies, but the controller's own ceiling is the binding one
        val hi = min(maxBasalMuPerMin, maxGainOverBasal * nominalBasalMuPerMin)
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
            birFloorFrac * horizonMean * 60 / 1000, finalU * 60 / 1000)
        return Decision(finalU * 60.0 / 1000.0, reason, horizonMean * 60.0 / 1000.0, model.glucoseMmol(es))
    }
}

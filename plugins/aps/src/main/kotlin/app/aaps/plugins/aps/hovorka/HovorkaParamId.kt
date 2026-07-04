package app.aaps.plugins.aps.hovorka

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * BAYESIAN / MAP RE-IDENTIFICATION of the patient's structural Hovorka parameters from a window of the
 * user's OWN closed-loop history (CGM + delivered basal + boluses + announced carbs).
 *
 * WHY (beyond 2a personalise + 2d adaptive-TDD):
 *   - [HovorkaParams.personalize] fits insulin sensitivity / EGP / carb bioavailability ONCE, from the
 *     titrated ISF/IC in the AAPS profile. It never revisits them.
 *   - [TddAdapter] walks the operating BASAL point day-to-day, but that is a single scalar gain on the whole
 *     insulin need — it cannot fix a *shape* error (e.g. corrections landing right while meals overshoot, or a
 *     seasonal insulin-sensitivity drift that the profile ISF no longer reflects).
 * This identifier closes that gap: it re-fits the model itself, slowly and safely, from what actually happened.
 *
 * DESIGN — the whole point is to be SAFE on ONE person's noisy data (no population set, no simulator):
 *   1. PRIOR-ANCHORED. The [prior] params (the personalise() output) ARE the prior mean. Each parameter is a
 *      multiplier θ on the prior with a Gaussian prior in log-space; with no evidence the MAP estimate == prior.
 *   2. ONLY EXCITED PARAMETERS MOVE. After the fit we measure each parameter's Fisher information (the curvature
 *      of the data-fit term). If the data barely constrains it (information share below [gateFrac]) the apparent
 *      move is prior-/noise-dominated, so we PIN it back to the prior. This is the rigorous version of the
 *      personalise() hardening ("ISF pinned-at-target"): SI identifies from weeks of corrections/meals; EGP needs
 *      clean overnight fasting; carb bioavailability needs clean meals — on a given window some simply aren't
 *      identifiable, and those must not drift.
 *   3. GENTLE + BOUNDED. Per call each multiplier is box-constrained close to 1.0 ([siBounds]/[egpBounds]/
 *      [agBounds]) — a single re-ID nudges, it never leaps. Apply repeatedly (e.g. weekly) to track slow drift.
 *   4. IMPROVEMENT-GATED. We only accept the fit if the held-in 30-min-ahead forecast RMSE improves by at least
 *      [minImproveFrac]; otherwise we return the prior unchanged. Guards against degenerate/overfit solutions.
 *
 * LIKELIHOOD. We score the model by k-step-ahead (default 30 min) prediction error under the recorded inputs,
 * with the EKF filtering the hidden state between samples. This is the same windowed-forecast discriminator the
 * IMM bank uses (report/hovorka-plugin-plan.md 3a): 1-step innovation is a poor identifier because the EKF
 * corrects glucose to the CGM-noise floor regardless of parameter error — the parameters only earn their keep
 * when PREDICTING the hidden trajectory forward, which is exactly what the MPC rolls out.
 *
 * This is an OFFLINE BATCH step (run it nightly/weekly against the log), not a per-tick loop element.
 */
class HovorkaParamId(
    private val prior: HovorkaParams,
    private val predSdMmol: Double = 1.2,          // expected 30-min forecast error scale (likelihood weighting)
    private val tauSi: Double = 0.35,              // log-space prior std — SI is the most identifiable (loosest)
    private val tauEgp: Double = 0.20,             // EGP needs fasting excitation — tighter prior
    private val tauAg: Double = 0.15,              // carb bioavailability needs clean meals — tightest
    private val gateFrac: Double = 0.30,           // pin a param unless data supplies >=30% of its posterior precision
    private val minImproveFrac: Double = 0.02,     // reject the fit unless forecast RMSE improves >=2%
    private val horizonMin: Int = 30,              // forecast horizon for the prediction-error score
    private val obsGain: Double = 0.5,             // fixed-gain glucose observer blend toward CGM per tick
    private val warmupMin: Int = 150,              // let the observer settle before scoring
    private val scoreStrideTicks: Int = 3,         // score every 3rd tick (15 min) — cuts cost, ample samples
    private val siBounds: Pair<Double, Double> = 0.6 to 1.6,
    private val egpBounds: Pair<Double, Double> = 0.7 to 1.4,
    private val agBounds: Pair<Double, Double> = 0.8 to 1.25,
    private val minSamples: Int = 288              // require >= 1 day of 5-min samples
) {
    private val stepMin = 5.0                       // samples are on a 5-min grid
    private val hTicks = maxOf(1, horizonMin / stepMin.toInt())
    private val warmupTicks = warmupMin / stepMin.toInt()

    /** Re-identify parameters from [samples] (chronological, 5-min cadence). */
    fun identify(samples: List<IdSample>): IdResult {
        if (samples.size < minSamples)
            return IdResult(prior, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, emptyList(), 0.0, 0.0, false,
                "insufficient data (${samples.size} < $minSamples samples)")

        // log-space bounds for the coordinate optimiser
        val lo = doubleArrayOf(ln(siBounds.first), ln(egpBounds.first), ln(agBounds.first))
        val hi = doubleArrayOf(ln(siBounds.second), ln(egpBounds.second), ln(agBounds.second))
        val tau = doubleArrayOf(tauSi, tauEgp, tauAg)

        // prior-baseline fit (θ = 0) — the number we must beat
        val (ssePrior, nScore) = sse(doubleArrayOf(0.0, 0.0, 0.0), samples)
        if (nScore <= 0)
            return IdResult(prior, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, emptyList(), 0.0, 0.0, false,
                "no scorable samples (window too short for the ${horizonMin}m horizon)")
        val rmsePrior = sqrt(ssePrior / nScore)

        // objective J(θ) = data-fit + log-space Gaussian prior
        fun cost(theta: DoubleArray): Double {
            val (s, _) = sse(theta, samples)
            var j = s / (2.0 * predSdMmol * predSdMmol)
            for (p in 0..2) j += theta[p] * theta[p] / (2.0 * tau[p] * tau[p])
            return j
        }

        // coordinate descent with golden-section line search (near-orthogonal params → 2 sweeps converge)
        val theta = doubleArrayOf(0.0, 0.0, 0.0)
        repeat(2) {
            for (p in 0..2) theta[p] = minimizeCoord(theta, p, lo[p], hi[p], ::cost)
        }

        // Fisher-information gate: pin any parameter the data does not meaningfully constrain
        val shares = DoubleArray(3)
        val moved = ArrayList<String>()
        val names = arrayOf("SI", "EGP", "carbAbs")
        for (p in 0..2) {
            val info = likelihoodCurvature(theta, p, samples)   // curvature of the data-fit term
            val priorPrec = 1.0 / (tau[p] * tau[p])
            shares[p] = if (info + priorPrec > 0) info / (info + priorPrec) else 0.0
            if (shares[p] < gateFrac) theta[p] = 0.0            // not excited → pin to prior
        }
        // clean-up sweep over the still-free params now that pinned ones are fixed at prior
        repeat(1) { for (p in 0..2) if (theta[p] != 0.0) theta[p] = minimizeCoord(theta, p, lo[p], hi[p], ::cost) }
        for (p in 0..2) if (abs(theta[p]) > 1e-4) moved.add(names[p])

        val (sseFit, _) = sse(theta, samples)
        val rmseFit = sqrt(sseFit / nScore)

        val siMul = exp(theta[0]); val egpMul = exp(theta[1]); val agMul = exp(theta[2])
        val accepted = moved.isNotEmpty() && rmseFit <= rmsePrior * (1.0 - minImproveFrac)
        if (!accepted)
            return IdResult(prior, 1.0, 1.0, 1.0, shares[0], shares[1], shares[2], emptyList(),
                rmsePrior, rmseFit, false,
                if (moved.isEmpty()) "no parameter excited by this window (all pinned to prior)"
                else "fit did not improve forecast RMSE by >=${(minImproveFrac * 100).toInt()}%% (%.2f→%.2f) — kept prior".format(rmsePrior, rmseFit))

        val out = prior.copy(
            kb1 = prior.kb1 * siMul, kb2 = prior.kb2 * siMul, kb3 = prior.kb3 * siMul,
            egp0 = prior.egp0 * egpMul,
            ag = (prior.ag * agMul).coerceIn(0.3, 1.0)
        )
        val reason = "re-ID moved {${moved.joinToString(",")}}: SI×%.2f EGP×%.2f carbAbs×%.2f  (info-share SI=%.2f EGP=%.2f abs=%.2f)  RMSE %.2f→%.2f mmol/L"
            .format(siMul, egpMul, agMul, shares[0], shares[1], shares[2], rmsePrior, rmseFit)
        return IdResult(out, siMul, egpMul, agMul, shares[0], shares[1], shares[2], moved, rmsePrior, rmseFit, true, reason)
    }

    /** Params for a log-multiplier vector θ = (log SI, log EGP, log carbAbs). */
    private fun paramsFor(theta: DoubleArray): HovorkaParams {
        val si = exp(theta[0]); val eg = exp(theta[1]); val ag = exp(theta[2])
        return prior.copy(
            kb1 = prior.kb1 * si, kb2 = prior.kb2 * si, kb3 = prior.kb3 * si,
            egp0 = prior.egp0 * eg,
            ag = (prior.ag * ag).coerceIn(0.3, 1.0)
        )
    }

    /**
     * Sum of squared 30-min-ahead forecast errors under the recorded inputs. Returns (sumSq, nScored).
     *
     * The hidden state is tracked by a cheap fixed-gain glucose observer (propagate with the model, then blend
     * the glucose compartment toward the CGM by [obsGain] each tick) rather than the full EKF — for parameter
     * IDENTIFICATION we only need the model's PREDICTIVE error, and the covariance machinery (finite-difference
     * Jacobian + 10×10 matmuls per tick) is pure overhead here, ~100× the cost. The observer is identical across
     * candidate parameters, so its residual bias is common-mode and cancels in the fit. At each scored tick we
     * roll the model forward [hTicks] under the ACTUAL future basal/bolus/carbs and compare to the CGM that
     * later arrived — the same windowed-forecast discriminator the IMM bank uses.
     */
    private fun sse(theta: DoubleArray, samples: List<IdSample>): Pair<Double, Int> {
        val model = HovorkaModel(paramsFor(theta))
        val vg = model.p.vg
        val u0 = samples[0].basalUPerHr * 1000.0 / 60.0
        var s = model.steadyState(u0, minutes = 400)
        val g0 = model.glucoseMmol(s)
        if (g0 > 1e-6) { val sc = samples[0].cgmMmol / g0; s[0] *= sc; s[1] *= sc }

        var sumSq = 0.0; var n = 0
        for (i in samples.indices) {
            val smp = samples[i]
            if (i > 0) {
                if (smp.carbsG > 0.0) s = model.addMeal(s, smp.carbsG)
                if (smp.bolusU > 0.0) { s = s.copyOf(); s[5] += smp.bolusU * 1000.0 }
                s = model.step(s, smp.basalUPerHr * 1000.0 / 60.0, stepMin)
            }
            // fixed-gain glucose correction: nudge Q1 toward the measurement (Q2 follows via k12 coupling)
            val corrMmol = (smp.cgmMmol - s[0] / vg) * obsGain
            s = s.copyOf(); s[0] = maxOf(0.0, s[0] + vg * corrMmol)
            if (i >= warmupTicks && i % scoreStrideTicks == 0 && i + hTicks < samples.size) {
                val pred = forwardGlucoseMmol(model, s.copyOf(), samples, i)
                val r = pred - samples[i + hTicks].cgmMmol
                sumSq += r * r; n++
            }
        }
        return sumSq to n
    }

    /** Roll [model] forward [hTicks] from [start], applying the recorded inputs at ticks from+1..from+hTicks. */
    private fun forwardGlucoseMmol(model: HovorkaModel, start: DoubleArray, samples: List<IdSample>, from: Int): Double {
        var s = start
        for (k in 1..hTicks) {
            val smp = samples[from + k]
            if (smp.carbsG > 0.0) s = model.addMeal(s, smp.carbsG)
            if (smp.bolusU > 0.0) { s = s.copyOf(); s[5] += smp.bolusU * 1000.0 }
            s = model.step(s, smp.basalUPerHr * 1000.0 / 60.0, stepMin)
        }
        return model.glucoseMmol(s)
    }

    /** Curvature (Fisher information) of the DATA-FIT term w.r.t. coordinate [p], central finite difference. */
    private fun likelihoodCurvature(theta: DoubleArray, p: Int, samples: List<IdSample>): Double {
        val d = 0.05
        fun fit(t: DoubleArray) = sse(t, samples).first / (2.0 * predSdMmol * predSdMmol)
        val tp = theta.copyOf().also { it[p] += d }
        val tm = theta.copyOf().also { it[p] -= d }
        val c = (fit(tp) - 2.0 * fit(theta) + fit(tm)) / (d * d)
        return maxOf(0.0, c)
    }

    /** Golden-section minimisation of [f] over coordinate [p] in [lo,hi] with the others fixed. */
    private fun minimizeCoord(theta: DoubleArray, p: Int, lo: Double, hi: Double, f: (DoubleArray) -> Double): Double {
        val gr = (sqrt(5.0) - 1.0) / 2.0
        var a = lo; var b = hi
        val work = theta.copyOf()
        fun at(x: Double): Double { work[p] = x; return f(work) }
        var c = b - gr * (b - a); var fc = at(c)
        var e = a + gr * (b - a); var fe = at(e)
        repeat(12) {
            if (fc < fe) { b = e; e = c; fe = fc; c = b - gr * (b - a); fc = at(c) }
            else { a = c; c = e; fc = fe; e = a + gr * (b - a); fe = at(e) }
        }
        return 0.5 * (a + b)
    }
}

/** One 5-min sample of closed-loop history for [HovorkaParamId]. */
data class IdSample(
    val cgmMmol: Double,           // CGM at this tick (mmol/L)
    val basalUPerHr: Double,       // basal delivered over the preceding 5 min (U/hr, TBR-adjusted actual)
    val bolusU: Double = 0.0,      // bolus delivered at this tick (U), if any
    val carbsG: Double = 0.0       // announced carbs at this tick (g), if any
)

/** Result of a re-identification pass. [accepted]=false ⇒ [params]==prior (nothing changed). */
data class IdResult(
    val params: HovorkaParams,
    val siMul: Double, val egp0Mul: Double, val agMul: Double,
    val siInfoShare: Double, val egpInfoShare: Double, val agInfoShare: Double,
    val moved: List<String>,
    val rmsePriorMmol: Double, val rmseFitMmol: Double,
    val accepted: Boolean,
    val reason: String
)

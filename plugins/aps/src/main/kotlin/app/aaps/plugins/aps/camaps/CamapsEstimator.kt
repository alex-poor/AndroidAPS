package app.aaps.plugins.aps.camaps

import app.aaps.plugins.aps.hovorka.GlucoseEstimator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * CamAPS's estimator: an interacting-multiple-model bank of 8 [CamapsSubModel] EKFs.
 *
 * Decoded structure, all from named globals (report/camaps-model-spec.md §3):
 *  - the 8 submodels differ in their two gut time constants (`tMaxG1s`, `tMaxG2s`) and in an
 *    insulin-sensitivity multiplier (`multWktInsIni`);
 *  - initial covariance is scaled per submodel by `multWini` = [1,1,2,2,1,1,2,2];
 *  - mode transitions use `halfTimeTran` = [17, 60, 180] min;
 *  - the prior over submodels depends on the **meal size class**, which is carbohydrate measured against
 *    body weight (`weightCategory` x `mealSizeForWeightCategory`), via `priorMealProb`;
 *  - bioavailability `f` is an estimated state with prior N(1.0, 0.3²) clamped to [0.2, 2.2], informed only
 *    while there is carbohydrate in the gut.
 *
 * Unrecovered and therefore chosen here: the measurement noise, the process noise on each state, and the
 * EGP half-effect. They are exposed so they can be fitted against the patient's own data rather than
 * guessed — which is what `CamapsRealFit` does.
 */
class CamapsEstimator(
    private val weightKg: Double,
    isfMmolPerU: Double,
    basalUPerHr: Double,
    egpHalfMuPerL: Double = 20.0,
    private val measNoiseVar: Double = 0.5,
    private val qGlucose: Double = 1e-3,
    private val qInsulin: Double = 1e-3,
    private val qGut: Double = 1e-2,
    private val qFlux: Double = 1e-3,
    private val qBio: Double = 1e-4,
    /**
     * Process noise on the log-scale absorption state — `SubModel1::Learn`'s adaptation.
     *
     * DEFAULT 0, i.e. the state exists and is frozen, because it was measured to add nothing on this
     * patient's 30 days. Re-measured after the covariance fix below, at the shipped qFlux of 3e-2, over
     * 29024 forecasts (30/60/120-min RMSE against actual future CGM):
     * ```
     *   qAbs = 0      1.477  2.821  5.568     <- shipped
     *   qAbs = 1e-2   1.556  2.986  6.257
     *   qAbs = 1e-1   1.586  3.097  6.652
     *   qAbs = 1e0    1.591  3.137  6.806
     * ```
     * Monotonically worse, so it stays frozen. The IMM bank already spans absorption via
     * `tMaxG1s`/`tMaxG2s`, so per-submodel tuning on top of it is redundant here. The mechanism is
     * replicated and switched off on evidence, not omitted.
     */
    private val qAbs: Double = 0.0,
    /** Mode-transition dwell time, from `halfTimeTran`. */
    private val tauTranMin: Double = CamapsSubModel.HALF_TIME_TRAN[0],
    /** Glucose the plant's basal anchor is struck at, mmol/L — see CamapsSubModel.gRefMmol. */
    private val gRefMmol: Double = 5.8,
    /** ∝G disposal blend — see CamapsSubModel.GLUCOSE_DEPENDENT_DISPOSAL. */
    private val gDisposal: Double = CamapsSubModel.GLUCOSE_DEPENDENT_DISPOSAL,
    /**
     * Multiplier on the ISF used to derive SI. 1.0 uses the patient's profile ISF unchanged.
     *
     * ⛔ Exists because of a hypothesis that was **measured and rejected**. The binary's input carries
     * weight, basal profile and TDD only — no ISF and no IC — so it must derive insulin sensitivity
     * itself, and at the probes' basal-only TDD of 20.4 U a TDD-derived ISF (~100/TDD) would be about
     * 4.9 mmol/L/U against this patient's profile 2.3. That would have explained the one region where the
     * replica still disagrees: flat glucose 10.5–14, where it asks for its ceiling and the binary asks for
     * 1.65–2.06.
     *
     * It does not. Swept over the 826-point reference:
     * ```
     *   siScale   implied ISF   level   trend  low+fall  post-meal   unsafe
     *     1.0         2.3       0.173   0.147     0.097      0.271        0   <- shipped
     *     1.4         3.2       0.171   0.151     0.066      0.255        1
     *     1.8         4.1       0.168   0.160     0.047      0.245        2
     *     2.1         4.8       0.164   0.167     0.054      0.259        3
     *     3.0         6.9       0.157   0.189     0.060      0.391        5
     * ```
     * The level arm barely moves across a 3x change in sensitivity (0.173 → 0.157) while safety cells
     * appear immediately. So that disagreement is not about how much glucose a unit of insulin buys, and
     * the patient's own ISF stays.
     */
    private val siScale: Double = 1.0,
    /** Flux half-life, minutes; 0 = random walk. */
    private val fluxHalfMin: Double = 0.0,
    /**
     * Covariance-inflation half-life, minutes — `forgettingHalfTime` (0x022d28) = 150. 0 disables it.
     * See the note in [predict].
     */
    private val forgettingHalfMin: Double = FORGETTING_HALF_MIN
) : GlucoseEstimator {

    private val n = 9
    private val nm = 8
    private val models = Array(nm) {
        CamapsSubModel.forProfile(it, weightKg, isfMmolPerU, basalUPerHr, egpHalfMuPerL,
                                  gRefMmol = gRefMmol, gDisposal = gDisposal, siScale = siScale,
                                  fluxHalfMin = fluxHalfMin)
    }
    private val basalMu = basalUPerHr * 1000.0 / 60.0
    private val xs = Array(nm) { models[it].steadyState(basalMu, 7.0) }
    private val Ps = Array(nm) { k -> diag(n) { i -> initVar(i) * CamapsSubModel.MULT_W_INI[k] } }
    private var mu = DoubleArray(nm) { 1.0 / nm }
    /** Most recent meal size class; selects the `priorMealProb` column. */
    private var mealCls = 0

    private fun initVar(i: Int) = when (i) {
        CamapsSubModel.FX -> 0.01
        CamapsSubModel.F  -> CamapsSubModel.F_PRIOR_SD * CamapsSubModel.F_PRIOR_SD
        CamapsSubModel.LG -> 0.25          // log-space prior, sd 0.5 => absorption within ~x1.6
        CamapsSubModel.Q1 -> 5.0
        CamapsSubModel.D1, CamapsSubModel.D2 -> 5.0
        else -> 1.0
    }

    private fun qDiag(i: Int) = when (i) {
        CamapsSubModel.Q1 -> qGlucose
        CamapsSubModel.S1, CamapsSubModel.S2, CamapsSubModel.I -> qInsulin
        CamapsSubModel.D1, CamapsSubModel.D2 -> qGut
        CamapsSubModel.FX -> qFlux
        CamapsSubModel.F  -> qBio
        CamapsSubModel.LG -> qAbs
        else -> 1e-6
    }

    companion object {

        /**
         * `forgettingHalfTime` (0x022d28) = **150** minutes — the half-life of the covariance inflation
         * `SubModel1::PredictStep` computes as `exp(+ln2 * dt / forgettingHalfTime)` and stores to
         * `SubModel1::forgettingFactor`. Decoded, and real.
         *
         * ⚠️ The transcription is INCOMPLETE: in the binary the inflation sits behind two guards
         * (0x55330–0x55348 — a magnitude test on `this+0x21c` against `smallVal`, and `this+0x4c == 1`)
         * which are not decoded. The plausible reading is "time actually advanced" and "estimator mode",
         * which is where this applies it, but that is inference.
         *
         * It was briefly shipped OFF on a measurement showing it degraded the 30-day forecast by 80%.
         * **That measurement was wrong** — an artefact of `CamapsRealFit` running ONE filter across the
         * whole stream, where inflation compounds without bound, while `CamapsPlugin.estimateState`
         * rebuilds the filter from a 6-hour window every tick, where it is bounded at 2^(360/150) ≈ 5x.
         * Measured the way the plugin actually uses it:
         * ```
         *   forgettingHalf   30 min   60 min   120 min
         *        0            1.522    2.665     4.862
         *      150            1.548    2.691     4.868     <- shipped
         * ```
         * ~1.7% at 30 minutes and under 1% beyond, against a mechanism that is decoded. It ships.
         */
        const val FORGETTING_HALF_MIN = 150.0
    }

    override val x: DoubleArray get() = DoubleArray(n) { i -> (0 until nm).sumOf { k -> mu[k] * xs[k][i] } }
    override fun glucoseMmol() = (0 until nm).sumOf { k -> mu[k] * models[k].glucoseMmol(xs[k]) }

    override fun meal(carbsG: Double) {
        mealCls = CamapsSubModel.mealClass(weightKg, carbsG)
        for (k in 0 until nm) xs[k] = models[k].addMeal(xs[k], carbsG)
        // a meal re-opens the prior over submodels, weighted by the class: priorMealProb[k][cls]
        val pr = DoubleArray(nm) { k -> CamapsSubModel.PRIOR_MEAL_PROB[k][mealCls] }
        val z = pr.sum()
        if (z > 0) for (k in 0 until nm) mu[k] = 0.5 * mu[k] + 0.5 * pr[k] / z
        normalise()
    }

    override fun bolus(unitsU: Double) { for (k in 0 until nm) xs[k] = models[k].addBolus(xs[k], unitsU) }

    override fun predict(u: Double, dtMin: Double) {
        // IMM mixing: exponential dwell with half-time tauTranMin
        val stay = 0.5.pow(dtMin / tauTranMin)
        val off = (1.0 - stay) / (nm - 1)
        val mixed = DoubleArray(nm) { j -> (0 until nm).sumOf { i -> (if (i == j) stay else off) * mu[i] } }
        mu = mixed; normalise()
        // `SubModel1::PredictStep` computes  forgettingFactor = exp(+ln2 * dt / forgettingHalfTime)
        // (globals `ln2` 0x8d648 and `forgettingHalfTime` 0x8d6d0 = 150 min) and stores it to
        // `SubModel1::forgettingFactor`. The exponent is POSITIVE, so it inflates the covariance —
        // exponential forgetting, which stops the filter becoming over-confident and keeps it tracking.
        // This replica had additive process noise only.
        val ff = if (forgettingHalfMin > 0.0) 2.0.pow(dtMin / forgettingHalfMin) else 1.0
        for (k in 0 until nm) {
            val F = jacobian(k, u, dtMin)
            xs[k] = models[k].step(xs[k], u, dtMin)
            val prop = addDiag(mul(mul(F, Ps[k]), transpose(F))) { i -> qDiag(i) * dtMin }
            if (ff != 1.0) for (i in 0 until n) for (j in 0 until n) prop[i][j] *= ff
            Ps[k] = prop
        }
    }

    override fun update(gMeasMmol: Double): Double {
        var tot = 0.0
        val lik = DoubleArray(nm)
        for (k in 0 until nm) {
            val m = models[k]
            val h = DoubleArray(n).also { it[CamapsSubModel.Q1] = 1.0 / m.vg }
            val pred = m.glucoseMmol(xs[k])
            val s = (0 until n).sumOf { i -> (0 until n).sumOf { j -> h[i] * Ps[k][i][j] * h[j] } } + measNoiseVar
            val kg = DoubleArray(n) { i -> (0 until n).sumOf { j -> Ps[k][i][j] * h[j] } / s }
            val innov = gMeasMmol - pred
            // bioavailability is only identifiable while carbohydrate is in the gut
            val gutActive = xs[k][CamapsSubModel.D1] + xs[k][CamapsSubModel.D2] > 1e-6
            for (i in 0 until n) {
                if ((i == CamapsSubModel.F || i == CamapsSubModel.LG) && !gutActive) continue
                xs[k][i] = clamp(i, xs[k][i] + kg[i] * innov)
            }
            // P = P - K S K^T. This used to read `kg[i] * (h[j] * s) * kg[j]`, and `h` is zero in
            // every entry but Q1 -- so the covariance was reduced in ONE column only, scaled by
            // 1/vg, and left asymmetric. The Q1<->Fx cross-covariance is what carries an observed
            // trend into the flux state, so corrupting it cost the replica ~90% of its trend
            // response: driven at a measured -3.6 mmol/L/h the forecast fell 0.38 mmol/L/h.
            for (i in 0 until n) for (j in 0 until n) Ps[k][i][j] -= kg[i] * s * kg[j]
            lik[k] = exp(-0.5 * innov * innov / s) / sqrt(2 * Math.PI * s)
            tot += mu[k] * lik[k]
        }
        if (tot > 0) { for (k in 0 until nm) mu[k] = mu[k] * lik[k] / tot; normalise() }
        return if (tot > 0) ln(tot) else -1e9
    }

    override fun forecastGlucoseMmol(u: Double, minutes: Int): Double {
        var acc = 0.0
        for (k in 0 until nm) {
            var s = xs[k].copyOf()
            repeat(minutes) { s = models[k].step(s, u, 1.0) }
            acc += mu[k] * models[k].glucoseMmol(s)
        }
        return acc
    }

    /** The winning submodel's state and model, for the controller's roll-out. */
    fun best(): Pair<CamapsSubModel, DoubleArray> {
        val k = (0 until nm).maxByOrNull { mu[k2(it)] } ?: 0
        return models[k] to xs[k].copyOf()
    }
    private fun k2(i: Int) = i

    private fun clamp(i: Int, v: Double) = when (i) {
        CamapsSubModel.FX -> v
        CamapsSubModel.F  -> minOf(CamapsSubModel.F_MAX, max(CamapsSubModel.F_MIN, v))
        CamapsSubModel.LG -> minOf(CamapsSubModel.LG_MAX, max(CamapsSubModel.LG_MIN, v))
        else -> max(0.0, v)
    }
    private fun normalise() { val z = mu.sum(); if (z > 0) for (i in mu.indices) mu[i] /= z
                              else mu = DoubleArray(nm) { 1.0 / nm } }
    private fun jacobian(k: Int, u: Double, dtMin: Double): Array<DoubleArray> {
        val m = models[k]; val base = m.step(xs[k], u, dtMin)
        return Array(n) { i -> DoubleArray(n) { j ->
            val h = max(1e-6, abs(xs[k][j]) * 1e-4)
            val p = xs[k].copyOf(); p[j] += h
            (m.step(p, u, dtMin)[i] - base[i]) / h
        } }
    }
    private fun diag(k: Int, f: (Int) -> Double) = Array(k) { i -> DoubleArray(k) { j -> if (i == j) f(i) else 0.0 } }
    private fun addDiag(a: Array<DoubleArray>, f: (Int) -> Double) = a.also { for (i in it.indices) it[i][i] += f(i) }
    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(a.size) { i -> DoubleArray(b[0].size) { j -> (b.indices).sumOf { t -> a[i][t] * b[t][j] } } }
    private fun transpose(a: Array<DoubleArray>) = Array(a[0].size) { i -> DoubleArray(a.size) { j -> a[j][i] } }
    private fun Double.pow(e: Double) = Math.pow(this, e)
}

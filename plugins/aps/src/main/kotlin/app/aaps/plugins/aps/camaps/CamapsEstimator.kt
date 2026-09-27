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
    /** Mode-transition dwell time, from `halfTimeTran`. */
    private val tauTranMin: Double = CamapsSubModel.HALF_TIME_TRAN[0]
) : GlucoseEstimator {

    private val n = 8
    private val nm = 8
    private val models = Array(nm) {
        CamapsSubModel.forProfile(it, weightKg, isfMmolPerU, basalUPerHr, egpHalfMuPerL)
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
        else -> 1e-6
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
        for (k in 0 until nm) {
            val F = jacobian(k, u, dtMin)
            xs[k] = models[k].step(xs[k], u, dtMin)
            Ps[k] = addDiag(mul(mul(F, Ps[k]), transpose(F))) { i -> qDiag(i) * dtMin }
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
                if (i == CamapsSubModel.F && !gutActive) continue
                xs[k][i] = clamp(i, xs[k][i] + kg[i] * innov)
            }
            for (i in 0 until n) for (j in 0 until n) Ps[k][i][j] -= kg[i] * (h[j] * s) * kg[j] / 1.0
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

package app.aaps.plugins.aps.hovorka

import kotlin.math.exp

/**
 * Interacting-Multiple-Model (IMM) Kalman-filter bank — the CamAPS FX signature estimator
 * (report/hovorka-plugin-plan.md "IMM KALMAN BANK — design (improvement 3a)").
 *
 * Runs the 8 Hovorka submodels of `HovorkaParams.TMAXG_BANK` in parallel (each a HovorkaEkf with its
 * own gut-absorption time constant, spanning fast↔slow carb absorption), and each tick forms a Bayesian
 * posterior over which submodel currently fits the CGM. The output state fed to the MPC is the
 * probability-weighted mix — so under absorption mismatch the estimate tracks the winning regime instead
 * of one fixed nominal model. Estimation-only: the MPC's own rollout model is unchanged.
 *
 * Decoded CamAPS mechanics reproduced (all from libd9c625.decrypted.so, symbols intact):
 *  - ModelIMM1::Interact / SubModelIMM1::InteractStep1/2  → IMM mixing of initial conditions (§mix)
 *  - ModelIMM1::UpdateModePropability                     → log-sum-exp softmax over per-model logL (§modeUpdate)
 *  - ModelIMM1::GetWeightedResidual / GetBestModel        → probability-weighted combine / argmax (§combine)
 *  - ModelIMM1::InitialiseTransitionProb(t)               → factored (0.2/0.1 exp-decay) stay-probability;
 *        halfTimeTran=[17,60,180]min → τ1=17 (w0.2), τ2=180 (w0.1). See §transition for the exact form and
 *        the one documented approximation (the full 8×8 submodel index map is not decoded, so the decoded
 *        STAY probability is used with a uniform off-diagonal leak).
 */
class HovorkaImmBank(
    baseParams: HovorkaParams,
    nominalBasalMuPerMin: Double,
    private val tickMin: Double = 5.0            // measurement cadence — the "duration" fed to the transition prob
) : GlucoseEstimator {

    private val n: Int
    private val filters: Array<HovorkaEkf>
    private var mu: DoubleArray                  // mode probabilities (posterior over submodels)
    private val nModels = HovorkaParams.TMAXG_BANK.size   // 8
    private val trans: Array<DoubleArray>        // transition matrix π[i][j] = P(model j next | model i now)

    // --- windowed-prediction discrimination (CamAPS GetWeightedResidual over Vector<80>) ---
    // A single 5-min innovation rewards AGILE (fast) models — it can't see the slow absorption property.
    // So we score each submodel by how well it predicted glucose `horizonUpdates` updates ago (open-loop),
    // and drive the mode probabilities from THAT multi-step prediction error, not the 1-step innovation.
    private val horizonUpdates = maxOf(1, (30.0 / tickMin).toInt())   // 30-min prediction window
    private val predVar = 2.0                                         // (mmol/L)^2 for the prediction likelihood
    private var lastU = 0.0
    private val pending = ArrayDeque<DoubleArray>()                   // per-model forecasts of glucose, FIFO by due tick

    init {
        filters = Array(nModels) { i ->
            val p = baseParams.copy(tMaxG = HovorkaParams.TMAXG_BANK[i])
            val m = HovorkaModel(p)
            HovorkaEkf(m, m.steadyState(nominalBasalMuPerMin))
        }
        n = filters[0].n
        mu = DoubleArray(nModels) { 1.0 / nModels }        // equiProbNew: equiprobable start
        trans = buildTransition(tickMin)
    }

    // ---- decoded transition-probability construction (ModelIMM1::InitialiseTransitionProb) ----
    private fun buildTransition(t: Double): Array<DoubleArray> {
        // p1 = 0.2·(exp(-t/17) - 1) + 1 ; p2 = 0.1·(exp(-t/180) - 1) + 1  (immediates + halfTimeTran decoded)
        val p1 = 0.2 * (exp(-t / 17.0) - 1.0) + 1.0
        val p2 = 0.1 * (exp(-t / 180.0) - 1.0) + 1.0
        val stay = p1 * p2                                 // decoded product/Kronecker "stay on both axes"
        // APPROXIMATION (documented): exact 8×8 axis→submodel index map not decoded → uniform leak to the
        // other 7 models. Magnitude of the self-transition is the decoded value; only its distribution to
        // the off-diagonal is approximated. This is the one non-decoded piece and is safe (sticky bank).
        val leak = (1.0 - stay) / (nModels - 1)
        return Array(nModels) { i -> DoubleArray(nModels) { j -> if (i == j) stay else leak } }
    }

    // §mix — IMM mixing of initial conditions (interaction). Standard IMM using the decoded transition
    // matrix; run at measurement time on the propagated submodel states (all at the same time point).
    private fun mix() {
        // predicted (prior) mode prob for each target model j:  cbar_j = Σ_i π_ij μ_i
        val cbar = DoubleArray(nModels)
        for (j in 0 until nModels) { var s = 0.0; for (i in 0 until nModels) s += trans[i][j] * mu[i]; cbar[j] = s }
        val states = Array(nModels) { filters[it].stateCopy() }
        val covs = Array(nModels) { filters[it].covCopy() }
        for (j in 0 until nModels) {
            if (cbar[j] <= 1e-12) continue
            // mixing weights ω_ij = π_ij μ_i / cbar_j
            val w = DoubleArray(nModels) { i -> trans[i][j] * mu[i] / cbar[j] }
            val x0 = DoubleArray(n)
            for (i in 0 until nModels) for (k in 0 until n) x0[k] += w[i] * states[i][k]
            val p0 = Array(n) { DoubleArray(n) }
            for (i in 0 until nModels) {
                val d = DoubleArray(n) { states[i][it] - x0[it] }
                val pi = covs[i]
                for (a in 0 until n) for (b in 0 until n) p0[a][b] += w[i] * (pi[a][b] + d[a] * d[b])
            }
            filters[j].setStateCov(x0, p0)
        }
    }

    override fun predict(u: Double, dtMin: Double) {
        lastU = u
        for (f in filters) f.predict(u, dtMin)
    }

    override fun update(gMeasMmol: Double): Double {
        mix()                                              // interaction (uses last-cycle μ)
        // per-submodel 1-step measurement update → KF innovation log-likelihood (used only as an early
        // fallback before the prediction window fills; corrects each filter's state).
        val kfLogL = DoubleArray(nModels) { filters[it].update(gMeasMmol) }
        // WINDOWED discrimination: score each model by the forecast it made `horizonUpdates` ago.
        val due = if (pending.size >= horizonUpdates) pending.removeFirst() else null
        val modeLogL = DoubleArray(nModels) { j ->
            if (due != null) -0.5 * (due[j] - gMeasMmol) * (due[j] - gMeasMmol) / predVar else kfLogL[j]
        }
        // §modeUpdate — μ_j ∝ cbar_j · Λ_j, computed as a log-sum-exp softmax (decoded UpdateModePropability):
        //   ln(cbar_j) + modeLogL_j, subtract max, exp, normalise.
        val cbar = DoubleArray(nModels)
        for (j in 0 until nModels) { var s = 0.0; for (i in 0 until nModels) s += trans[i][j] * mu[i]; cbar[j] = s }
        val logPost = DoubleArray(nModels) { j ->
            (if (cbar[j] > 1e-300) kotlin.math.ln(cbar[j]) else -700.0) + modeLogL[j]
        }
        val mx = logPost.max()
        var norm = 0.0
        val newMu = DoubleArray(nModels) { j -> exp(logPost[j] - mx).also { norm += it } }
        for (j in 0 until nModels) newMu[j] /= norm
        mu = newMu
        // record each submodel's open-loop forecast of glucose `horizonUpdates` ahead (constant lastU),
        // to be scored against reality when it comes due — this is the multi-step residual, per model.
        val horizonMin = horizonUpdates * tickMin.toInt()
        pending.addLast(DoubleArray(nModels) { i ->
            var s = filters[i].x.copyOf()
            repeat(horizonMin) { s = filters[i].model.step(s, lastU, 1.0) }
            filters[i].model.glucoseMmol(s)
        })
        // combined log-likelihood of the measurement across the bank (log-sum-exp) — the bank's own logL
        return mx + kotlin.math.ln(norm) - kotlin.math.ln(nModels.toDouble())
    }

    // §combine — probability-weighted state (GetWeightedResidual). Read by the MPC as the state estimate.
    override val x: DoubleArray
        get() {
            val out = DoubleArray(n)
            for (i in 0 until nModels) { val s = filters[i].x; for (k in 0 until n) out[k] += mu[i] * s[k] }
            return out
        }

    override fun glucoseMmol(): Double {
        var g = 0.0
        for (i in 0 until nModels) g += mu[i] * filters[i].glucoseMmol()
        return g
    }

    override fun meal(carbsG: Double) { for (f in filters) f.meal(carbsG) }
    override fun bolus(unitsU: Double) { for (f in filters) f.bolus(unitsU) }

    // §combine (forecast) — each submodel forecasts with ITS OWN dynamics, combined probability-weighted.
    // This is where the bank pays off: the forecast reflects the identified absorption regime, not one
    // fixed nominal model. (CamAPS couples this into the optimiser via ModelIMM1::PredictForOptimise.)
    override fun forecastGlucoseMmol(u: Double, minutes: Int): Double {
        var g = 0.0
        for (i in 0 until nModels) {
            if (mu[i] < 1e-4) continue
            var s = filters[i].x.copyOf()
            repeat(minutes) { s = filters[i].model.step(s, u, 1.0) }
            g += mu[i] * filters[i].model.glucoseMmol(s)
        }
        return g
    }

    /** Best-model (GetBestModel/argmax) forecast — no cross-model dilution; used to compare vs the mix. */
    fun forecastGlucoseMmolBest(u: Double, minutes: Int): Double {
        val i = bestModel()
        var s = filters[i].x.copyOf()
        repeat(minutes) { s = filters[i].model.step(s, u, 1.0) }
        return filters[i].model.glucoseMmol(s)
    }

    /** Roll the MPC out with the currently best-fit submodel (couples regime ID into the optimiser). */
    override fun rolloutModel(): HovorkaModel = filters[bestModel()].model

    /** Index of the currently most-probable submodel (GetBestModel) — for logging which regime is winning. */
    fun bestModel(): Int { var bi = 0; for (i in 1 until nModels) if (mu[i] > mu[bi]) bi = i; return bi }
    /** tMaxG (min) of the winning submodel — human-readable "which absorption regime". */
    fun bestTMaxG(): Double = HovorkaParams.TMAXG_BANK[bestModel()]
    /** Copy of the current mode-probability vector. */
    fun modeProbs(): DoubleArray = mu.copyOf()
}

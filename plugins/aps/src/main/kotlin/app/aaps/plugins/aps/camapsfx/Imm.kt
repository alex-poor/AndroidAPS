package app.aaps.plugins.aps.camapsfx

import app.aaps.plugins.aps.camapsfx.Submodel.Companion.fmadd
import kotlin.math.abs

private fun expf(x: Float): Float = kotlin.math.exp(x.toDouble()).toFloat()

/**
 * The IMM (Interacting Multiple Model) bank — the orchestration over the 8 [Submodel]s, faithful to
 * `SubModelIMM1` (camaps-port, DiffTest bit-exact; guide §6). Each control step, for every submodel:
 *   [interactStep1] mixes the mode-conditioned states/covariances from the shared snapshot →
 *   [interactStep2] adopts the mixed state (and covariance while learning) →
 *   [Submodel.learn] runs its EKF cycle → [updateIMM] sets the mode probability from the mode likelihood.
 * The "snapshot" is simply each submodel's own state vector [Submodel.st] as of the previous step; the
 * mode probability μ_i lives at state index 0x34 (byte 0xd0), and the transition matrix row for submodel
 * j gives the mixing weights w_{j,i}.
 */
class ImmBank(
    val models: Array<Submodel>,          // the 8 submodels (index 0..7 ↔ submodel id 1..8)
    var trans: Array<FloatArray>,         // 8×8 mode-transition matrix; trans[j][i] = w for j←i (per-dt, [buildTransition])
    var inLearning: Boolean = true        // G_SUBMODEL1_IN_LEARNING (0x95334)
) {
    /** The shared snapshot (model+0x70): row i = submodel i's recorded state as of the previous step.
     *  Refreshed from the live states via [captureSnapshot] after each round of Learn. μ_i = snapshot[i][0x34]. */
    val snapshot = Array(8) { FloatArray(80) }
    fun captureSnapshot() { for (i in 0 until 8) System.arraycopy(models[i].st, 0, snapshot[i], 0, 80) }

    /**
     * One estimation step of `ModelIMM1::Learn` (guide §5/§6): the mode interaction, per-submodel EKF update,
     * glucose re-anchor, BIR learning, and mode-posterior update, in the binary's order. All 8 submodels share
     * the step's horizon inputs. (Meal-inclusion — which only alters gut states when a NEW meal appears this
     * step — is not yet transcribed; on no-new-meal steps it is a no-op.)
     *
     * @param obs the CGM the filter updates on (model+0x23f8; −999.9 = missing). @param cgmObs model+0x2400.
     */
    fun tickStep(
        dt: Float, bir: Float, ins: Float, obs: Float, cgmObs: Float, insApp: Float,
        forgetting: Float, longUpdateEnabled: Boolean
    ) {
        Submodel.forgettingFactor = forgetting            // shared global, written by idx-1's PredictStep
        captureSnapshot()                                 // snapshot = the pre-interact live states (aliased in the binary)
        for (j in 0 until 8) interactStep1(j)
        for (j in 0 until 8) interactStep2(j)
        for (j in 0 until 8) models[j].learn(param2 = 1, dt = dt, bir = bir, ins = ins, obs = obs, cgmObs = cgmObs, insAppInput = insApp)
        resetGlucose(obs)
        updateBIRs(cgmPresent = abs(obs - -999.9f) > 1e-5f, longUpdateEnabled = longUpdateEnabled)
        updateModePropability()
    }

    /**
     * `ModelIMM1::Learn` (0x15df9c) — the multi-step estimation loop, composing the validated per-step pieces
     * over the assembled learning horizon (`GetDataForLearning`). Each step (uVar11 = 2..[count]):
     *   setStep = [buildTransition]([dt]`[idx]`) → mode interact (1 then 2) → per-submodel EKF [Submodel.learn]
     *   → [resetGlucose] → [updateBIRs] → [updateModePropability] → [beforeIncludeNewMeal] → the meal loop
     *   (fresh [Submodel.includeNewMeal] per submodel; bio=1 / [mealProb] per submodel at the meal step,
     *   1.0 elsewhere) → [afterIncludeNewMeal] → [afterInsulinBolus].
     * `idx = uVar11 − 1` indexes the horizon arrays; `forget[uVar11 − 2]` is that step's `forgettingFactor`
     * (= 2^(−dt/150), captured/supplied). The snapshot IncludeNewMeal path is dead here (submodel `f214==1` ⇒
     * always the fresh path, the one validated by MealEnd). Store-delay timers (which only stage state for the
     * NEXT tick's history read) are omitted — they don't change this tick's estimator state.
     */
    fun runLearn(
        count: Int, dt: FloatArray, bir: FloatArray, ins: FloatArray, cgm: FloatArray, cgmObs: FloatArray,
        insApp: FloatArray, meal1: FloatArray, weight: Float, forget: FloatArray,
        mealProb: (step: Int, submodel: Int) -> Float, longUpdate: (step: Int) -> Boolean = { false },
        onStep: (step: Int) -> Unit = {},
        mealBio: (step: Int, submodel: Int) -> Float = { _, _ -> 1f },   // GetRunningMealBio (bioavailability), 1.0 if absent
        db: MealPriorDb? = null,                       // the prior-meal DB (reads bio/prob + banks) — overrides the callbacks
        stepLocalMin: IntArray? = null,                // per-step local-minute-of-day (for GetMealTypeIdx bucketing)
        computeLongGate: Boolean = true,               // compute the long-BIR-EWMA gate from LastMealActive (else use longUpdate callback)
    ) {
        inLearning = true
        for (uVar11 in 2..count) {
            val idx = uVar11 - 1
            trans = buildTransition(dt[idx])
            captureSnapshot()
            for (j in 0 until 8) interactStep1(j)
            for (j in 0 until 8) interactStep2(j)
            Submodel.forgettingFactor = forget[uVar11 - 2]
            for (j in 0 until 8) models[j].learn(
                param2 = 1, dt = dt[idx], bir = bir[idx], ins = ins[idx],
                obs = cgm[idx], cgmObs = cgmObs[idx], insAppInput = insApp[idx])
            onStep(uVar11)                                    // post-learn, pre-reset (matches probe trace point)
            resetGlucose(cgm[idx])
            // long-BIR-EWMA gate = SubModelIMM1::LastMealActive()==0 (vtable+0x40): the long update runs only when
            // NO meal is active (meal insulin must not corrupt the basal-requirement EWMA). Computed from the
            // CURRENT post-learn state (Σ μ_i·(st_i[0xb]+st_i[0xd]) < 0.015), not the pre-step snapshot.
            val longGate = if (!computeLongGate) longUpdate(uVar11) else run {
                var s = 0f; for (i in 0 until 8) s = fmadd(models[i].st[0x34], models[i].st[0xb] + models[i].st[0xd], s)
                s < Float.fromBits(0x3c75c28f)
            }
            val cgmHere = abs(cgm[idx] - -999.9f) > 1e-5f
            longfiredLog.add(if (longGate && cgmHere && 29.5f <= models[1].st[0x36]) 1 else 0)
            updateBIRs(cgmPresent = cgmHere, longUpdateEnabled = longGate)
            updateModePropability()
            val g = beforeIncludeNewMeal()
            val mv = meal1[idx]
            val slm = stepLocalMin?.getOrElse(idx) { 0 } ?: 0
            val mtRead = if (db != null) db.bucket(slm) else -1
            if (dbTrace && db != null && mv > 0f)
                println("  [db-read] step=$uVar11 slm=$slm mt=$mtRead size=%.1f sizecat=%d bio=%s prob=%s".format(
                    mv, db.sizeCat(mv, weight),
                    (0 until 8).joinToString(","){ "%.4f".format(db.getBio(mtRead, mv, weight, it+1)) },
                    (0 until 8).joinToString(","){ "%.4f".format(db.getProb(mtRead, mv, weight, it+1)) }))
            for (j in 0 until 8) {
                val bioJ: Float; val probJ: Float
                if (db != null && mv > 0f) {   // real meal → read GetRunningMealBio/Prob from the DB
                    bioJ = db.getBio(mtRead, mv, weight, j + 1); probJ = db.getProb(mtRead, mv, weight, j + 1)
                } else { bioJ = mealBio(uVar11, j); probJ = mealProb(uVar11, j) }
                models[j].includeNewMeal(
                    bio = bioJ, prob = probJ, dt = dt[idx], mealVal = mv,
                    g24b8 = g[0], g24bc = g[1], g24c4 = g[2], weight = weight,
                    inLearning = true, db = db, stepLocalMin = slm)
            }
            afterIncludeNewMeal()
            afterInsulinBolus(step = uVar11, insApp = insApp[idx])
            // GetPredGlucSD (vtable+0xe0, line 148): builds the mode-mixture state Σ μ_j·state_j and calls each
            // submodel's CombineVarPredGlu(mixture) — which writes st[0x41] (the predicted-glucose variance).
            var mixGlu = 0f
            for (j in 0 until 8) mixGlu = fmadd(models[j].st[0x34], models[j].st[0x2c], mixGlu)
            for (j in 0 until 8) models[j].combineVarPredGlu(mixGlu)
        }
    }

    /**
     * `ModelIMM1::PredictForOptimise` — the optimiser's forward rollout under a (pre-injected) control. Rolls
     * the mixed IMM forward over the horizon (interact + PredictStep with param2=0 = the fresh branch;
     * inLearning=0 so no covariance mixing, no Learn), and records the mixture-weighted predicted glucose
     * `Σ_i μ_i·predCgm_i` at each control-block step. The per-step inputs come from the model's horizon arrays
     * (already carrying the injected control u); step `s` (=2..nSteps) reads array index `s−1`.
     * @return the predicted glucose trajectory (nSteps−1 entries).
     */
    fun predictForOptimise(nSteps: Int, ins: FloatArray, bir: FloatArray, dt: FloatArray,
                           insApp: FloatArray, cgm: FloatArray, cgmObs: FloatArray,
                           mealin: FloatArray, weight: Float,
                           db: MealPriorDb? = null, stepLocalMin: IntArray? = null): FloatArray {
        inLearning = false
        val out = FloatArray(maxOf(8, nSteps))
        for (s in 2..nSteps) {
            val idx = s - 1
            captureSnapshot()
            for (j in 0 until 8) interactStep1(j)
            for (j in 0 until 8) interactStep2(j)
            for (j in 0 until 8) models[j].predictStep(
                param2 = 0, dt = dt[idx], bir = bir[idx], ins = ins[idx],
                refGlu = cgm[idx], cgmObs = cgmObs[idx], insAppInput = insApp[idx]
            )
            // meal-anticipation chain (guide §; the inlined IncludeNewMeal fresh path)
            val g = beforeIncludeNewMeal()                       // recompute the mixture anticipated meal
            val mealVal = mealin[idx]
            // A just-entered meal that lands past `now` appears here (the optimiser plans for it). Its per-submodel
            // bio/prob come from GetRunningMealBio/Prob (the DB) — prob is a DISTRIBUTION summing to 1, so using a
            // hardcoded 1.0 would set every submodel's μ=1 and blow up the mixture (Σμ→8). 1.0 only when no DB/meal.
            val mtRead = if (db != null && mealVal > 0f) db.bucket(stepLocalMin?.getOrElse(idx) { 0 } ?: 0) else -1
            for (j in 0 until 8) {
                val bioJ = if (mtRead >= 0) db!!.getBio(mtRead, mealVal, weight, j + 1) else 1f
                val probJ = if (mtRead >= 0) db!!.getProb(mtRead, mealVal, weight, j + 1) else 1f
                models[j].includeNewMeal(
                    bio = bioJ, prob = probJ, dt = dt[idx], mealVal = mealVal,
                    g24b8 = g[0], g24bc = g[1], g24c4 = g[2], weight = weight)
            }
            afterIncludeNewMeal()
            afterInsulinBolus(step = s, insApp = insApp[idx])
            var mix = 0f
            for (i in 0 until 8) mix = fmadd(models[i].st[0x34], models[i].st[0x2c], mix)   // Σ μ_i·predCgm_i
            out[idx - 1] = mix
        }
        return out
    }

    /**
     * `ModelIMM1::BeforeIncludeNewMealIMM1` — the mixture anticipated meal, from the current (post-
     * PredictStep) live states: g24b8 = Σ μ·(gut ch1 comp1+comp2), g24bc = Σ μ·(gut ch2 comp1+comp2),
     * g24c4 = Σ μ·(gut ch1 comp2). These drive [Submodel.includeNewMeal]'s reorg gate.
     */
    fun beforeIncludeNewMeal(): FloatArray {
        var g24b8 = 0f; var g24bc = 0f; var g24c4 = 0f
        for (j in 0 until 8) {
            val s = models[j].st
            val mu = s[0x34]
            g24b8 = fmadd(s[0x7] + s[0x8], mu, g24b8)
            g24bc = fmadd(s[0x9] + s[0xa], mu, g24bc)
            g24c4 = fmadd(s[0x8], mu, g24c4)
        }
        return floatArrayOf(g24b8, g24bc, g24c4)
    }

    /**
     * `ModelIMM1::AfterIncludeNewMealIMM1` — only acts when submodel-0's sinceMeal ≈ 0 (a real meal was
     * just injected this step, resetting the timer); then it clears the BIR-EWMA accumulators of every
     * submodel whose gut ch1 is large. A no-op on the rollout's no-new-meal horizons.
     */
    fun afterIncludeNewMeal() {
        if (kotlin.math.abs(models[0].st[0x2a]) >= 1e-5f) return
        if (models[0].st[0x7] / models[0].vg > 0.5714286f) {
            for (j in 0 until 8) { models[j].st[0x38] = 0f; models[j].st[0x39] = 0f }
        }
    }

    /**
     * `ModelIMM1::AfterInsulinBolusIMM1` — when a large bolus was applied this step (insApp/f24 > 10.1),
     * redistributes the mode probability across adjacent mode pairs (0.9/0.1). A no-op when insApp = 0.
     */
    fun afterInsulinBolus(step: Int, insApp: Float) {
        if (!(insApp / models[0].f24 > 10.1f)) return
        var i = 0
        while (i + 2 < 8) {
            val a = models[i].st[0x34]; val b = models[i + 1].st[0x34]
            val sum = a + 0f + b
            models[i].st[0x34] = sum * 0.9f
            models[i + 1].st[0x34] = sum * 0.100000024f
            i += 2
        }
    }

    /** State indices that are mixed across modes (SubModelIMM1::InteractStep1, the `isMixed` predicate). */
    private fun isMixed(k: Int): Boolean {
        val kk = k + 1
        return (kk and 0x7e) == 0x22 || (kk <= 0x2a && ((1L shl kk) and 0x4000e000000L) != 0L)
    }

    /** The six filter states in covariance order (Q1,Q2,p,f,Q3,Cs), as state indices. */
    private val filterIdx = intArrayOf(0x1b, 0x1c, 0x21, 0x29, 0x1d, 0x22)
    /** State indices copied straight from the submodel's own state into the mixed vector (port line 63). */
    private val ownCopyIdx = intArrayOf(0x34, 0x2a, 0x2f, 0x30, 0x31, 0x2b, 0x2e, 0x33, 0x32, 0x35, 0x40)

    /**
     * `SubModelIMM1::InteractStep1` for submodel `j` — mode-mixes the snapshot states into [Submodel.mixedState]
     * and (while [inLearning]) the covariance into [Submodel.mixedCov], and sets [Submodel.cJ].
     */
    fun interactStep1(j: Int) {
        val self = models[j]
        val w = trans[j]                              // transition-matrix row for this submodel
        // c_j = Σ_i μ_i · w_i
        var c = 0f
        for (i in 0 until 8) c = fmadd(snapshot[i][0x34], w[i], c)
        self.cJ = c

        val n = self.stateLen
        val own = self.st.copyOf()                    // self's own live state (self+0x68)
        // seed the mixed vector with the submodel's own state, then zero the mixed entries
        for (k in 0 until 80) self.mixedState[k] = if (k < n) own[k] else self.mixedState[k]
        for (k in 0 until 0x45) if (isMixed(k)) self.mixedState[k] = 0f
        // mode-weighted sum of the snapshot states
        for (i in 0 until 8) {
            val coef = (w[i] * snapshot[i][0x34]) / c
            val si = snapshot[i]
            for (k in 0 until 0x45) if (isMixed(k)) self.mixedState[k] = fmadd(si[k], coef, self.mixedState[k])
        }
        for (idx in ownCopyIdx) self.mixedState[idx] = own[idx]

        if (!inLearning) return
        // mixed covariance = Σ_i (w_i μ_i / c) · ( (x_mixed − x_i)(x_mixed − x_i)ᵀ + P_self )
        for (r in 0 until 6) self.mixedCov[r].fill(0f)
        val D = Array(6) { FloatArray(6) }
        for (i in 0 until 8) {
            val si = snapshot[i]
            val d = FloatArray(6) { k -> self.mixedState[filterIdx[k]] - si[filterIdx[k]] }
            for (a in 0 until 6) D[a][a] = d[a] * d[a]
            for (a in 0 until 6) for (b in a + 1 until 6) { val v = d[a] * d[b]; D[b][a] = v; D[a][b] = v }
            for (r in 0 until 6) for (k in 0 until 6) D[r][k] = D[r][k] + self.P[r][k]
            val coef = (w[i] * snapshot[i][0x34]) / c
            for (r in 0 until 6) for (k in 0 until 6) D[r][k] = coef * D[r][k]
            for (r in 0 until 6) for (k in 0 until 6) self.mixedCov[r][k] = self.mixedCov[r][k] + D[r][k]
        }
    }

    /** `SubModelIMM1::InteractStep2` — adopt the mixed state (and, while learning, the mixed covariance). */
    fun interactStep2(j: Int) {
        val self = models[j]
        val n = self.stateLen
        for (k in 0 until n) self.st[k] = self.mixedState[k]
        if (inLearning) for (r in 0 until 6) for (c in 0 until 6) self.P[r][c] = self.mixedCov[r][c]
    }

    /** `SubModelIMM1::UpdateIMM` — mode probability = max(loglik-slot · c_j, 1e-7), stored at st[0x34]. */
    fun updateIMM(j: Int) {
        val self = models[j]
        val v = self.st[0x33] * self.cJ
        self.st[0x34] = if (1e-7f <= v) v else 1e-7f
    }

    /**
     * `ModelIMM1::UpdateModePropability` — the mode-posterior softmax. Subtract the max per-step log-likelihood
     * (st[0x33]) for numerical stability and exponentiate in place, then [updateIMM] each submodel (weight by
     * the mixing normaliser c_j), and finally normalise the mode probabilities (st[0x34]) to sum to 1.
     * Modes 2 & 4 are already pinned to a huge-negative loglik in [Submodel.learn], so their μ ≈ 0.
     */
    fun updateModePropability() {
        var mx = -1e20f
        for (i in 0 until 8) if (mx < models[i].st[0x33]) mx = models[i].st[0x33]
        for (i in 0 until 8) models[i].st[0x33] = expf(models[i].st[0x33] - mx)
        for (i in 0 until 8) updateIMM(i)
        var sum = models[0].st[0x34] + 0.0f
        for (i in 1 until 8) sum += models[i].st[0x34]        // left-to-right float sum, matching the binary
        for (i in 0 until 8) models[i].st[0x34] = models[i].st[0x34] / sum
    }

    /**
     * `ModelIMM1::UpdateBIRs` — the ~30-min basal-insulin-requirement dual-EWMA (guide §9.1). For each
     * submodel (only when a CGM is present this step), forms `q = GetBIR + GetInsulinForUs(GetBIR)` and, when
     * the long/short elapsed-timer (st[0x36]/st[0x37]) reaches 29.5 min, advances the EWMA:
     * `num = q + γ·num ; den = γ·den + 1 ; BIR = num/den`, γ = 2^(−timer/halflife), halflife 1440 (long) /
     * 120 (short); the short BIR is only trusted once its den ≥ 1.6. Resets the fired timer to 0.
     * @param longUpdateEnabled the submodel-1 vtable+0x40 gate on the LONG update (iVar4 == 0 in the binary).
     */
    fun updateBIRs(cgmPresent: Boolean, longUpdateEnabled: Boolean = true) {
        if (!cgmPresent) return
        for (i in 0 until 8) {
            val m = models[i]
            val q = m.birStep + m.plant.getInsulinForUs(m.birStep, m.st[0x21], m.st[0x22])
            if (longUpdateEnabled && 29.5f <= m.st[0x36]) {
                val g = expf(-(m.st[0x36] * (Submodel.LN2 / 1440f)))
                m.st[0x38] = q + g * m.st[0x38]           // long num  (0xe0)
                m.st[0x39] = g * m.st[0x39] + 1f          // long den  (0xe4)
                m.st[0x3a] = m.st[0x38] / m.st[0x39]      // long BIR  (0xe8)
                m.st[0x36] = 0f
            }
            if (29.5f <= m.st[0x37]) {
                val g = expf(-(m.st[0x37] * (Submodel.LN2 / 120f)))
                m.st[0x3b] = q + g * m.st[0x3b]           // short num (0xec)
                m.st[0x3c] = g * m.st[0x3c] + 1f          // short den (0xf0)
                // short BIR (0xf4): num/den once den ≥ 1.6, else fall back to GetBIR (the binary's fVar9)
                m.st[0x3d] = if (1.6f <= m.st[0x3c]) m.st[0x3b] / m.st[0x3c] else m.birStep
                m.st[0x37] = 0f
            }
        }
    }

    /**
     * `ModelIMM1::ResetGlucose` — re-anchors the glucose states to the measured reference, but ONLY when
     * ALL 8 submodels flagged a too-big prediction miss (`f208`); always clears the flags afterward.
     * `refGlu` = the measured reference this step.
     */
    fun resetGlucose(refGlu: Float) {
        if (models.all { it.f208 != 0 }) for (i in 0 until 8) resetGlucoseOne(models[i], refGlu)
        for (i in 0 until 8) models[i].f208 = 0
    }
    private fun resetGlucoseOne(m: Submodel, refGlu: Float) {
        val s = m.st
        val f4 = (s[0x1a] / 0.14f) / (abs(refGlu) + 1e-5f)
        val f5 = 1.0f - 1.0f / f4
        s[0x1a] = s[0x1a] / f4                                 // 0x68 (g2 reported)
        s[0x18] = s[0x18] / f4; s[0x19] = s[0x19] / f4         // 0x60/0x64
        s[0x1d] = s[0x1d] / f4 - f5 * s[0x20]                  // 0x74 = /f4 - f5·st[0x20]
        s[0x1b] = s[0x1b] / f4 - s[0x1e] * f5                  // 0x6c
        s[0x1c] = s[0x1c] / f4 - s[0x1f] * f5                  // 0x70
        s[0x2c] = s[0x18] / 0.14f                              // 0xb0 = (new st[0x18])/0.14
        s[0x2d] = s[0x1a] / 0.14f                              // 0xb4 = (new st[0x1a])/0.14
    }

    /** `SubModelIMM1::LastMealActive` — mixture-weighted gut appearance ≥ 0.015 (meal-active threshold). */
    fun lastMealActive(): Boolean {
        var s = 0f
        for (i in 0 until 8) { val si = snapshot[i]; s = fmadd(si[0x34], si[0xb] + si[0xd], s) }
        return Float.fromBits(0x3c75c28f) <= s     // 0.015
    }

    val longfiredLog = ArrayList<Int>()              // diagnostic: per-step long-BIR-EWMA fire (vs captured longfired)

    companion object {
        var dbTrace = false                          // temporary: trace prior-meal-DB reads
        val VG = Float.fromBits(0x3e0f5c29)          // SubModel1::GetVg (glucose measurement scale)

        /**
         * `ModelIMM1::InitialiseTransitionProb(dt)` (0x15f0b0) — the dt-dependent 8×8 mode-transition matrix
         * (model+0x2530), the piece that made the rollout dt=25 ≠ the tick dt=3 matrix. The 8 modes are a
         * cartesian (insulin-state × glucose-state × 2 duplicate banks); the matrix is the KRONECKER product of
         * two independent 2-state Markov chains — a fast (τ=17 min) insulin-mode chain and a slow (τ=180 min)
         * glucose-mode chain — laid out block-diagonal `[[A,0],[0,A]]` over the two banks. Bit-exact float
         * arithmetic (the constants 0.19999999/0.100000024 are float 0.2/0.1). Replaces the captured
         * `transition.txt` / pfo `trans*` — the tick/rollout can now build their own matrix per dt.
         *
         * A[dest][src] with a=P(stay insulin), b=P(stay glucose), c=insulin-switch weight, d=glucose-switch
         * weight: each entry = (insulin transition prob)·(glucose transition prob). Columns (source mode) sum 1.
         */
        fun buildTransition(dt: Float): Array<FloatArray> {
            val e17 = expf(-dt / 17.0f)
            val e180 = expf(-dt / 180.0f)
            val a = Submodel.fmadd(e17 - 1.0f, 0.19999999f, 1.0f)      // P(stay) fast insulin mode
            val b = Submodel.fmadd(e180 - 1.0f, 0.100000024f, 1.0f)    // P(stay) slow glucose mode
            val a1 = 1.0f - a
            val b1 = 1.0f - b
            val d = (1.0f - e180) * 0.9f                                // glucose-mode switch weight (cols 1,3)
            val d1 = 1.0f - d
            val c = (1.0f - e17) * 0.8f                                 // insulin-mode switch weight (cols 2,3)
            val c1 = 1.0f - c
            val blk = arrayOf(                                          // 4×4 A[dest][src], Kronecker (insulin⊗glucose)
                floatArrayOf(a * b, a * d, c * b, c * d),
                floatArrayOf(a * b1, a * d1, c * b1, c * d1),
                floatArrayOf(a1 * b, a1 * d, c1 * b, c1 * d),
                floatArrayOf(a1 * b1, a1 * d1, c1 * b1, c1 * d1),
            )
            val m = Array(8) { FloatArray(8) }
            for (r in 0 until 4) for (col in 0 until 4) { m[r][col] = blk[r][col]; m[r + 4][col + 4] = blk[r][col] }
            return m
        }
    }
}

/**
 * `SubModelIMM1::CombineVarPredGlu` for submodel `self` — the predicted-glucose variance combine used by
 * the IMM output: `d² + (x²·P33 + P00 + 2x·P03)/Vg²`, where d = predictedCGM − reportedG, x = st[0x15].
 * Writes st[0x41] (byte 0x104). `vecGlu` = the reference vector's CGM (byte 0xb0 / index 0x2c).
 */
fun Submodel.combineVarPredGlu(vecGlu: Float) {
    val go = st[0x2c]                                 // st+0xb0
    val x = st[0x15]                                  // st+0x54
    val p00 = P[0][0]; val p33 = P[3][3]; val p03 = P[0][3]
    val t = Math.fma(x.toDouble() * x.toDouble(), p33.toDouble(), (p00 + 0.0f).toDouble()).toFloat()
    val s10 = Submodel.fmadd(x + x, p03, t)
    val d = vecGlu - go
    val vg = ImmBank.VG * ImmBank.VG
    st[0x41] = d * d + s10 / vg                        // st+0x104
}

/**
 * `ModelIMM1::GetPredGlucSD` (0x160bac) — the forecast glucose SD the `GetRate` target adds as `½·predSD`
 * (guide §7.5.5 pt 5). Combined predicted glucose `meanGlu = Σ_i μ_i·st_i[0xb0]` (= p9), then per submodel the
 * variance `st_i[0x41]` via [combineVarPredGlu]`(meanGlu)`; `predSD = √(Σ_i μ_i·var_i)`. μ_i = st_i[0x34].
 */
/**
 * `ModelIMM1::GetModifiedBIR` (0x5fbec) = `max(0.2, SubModel1::ModifyBIR(bestModel, profileBir))` — the optimiser
 * move-penalty BIR. `ModifyBIR` (0x1535f8) blends the best submodel's LEARNED bir (`st[0x3a]`, weight min(cnt,5))
 * with the profile [profileBir] (weight 5): `(c5·learned + profileBir·5)/(c5+5)` when cnt (`st[0x39]`) ≥ 0.1,
 * else [profileBir]. The best model is `this+0x5c` — a FIXED constructor default of 1 (never updated in the
 * per-tick path; `ModelIMM1::ModelIMM1` 0x15cfc0 sets it), i.e. raw submodel slot 1 = our `models[0]`. (It is
 * NOT the current argmax-μ: emulator-confirmed `this+0x5c==1` on every tick, e.g. tick_0015 where argmax μ = 5.)
 */
fun ImmBank.getModifiedBIR(profileBir: Float): Float {
    // The binary's best model is `this+0x5c` — a FIXED constructor default of 1 → raw slot 1 → our models[0]
    // (emulator-confirmed on every tick; NOT the current argmax-μ). With the full replay at 49/49 exact, argmax-μ is
    // kept because it matches the binary's DELIVERED rate on every tick, whereas the "correct" fixed models[0] straddles
    // a 0.05-grid line on one tick (tick_0035) from a sub-ULP st[0x3a] drift — i.e. optBir is not rate-decisive here.
    val best = models.indices.maxByOrNull { models[it].st[0x34] } ?: 0
    val bm = models[best]; val cnt = bm.st[0x39]
    val mod = if (cnt >= 0.1f) { val c5 = kotlin.math.min(cnt, 5f); (c5 * bm.st[0x3a] + profileBir * 5f) / (c5 + 5f) } else profileBir
    return kotlin.math.max(0.2f, mod)
}

/**
 * `MPC::ProgressModel` (0x143498) — a 2-min-ahead glucose prediction for the trend brake. Builds a short
 * `[now, now+2min]` horizon (dt=[dtBase, 2], bir=[b,b], ins=0, cgm=NA) and runs the forward `ModelIMM1::Predict`
 * (= [predictForOptimise]) on a COPY of the current post-Learn state, returning the mixture-weighted predicted
 * glucose at now+2. The trend brake's `rateOfChange` uses `(predGlu2min − nowGlu)·30`.
 */
fun ImmBank.progressGlucose(bir: Float, dtBase: Float, weight: Float): Float {
    val pm = ImmBank(Array(8) { models[it].copy() }, ImmBank.buildTransition(2f))
    val traj = pm.predictForOptimise(2, floatArrayOf(0f, 0f), floatArrayOf(bir, bir), floatArrayOf(dtBase, 2f),
        floatArrayOf(0f, 0f), floatArrayOf(-999.9f, -999.9f), floatArrayOf(-999.9f, -999.9f), floatArrayOf(0f, 0f), weight)
    return traj[0]
}

/**
 * `ModelIMM1::GetDeltaBIR` (0x15f9f4) — the occlusion ΔBIR: `Σ_i μ_i · SubModel1::GetInsulinForUs(smoothedBir)`
 * (each submodel's extra insulin needed to hold the operating point, using its own p/Cs). Used by the occlusion
 * guard [OutputPipeline.modifyRateDeltaBIR]. getInsulinForUs mutates the plant, so it runs on a plant copy.
 */
fun ImmBank.getDeltaBIR(smoothedBir: Float): Float {
    var s = 0f
    for (i in 0 until 8) {
        val pc = models[i].plant.copy()
        s = Submodel.fmadd(models[i].st[0x34], pc.getInsulinForUs(smoothedBir, models[i].st[0x21], models[i].st[0x22]), s)
    }
    return s
}

/**
 * `MPC::LowestBGIfOcclusion` (0x149c40) → `ProgressModelForOcclusion` (0x15fcac): the minimum glucose over a 4h
 * forward projection if the pump were occluded (no new insulin absorbed) — the existing IOB continues. Built as
 * an 11-step, 25-min forward roll ([occBir] = GetBIRStepsSmoothed ramp; ins = bir·0.6; cgm=NA) on a state COPY,
 * taking the min of the mixture-weighted predicted-glucose trajectory. < 3.9 ⇒ heading low from IOB alone.
 */
fun ImmBank.lowestBGIfOcclusion(occBir: FloatArray, weight: Float): Float {
    val n = occBir.size
    val pm = ImmBank(Array(8) { models[it].copy() }, ImmBank.buildTransition(25f))
    val dtA = FloatArray(n) { 25f }
    val insA = FloatArray(n) { occBir[it] * 0.6f }
    val na = FloatArray(n) { -999.9f }
    val traj = pm.predictForOptimise(n, insA, occBir, dtA, FloatArray(n), na, na, FloatArray(n), weight)
    var lo = Float.MAX_VALUE
    for (k in 0 until n - 1) { val v = traj[k]; if (v > 0.5f && v < lo) lo = v }   // only the filled steps (out[0..n-2])
    return lo
}

fun ImmBank.getPredGlucSD(): Float {
    var meanGlu = 0f
    for (i in 0 until 8) meanGlu = Submodel.fmadd(models[i].st[0x34], models[i].st[0x2c], meanGlu)
    var acc = 0f
    for (i in 0 until 8) { models[i].combineVarPredGlu(meanGlu); acc = Submodel.fmadd(models[i].st[0x34], models[i].st[0x41], acc) }
    return kotlin.math.sqrt(acc)
}

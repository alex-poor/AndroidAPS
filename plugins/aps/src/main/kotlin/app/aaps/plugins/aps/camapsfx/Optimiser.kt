package app.aaps.plugins.aps.camapsfx

import kotlin.math.exp

/**
 * The CamAPS controller's set-point / optimiser front-end (guide §7). This file grows toward the full
 * `Model::Optimise` (the Tikhonov move-penalty active-set LQ solve); it starts with the reference-trajectory
 * builder, which the optimiser steers the predicted glucose onto.
 */
object Optimiser {
    const val LN2 = 0.6931472f

    private fun expf(x: Float) = exp(x.toDouble()).toFloat()

    /**
     * One relaxation step of the set-point toward [target] over `dt` minutes, with the binary's glucose
     * zones (`MPC::DetermineSetPoint`): fast descent above 13 (−2.5/h) and above 10 (−1.7/h); an exponential
     * relaxation with time-constant [tau] when out of the [target..target+2] band; and a gentle −1.0/h floored
     * at the target inside the band.
     */
    private fun zone(g: Float, dt: Float, target: Float, tau: Float): Float = when {
        g > 13f -> g + -0.041666668f * dt
        g > 10f -> g + -0.028333334f * dt
        g > target + 2f || g < target -> target + expf((-LN2 / tau) * dt) * (g - target)
        else -> { val t = g + -0.016666668f * dt; if (target <= t) t else target }
    }

    /**
     * `MPC::GetRate`'s per-tick target-glucose calc (0x14540c lines ~130-260). From the profile target
     * ([finalTarget], 5.8 default) + a fasting [offset] (0 / −0.4 / −0.8), raise the target when glucose is
     * LOW (`raise` term), add half the recent-high excess when the 3h-mean is above `offset+8`, add half the
     * predicted-SD, CAP at `finalTarget+offset+2.0`, then add the exercise ramp. [meanGlu3h] = GetMeanGlucose
     * over the last 3h; [estGlu] = the estimator's glucose (GetStartingGlucose); [predSD] = GetPredGlucSD;
     * [exerciseAdj] = the exercise term (0 when none). Sanity (high glucose, no exercise) ⇒ the cap = 7.8.
     * (The FastingAndGlucoseStable "hold at f13" branch and a small always-0 term are folded into the inputs.)
     */
    fun targetGlucose(finalTarget: Float, offset: Float, meanGlu3h: Float, estGlu: Float,
                      predSD: Float, exerciseAdj: Float, fastingStable: Boolean = false): Float {
        val f13 = finalTarget + offset
        val gLow = minOf(meanGlu3h, estGlu)
        var tgt = f13 + (if (f13 - 0.5f <= gLow) 0f else (f13 - gLow - 0.5f) * 0.5f + 0.5f)
        if (fastingStable) tgt = f13
        if (offset + 8f < meanGlu3h) tgt += (meanGlu3h - (offset + 8f)) * 0.5f
        tgt += predSD * 0.5f
        val cap = finalTarget + offset + 2f
        if (cap < tgt) tgt = cap
        tgt += exerciseAdj
        return tgt
    }

    /**
     * `MPC::DetermineSetPoint` — the reference glucose trajectory the optimiser tracks. Starts from the CGM
     * `predictLead` minutes ago ([startGlucose]) and relaxes toward [target] block-by-block; each value is
     * clamped to ≤ 12. The first (seed) relaxation uses [predictLead] as its step; block k uses `dt[k+1]`.
     * Returns a Vector<8> (only the first [nBlocks]−1 entries are filled).
     *
     * @param dt the per-control-block dt array (Vector<180>; block k reads dt[k+1]).
     */
    fun determineSetPoint(startGlucose: Float, target: Float, predictLead: Float, nBlocks: Int, dt: FloatArray): FloatArray {
        val out = FloatArray(8)
        val tau = if (startGlucose >= target) 60f else 15f
        val seed = zone(startGlucose, predictLead, target, tau)     // the first value (not stored directly)
        var g = if (seed <= 12f) seed else 12f
        for (k in 0 until nBlocks - 1) {
            val next = zone(g, dt[k + 1], target, tau)
            g = if (next <= 12f) next else 12f
            out[k] = g
        }
        return out
    }

    const val finalTargetGlucose = 5.8f          // Model1::finalTargetGlucose (0x92800)
    const val dtPerControlStep = 1               // _dtPerControlStep (0x94f88)
    const val durationExtensionMin = 240         // durationExtension (0x94f58) = 240 min = 8 × 30

    /**
     * `MPC::GetBIRpump` (0x148e44) — the pump basal profile at a time: `profile48[hour·2 + (min≥30?1:0)]`,
     * i.e. the half-hour slot of the (timezone-converted) local time. [profile48] = the 48 basal slots at
     * MPC this+0x18; [localHour]/[localMin] come from `Model::GetLocalTime` (same timezone conversion
     * `Model1::GetMealTypeIdx` uses). The per-slot basal profile is per-patient config.
     */
    fun getBIRpump(profile48: FloatArray, localHour: Int, localMin: Int): Float =
        profile48[localHour * 2 + (if (localMin >= 30) 1 else 0)]

    /**
     * `MPC::GetBIRStepsSmoothed` (0x146510) — the projected basal-insulin-requirement for one horizon step:
     * a triangularly-weighted average of the pump basal profile ([pumpSamples] = `GetBIRpump` at the step's
     * time minus 0, 30, 60 … 210 min), each floored at `(floorBase/24)·0.35` and, when [param3]==1, raised to
     * [cap] whenever the profile falls below it. Weights ramp 240/240, 210/240 … 30/240 (sum 4.5). Bit-exact
     * vs the emulator (all 7 horizon bir values). [floorBase] = this+0xc (≈ TDD scale), [cap] = this+0x14.
     */
    fun getBIRStepsSmoothed(pumpSamples: FloatArray, floorBase: Float, cap: Float, param3: Int): Float {
        val window = durationExtensionMin.toFloat()
        val floor = (floorBase / 24f) * 0.35f
        var num = 0f; var den = 0f; var rem = durationExtensionMin
        for (s in pumpSamples) {
            val w = rem.toFloat()
            val floored = if (s >= floor) s else floor
            den += w / window
            val v = if (cap <= floored || param3 != 1) floored else cap
            num += v * (w / window)
            rem -= 30
        }
        return num / den
    }

    /**
     * `Model::GetPreviousAdvice` (0x13ed28) — the optimiser warm-start: block k takes the mean of the previous
     * advice ([prev], = the BIR array in `MPC::Optimise`) over its control step, i.e. `u[k] = prev[k]` when
     * `dtPerControlStep == 1`. Block 0 is left at 0. `nBlocks` = param2.
     */
    fun getPreviousAdvice(prev: FloatArray, nBlocks: Int): FloatArray {
        val u = FloatArray(8)
        var base = 0
        for (k in 1..nBlocks) {
            var s = 0f
            for (t in 1..dtPerControlStep) s += prev[base + t]
            u[k] = s / dtPerControlStep
            base += dtPerControlStep
        }
        return u
    }
    const val controlStepInt = 25f               // _controlStepInt (0x94f84)
    const val lambdaBase = 1.6f                  // 0x... move-penalty ceiling
    const val lambdaBaseMeal = 1.2f              // move-penalty floor (non-bolus)
    const val lambdaBaseBolus = 1.0f             // move-penalty floor (bolus)

    /**
     * `Model::Optimise`'s M2 move-penalty regulariser (the `DᵀWD` tridiagonal added to `SᵀS`; guide §7,
     * [[camaps-optimise-is-lq-solve]]). Per block the effort weight is `w_k = λ_eff(τ_k)² / BIR` with
     * `λ_eff(τ) = λfloor + (λbase−λfloor)·τ/240` ramping to λbase at τ=240 (λfloor = [lambdaBaseBolus] for a
     * bolus else [lambdaBaseMeal]); the FIRST block ramps only when a meal is active and τ>50, later blocks
     * ramp whenever τ<240. Cost adds `Σ w_k(u_k − u_{k−1})²`, i.e. tridiagonal `M2[k][k]=w_{k−1}+w_k`,
     * `M2[k][k±1]=−w`. Row 0 (the fixed first block) stays zero. [bir]/[tau0]/[mealActive] are the vtable
     * getters (BIR, minutes-since-meal, LastMealActive) read at the M2-fill head.
     */
    fun buildM2(bir: Float, tau0: Float, mealActive: Int, bolus: Int, param1: Int,
                lambdaBase: Float = Optimiser.lambdaBase, lambdaBaseMeal: Float = Optimiser.lambdaBaseMeal): Array<FloatArray> {
        val floor = if (bolus == 1) lambdaBaseBolus else lambdaBaseMeal
        val sq = kotlin.math.sqrt(1.0 / bir).toFloat()            // √(1/BIR), 1.0/BIR in double as the binary
        val inv = sq * sq                                         // (√(1/BIR))²
        val ramp = (lambdaBase - floor) / 240f                    // float division (fdiv s)
        fun weight(le: Float): Float = inv * (le * le)            // inv·(le²), not (inv·le)·le
        var le = lambdaBase
        if (50f < tau0 && mealActive == 1) le = Submodel.fmadd(ramp, tau0, floor)   // floor + ramp·τ, fused
        var wPrev = weight(le)                                    // w_0
        val M2 = Array(8) { FloatArray(8) }
        var tau = tau0
        for (k in 1..param1) {
            tau += controlStepInt
            le = lambdaBase
            if (tau < 240f) le = Submodel.fmadd(ramp, tau, floor)
            val wk = weight(le)
            M2[k][k] = wPrev + wk
            if (k - 1 != 0) M2[k][k - 1] = -wPrev
            if (param1 != k) M2[k][k + 1] = -wk
            wPrev = wk
        }
        return M2
    }

    /**
     * `Model::Optimise` — the constrained least-squares control solve (guide §7). Minimises
     * `‖G(u) − e‖² + Σ w_k(u_k − u_{k−1})²` over the control blocks, linearising the nonlinear rollout each of
     * THREE Gauss-Newton iterations: `H = SᵀS + M2`, `g = Sᵀ(e − G(u) + S·u)` with the move-penalty boundary
     * `g[1] += anchor·w_last` (anchor = the current basal ins[0]; w_last = the last M2 weight — the binary
     * reuses the fill loop's leftover `fVar40`), then solves the free-block subsystem `u_free = H_free⁻¹ g_free`.
     * Block 0 is fixed at 0 for a non-[bolus] step; every block is bounded `u ≥ 0` (the active-set loop, which
     * in practice stays interior here). [runPfo]`(u)` = the bit-exact rollout `G(u)`.
     */
    fun optimise(u0: FloatArray, e: FloatArray, bolus: Int, param1: Int, param2: Int, p9: Float,
                 w24: Float, w2c: Float, bir: Float, tau0: Float, mealActive: Int, anchor: Float,
                 runPfo: (FloatArray) -> FloatArray, iters: Int = 3,
                 lambdaBase: Float = Optimiser.lambdaBase, lambdaBaseMeal: Float = Optimiser.lambdaBaseMeal): FloatArray {
        val M2 = buildM2(bir, tau0, mealActive, bolus, param1, lambdaBase, lambdaBaseMeal)
        val wLast = lastMoveWeight(bir, tau0, bolus, param1, lambdaBase, lambdaBaseMeal)   // w_{param1}, the fill loop's leftover fVar40
        val nR = param2 - 1; val nB = param1 + 1
        val free = if (bolus == 0) (1..param1).toList() else (0..param1).toList()
        var u = u0.copyOf()
        repeat(iters) {
            val baseline = runPfo(u)
            val S = calculateDerivative(u, param1, param2, bolus, p9, w24, w2c, baseline, runPfo)
            // residual r = (S·u − G(u)) + e, in the binary's order (Su first, then −baseline, then +e). All
            // the matrix products (S·u, SᵀS, Sᵀr, H⁻¹g) are FUSED multiply-adds in the binary.
            val res = FloatArray(nR) { r -> var s = 0f; for (j in 0 until nB) s = Submodel.fmadd(S[r][j], u[j], s); (s - baseline[r]) + e[r] }
            val g = FloatArray(nB) { i -> var s = 0f; for (r in 0 until nR) s = Submodel.fmadd(S[r][i], res[r], s); s }
            g[1] = Submodel.fmadd(anchor, wLast, g[1])              // g[1] + anchor·wLast, FUSED (single-rounded)
            val H = Array(nB) { i -> FloatArray(nB) { j -> var s = 0f; for (r in 0 until nR) s = Submodel.fmadd(S[r][i], S[r][j], s); s + M2[i][j] } }
            val m = free.size
            val A = Array(m) { r -> FloatArray(m) { c -> H[free[r]][free[c]] } }
            val gFree = FloatArray(m) { g[free[it]] }
            val Hinv = luInverse(A, m)                               // explicit inverse (ludcmp + lubksb)
            val x = FloatArray(m) { i -> var s = 0f; for (k in 0 until m) s = Submodel.fmadd(Hinv[i][k], gFree[k], s); s }  // u = H⁻¹·g
            val nu = u.copyOf()
            for (k in free.indices) nu[free[k]] = x[k]
            if (bolus == 0) nu[0] = 0f
            u = nu
        }
        return u
    }

    /** The last move-penalty weight `w_{param1}` exactly as [buildM2]'s fill loop leaves `fVar40` — the value
     *  `Model::Optimise` reuses for the gradient's boundary term `g[1] += anchor·w_last`. */
    private fun lastMoveWeight(bir: Float, tau0: Float, bolus: Int, param1: Int,
                               lambdaBase: Float = Optimiser.lambdaBase, lambdaBaseMeal: Float = Optimiser.lambdaBaseMeal): Float {
        val floor = if (bolus == 1) lambdaBaseBolus else lambdaBaseMeal
        val sq = kotlin.math.sqrt(1.0 / bir).toFloat(); val inv = sq * sq
        val ramp = (lambdaBase - floor) / 240f
        var tau = tau0; var w = inv * (lambdaBase * lambdaBase)
        for (k in 1..param1) {
            tau += controlStepInt
            val le = if (tau < 240f) Submodel.fmadd(ramp, tau, floor) else lambdaBase
            w = inv * (le * le)
        }
        return w
    }

    /**
     * `Matrix<float,8,8>::inv` — the explicit matrix inverse via `ludcmp` (Crout LU with implicit-scaled
     * partial pivoting) + `lubksb` over the identity columns (Numerical Recipes). For n ≤ 7 the binary's
     * SIMD-unrolled dot products degrade to the scalar left-to-right sums transcribed here.
     */
    private fun luInverse(Hin: Array<FloatArray>, n: Int): Array<FloatArray> {
        val a = Array(n) { Hin[it].copyOf() }
        val idx = IntArray(n)
        val vv = FloatArray(n)                                       // implicit row scaling
        for (i in 0 until n) {
            var big = 0f
            for (j in 0 until n) { val t = kotlin.math.abs(a[i][j]); if (t > big) big = t }
            vv[i] = 1f / big                                         // fdiv s (single-rounded float)
        }
        for (j in 0 until n) {
            for (i in 0 until j) {
                var sum = a[i][j]
                for (k in 0 until i) sum = Submodel.fmsub(a[i][k], a[k][j], sum)   // sum − a·b, fused
                a[i][j] = sum
            }
            var big = 0f; var imax = j
            for (i in j until n) {
                var sum = a[i][j]
                for (k in 0 until j) sum = Submodel.fmsub(a[i][k], a[k][j], sum)
                a[i][j] = sum
                val dum = vv[i] * kotlin.math.abs(sum)
                if (big <= dum) { imax = i; big = dum }
            }
            if (j != imax) {
                for (k in 0 until n) { val t = a[imax][k]; a[imax][k] = a[j][k]; a[j][k] = t }
                vv[imax] = vv[j]
            }
            idx[j] = imax
            if (j != n - 1) {
                val piv = 1f / a[j][j]
                for (i in j + 1 until n) a[i][j] = piv * a[i][j]
            }
        }
        // lubksb over each identity column → the inverse
        val inv = Array(n) { FloatArray(n) }
        for (col in 0 until n) {
            val b = FloatArray(n); b[col] = 1f
            var ii = -1
            for (i in 0 until n) {
                val l = idx[i]
                var sum = b[l]; b[l] = b[i]
                if (ii >= 0) { for (k in ii until i) sum = Submodel.fmsub(a[i][k], b[k], sum) }
                else if (kotlin.math.abs(sum) > 1e-5f) ii = i
                b[i] = sum
            }
            for (i in n - 1 downTo 0) {
                var sum = b[i]
                for (k in i + 1 until n) sum = Submodel.fmsub(a[i][k], b[k], sum)
                b[i] = sum / a[i][i]
            }
            for (r in 0 until n) inv[r][col] = b[r]
        }
        return inv
    }

    /**
     * `Model::AdviceToInsulinInfusion` — spreads the ≤8-block advice [u] over the per-step insulin infusion
     * array (`this+0x1b18`, a Vector<180> that PredictStep reads). Step `s` (2..[param2]) takes the advice of
     * block `min((s−2)/dtPerControlStep + 2, param1+1) − 1`; index 0 is left untouched (it keeps the
     * DataForOptimisation "current" insulin). [baseIns] supplies that untouched prefix.
     */
    fun adviceToInsulinInfusion(u: FloatArray, param1: Int, param2: Int, baseIns: FloatArray): FloatArray {
        val ins = baseIns.copyOf()
        for (s in 2..param2) {
            var blk = if (dtPerControlStep != 0) (s - 2) / dtPerControlStep else 0
            blk += 2
            if (param1 + 1 <= blk) blk = param1 + 1
            ins[s - 1] = u[blk - 1]
        }
        return ins
    }

    /**
     * `Model::CalculateDerivative` (vtable+8) — the optimiser's sensitivity matrix `S = ∂G/∂u`, built by
     * FORWARD FINITE-DIFFERENCE of the nonlinear forward rollout [runPfo] (which internally restores the
     * snapshot and rebuilds the insulin infusion from the perturbed control). `S[row][col] = (G(u+δ·e_col) −
     * G(u))[row] / δ`, with per-column step δ. Non-bolus ([bolus]=0) zeroes column 0 (the first block is
     * fixed); bolus finite-differences it with δ₀ = w24/50. Blocks ≥1 use δ = (w2c/24)·0.2 and, when the
     * starting glucose [p9] exceeds [finalTargetGlucose], collocate only on the terminal rows (`param2−2 …
     * param2−1`); at/below target every row is used. [runPfo]`(u)` returns the glucose trajectory (row r =
     * output index r); [base] is `G(u)`.
     *
     * @return the (param2−1)×(param1+1) sensitivity `S` (8×8-padded), matching the binary's Matrix layout.
     */
    fun calculateDerivative(u: FloatArray, param1: Int, param2: Int, bolus: Int, p9: Float,
                            w24: Float, w2c: Float, base: FloatArray, runPfo: (FloatArray) -> FloatArray): Array<FloatArray> {
        val S = Array(8) { FloatArray(8) }
        // ---- column 0 ----
        if (bolus == 0) {
            // non-bolus: block 0 is fixed → column 0 is identically zero (already zeroed)
        } else {
            val d0 = 10f / (500f / w24)                       // = w24/50
            val up = u.copyOf(); up[0] = up[0] + d0
            val out = runPfo(up)
            for (row in 0 until param2 - 1) S[row][0] = (out[row] - base[row]) / d0
        }
        // ---- columns 1..param1 ----
        if (param1 != 0) {
            val rowStart = if (p9 <= finalTargetGlucose) 1 else param2 - 2   // uVar8
            val d = (w2c / 24f) * 0.2f
            for (col in 1..param1) {
                val up = u.copyOf(); up[col] = up[col] + d
                val out = runPfo(up)
                var uVar4 = rowStart
                while (uVar4 != param2) {                     // rows uVar4−1 for uVar4 = rowStart..param2−1
                    val row = uVar4 - 1
                    S[row][col] = (out[row] - base[row]) / d
                    uVar4++
                }
            }
        }
        return S
    }
}

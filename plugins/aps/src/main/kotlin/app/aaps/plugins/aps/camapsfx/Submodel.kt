package app.aaps.plugins.aps.camapsfx

/**
 * One CamAPS submodel: the [Plant] (SetBIC/EndoBalance, validated) + the persistent PredictStep scratch
 * and the 6-D EKF covariance/state. Holds everything `SubModel1::PredictStep` reads/writes across ticks.
 *
 * PredictStep is being transcribed section-by-section (setup → insulin subsystem → gut → EGP/disturbance →
 * glucose matrix-exponential → Jacobian assembly → writeback), each validated against the emulator #240
 * reference (`decode/probe_predstep.py`). The verbatim arithmetic uses [fmadd]/[fmsub]/[fnmadd]/[fnmsub]
 * below, which match the port's AArch64 helpers EXACTLY (double-intermediate, single-rounded).
 */
class Submodel(
    val idx: Int,                    // 0x4c (1..8)
    val plant: Plant,                // owns weightKg, multWktInsIni, egp0, and the SetBIC-derived params
    val multWini: Float,             // 0x34
    val multWk31: Float = 2f,        // 0x38
    var usRegressionOn: Boolean = true
) {
    val weightKg get() = plant.weightKg

    // per-submodel structural params from Initialise
    var f0c = 0f                     // 0x0c — glucose subsystem param (renal/second-pool constant `c`)
    var f10 = 0.06f                  // 0x10 — process-noise base scale
    var tMaxIs = 45f                 // 0x14 — insulin tMax source constant (Initialise)
    var tMaxIBase = 0f               // 0x18 — base insulin tMax (= tMaxIs / multWktInsIni, set by SetBIC)
    var f40 = 0f                     // 0x40 — gut slow time-constant scale
    var f28 = 0f                     // 0x28 — channel-1 slow τ (= st+0xa0)
    var f24 = 0f                     // 0x24 — meal gut fast τ loaded into st[0x27] by IncludeNewMeal
    var f1c = 0f                     // 0x1c — gut τ InitialiseMealParameters loads (overwritten in the fresh path)
    var f20 = 0f                     // 0x20 — gut τ InitialiseMealParameters loads (overwritten in the fresh path)
    var vg = 0f                      // model+0x20 — glucose distribution volume (read via lVar11+0x20)
    var bicBir = 0f                  // 0x224 — BIR the plant was last SetBIC'd at
    var birStep = 0f                 // 0x220 — the BIR for this step (= SubModel1::GetBIR)
    // NB: the SetBIC-derived params at 0x22c/0x230/0x234/0x238/0x23c ARE plant.iRef/f01/egpHalf/tMaxIAbs/si
    // (same memory) — read them via `plant.*` so a BIR change (→ setBIC) is seen; do NOT duplicate them here.

    // cross-tick persistent scratch (recomputed only when dt changes)
    var lastDt = -1f                 // 0x21c
    var last238 = 0f                 // 0x238 — current tMaxIAbs
    var f228 = 0f                    // 0x228 — tMaxIAbs the block-2 insulin coeffs were last built with
    var stateLen = 69                // 0x60 — number of active state entries copied in/out
    var c248=0f; var c24c=0f; var c250=0f; var c254=0f; var c258=0f; var c25c=0f; var c260=0f
    var c264=0f; var c268=0f; var c26c=0f; var c270=0f; var c274=0f; var c278=0f; var c27c=0f; var c280=0f
    var c284=0f; var c288=0f; var c28c=0f; var c290=0f; var c294=0f; var c298=0f
    var f240=0f; var f244=0f          // 0x240, 0x244

    // transition-Jacobian entries PredictStep writes (guide §5.2), read by the EKF Learn
    var c29c=0f; var c2a0=0f; var c2a4=0f; var c2a8=0f; var c2ac=0f; var c2b0=0f; var c2b4=0f; var c2b8=0f
    var c2bc=0f; var c2c0=0f; var c2c4=0f; var c2c8=0f; var c2cc=0f; var c2d0=0f; var c2d4=0f; var c2d8=0f
    var c2dc=0f; var c2e0=0f; var c2e4=0f; var c2e8=0f; var c2ec=0f
    var c2f0=0f; var c2f4=0f; var c2f8=0f; var c2fc=0f; var c300=0f; var c304=0f; var c308=0f; var c30c=0f
    var f208 = 0                     // 0x208 large-change flag

    /** persistent state vector (the "L" mirror), indexed by byteOffset/4. Glucose 0x6c/0x70/0x74, p 0x84,
     *  Cs 0x88, f 0xa4; gut 0x1c/0x20/0x24/0x28; predicted G 0xb0, predicted CGM 0xb4; timers 0xa8/0xac. */
    val st = FloatArray(81)

    /** 6-D EKF covariance (self+0x1f8). States: g0, g1, p, f, g2(obs), Cs. P0 = diag(196,100,3.08e-5,0.09,196,3.08e-5). */
    val P = Array(6) { FloatArray(6) }

    // ---- IMM (SubModelIMM1) mixing scratch ----
    var cJ = 0f                                    // self+0x3e8 (1000) — Σ μ_i·w_i mixing normaliser
    val mixedState = FloatArray(80)                // self+0x400 — the mode-mixed state
    val mixedCov = Array(6) { FloatArray(6) }      // self+0x550 — the mode-mixed covariance
    fun initP0() {
        for (r in 0 until 6) P[r].fill(0f)
        val d = floatArrayOf(196f, 100f, 3.08025e-5f, 0.09f, 196f, 3.08025e-5f)
        for (i in 0 until 6) P[i][i] = d[i]
    }

    /** Set one PredictStep coefficient c<off> by its byte offset (0x248..0x30c) — for seeding from a captured image. */
    fun setCoef(off: Int, v: Float) { when (off) {
        0x248->c248=v;0x24c->c24c=v;0x250->c250=v;0x254->c254=v;0x258->c258=v;0x25c->c25c=v;0x260->c260=v;0x264->c264=v
        0x268->c268=v;0x26c->c26c=v;0x270->c270=v;0x274->c274=v;0x278->c278=v;0x27c->c27c=v;0x280->c280=v;0x284->c284=v
        0x288->c288=v;0x28c->c28c=v;0x290->c290=v;0x294->c294=v;0x298->c298=v;0x29c->c29c=v;0x2a0->c2a0=v;0x2a4->c2a4=v
        0x2a8->c2a8=v;0x2ac->c2ac=v;0x2b0->c2b0=v;0x2b4->c2b4=v;0x2b8->c2b8=v;0x2bc->c2bc=v;0x2c0->c2c0=v;0x2c4->c2c4=v
        0x2c8->c2c8=v;0x2cc->c2cc=v;0x2d0->c2d0=v;0x2d4->c2d4=v;0x2d8->c2d8=v;0x2dc->c2dc=v;0x2e0->c2e0=v;0x2e4->c2e4=v
        0x2e8->c2e8=v;0x2ec->c2ec=v;0x2f0->c2f0=v;0x2f4->c2f4=v;0x2f8->c2f8=v;0x2fc->c2fc=v;0x300->c300=v;0x304->c304=v
        0x308->c308=v;0x30c->c30c=v } }

    /**
     * Deep copy of the full submodel image (structural fields + coefficients + state + covariance). The
     * optimiser's finite-difference rollout ([ImmBank.predictForOptimise]) mutates the estimator, so each
     * control iterate must roll a FRESH copy of the post-estimation state — this is that copy (the binary's
     * MakeSnapshot/RestoreSnapshot).
     */
    fun copy(): Submodel {
        val s = Submodel(idx, plant.copy(), multWini, multWk31, usRegressionOn)
        s.f0c = f0c; s.f10 = f10; s.tMaxIs = tMaxIs; s.tMaxIBase = tMaxIBase; s.f40 = f40; s.f28 = f28
        s.f24 = f24; s.f1c = f1c; s.f20 = f20; s.vg = vg; s.bicBir = bicBir; s.birStep = birStep
        s.lastDt = lastDt; s.last238 = last238; s.f228 = f228; s.stateLen = stateLen; s.f208 = f208
        s.f240 = f240; s.f244 = f244
        s.c248=c248;s.c24c=c24c;s.c250=c250;s.c254=c254;s.c258=c258;s.c25c=c25c;s.c260=c260;s.c264=c264
        s.c268=c268;s.c26c=c26c;s.c270=c270;s.c274=c274;s.c278=c278;s.c27c=c27c;s.c280=c280;s.c284=c284
        s.c288=c288;s.c28c=c28c;s.c290=c290;s.c294=c294;s.c298=c298;s.c29c=c29c;s.c2a0=c2a0;s.c2a4=c2a4
        s.c2a8=c2a8;s.c2ac=c2ac;s.c2b0=c2b0;s.c2b4=c2b4;s.c2b8=c2b8;s.c2bc=c2bc;s.c2c0=c2c0;s.c2c4=c2c4
        s.c2c8=c2c8;s.c2cc=c2cc;s.c2d0=c2d0;s.c2d4=c2d4;s.c2d8=c2d8;s.c2dc=c2dc;s.c2e0=c2e0;s.c2e4=c2e4
        s.c2e8=c2e8;s.c2ec=c2ec;s.c2f0=c2f0;s.c2f4=c2f4;s.c2f8=c2f8;s.c2fc=c2fc;s.c300=c300;s.c304=c304
        s.c308=c308;s.c30c=c30c
        System.arraycopy(st, 0, s.st, 0, 81)
        for (r in 0 until 6) System.arraycopy(P[r], 0, s.P[r], 0, 6)
        s.cJ = cJ; System.arraycopy(mixedState, 0, s.mixedState, 0, 80)
        for (r in 0 until 6) System.arraycopy(mixedCov[r], 0, s.mixedCov[r], 0, 6)
        return s
    }

    /**
     * `SubModel1::SetBIC` — sets the (BIR-independent) structural coefficients [tMaxIBase]/[f244] from the
     * f2 multiplier (idempotent), then derives the BIR-dependent plant params via [Plant.setBIC].
     * Port lines 19–29: f244 = f2·0.0191, tMaxIBase = tMaxIs / f2.
     */
    fun setBIC(bir: Float) {
        f244 = plant.multWktInsIni * Float.fromBits(0x3c9c779a)   // 0.0191 · f2
        tMaxIBase = tMaxIs / plant.multWktInsIni                  // tMaxIs / f2
        plant.setBIC(bir)
    }

    /**
     * `PredictStep` insulin-subsystem coefficients (port lines 116–204), the two eigenvalue-solve blocks
     * that build the SC-insulin transition coefficients. Assumes a recompute (dt changed). tMaxI collapses
     * to [tMaxIBase] (the dose-dependent term is `×0.0`). Validated against the emulator #240 fields.
     */
    fun computeInsulinCoeffs(dt: Float) {
        val W = weightKg
        val fVar30 = -dt
        val tMaxI = tMaxIBase                        // f(self+0x18) (+ ×0.0 dose term)
        // ---- block 1 (0x24c/0x250/0x254/0x260/0x268/0x26c/0x270), fVar38 = f244 ----
        var fVar32 = expf(fVar30 / tMaxI)
        val fVar38 = f244
        c24c = fVar32
        c250 = (tMaxI / 60f) * (1f - fVar32)
        c260 = (dt * fVar32) / tMaxI
        val fVar36 = expf(-(dt * fVar38))
        c270 = fVar36
        val dVar17 = (50f / (W * 0.08127f)).toDouble()
        if (kotlin.math.abs(1f / tMaxI - fVar38) <= 1e-5f) {
            val dVar24 = tMaxI.toDouble(); val dVar19 = dVar24 * dVar24; val sD = (dt + tMaxI).toDouble()
            c254 = ((1.0 - ((dVar19 + sD * sD) * fVar32.toDouble()) / (dVar19 + dVar19)) * dVar17).toFloat()
            val d3 = Math.pow(dVar24, 3.0)
            c268 = ((dt.toDouble() * dt.toDouble() * 500.0 * fVar32.toDouble()) / (d3 * (W * 0.02709f).toDouble())).toFloat()
            fVar32 = ((dt * 1000f * fVar32).toDouble() / (dVar19 * (W * 0.02709f).toDouble())).toFloat()
        } else {
            val s2 = tMaxI * fVar38
            var s3 = fmadd(s2, dt + tMaxI, fVar30)
            val fVar25 = fmadd(fVar38, tMaxI, -1.0f)
            val s6 = fVar32 * fVar38
            var s2b = fmadd(s2, dt, fVar30)
            s3 = fmadd(tMaxI, -2.0f, s3)
            var dVar24 = fVar25.toDouble()
            s2b -= tMaxI
            s3 = fmadd(s6, s3, fVar36)
            dVar24 *= dVar24
            s2b = fVar32 * s2b
            s2b /= tMaxI
            s2b = fVar36 + s2b
            c254 = ((1.0 - s3.toDouble() / dVar24) * dVar17).toFloat()
            val s6b = fVar38 * 1000f
            c268 = ((s6b.toDouble() / (dVar24 * (W * 0.02709f).toDouble())) * s2b.toDouble()).toFloat()
            fVar32 = (fVar32 - fVar36) * (s6b / (fVar25 * (W * 0.02709f)))
        }
        c26c = fVar32
        computeInsulinCoeffsBlock2(dt)
    }

    /**
     * Block 2 of the insulin-coefficient recompute (0x258/0x278/0x27c/0x280) — the second-absorption-channel
     * (`tMaxIAbs`) coefficients. The binary recomputes these whenever `tMaxIAbs` (0x238, the current value)
     * differs from the value they were last built with (0x228 = [f228]), *even if dt is unchanged* — the
     * path that carries insulin-sensitivity adaptation into the filter (port lines 159–203). Uses the
     * current [c24c] (fVar20) and tMaxI = [tMaxIBase]. Sets [f228] ← tMaxIAbs.
     */
    fun computeInsulinCoeffsBlock2(dt: Float) {
        val W = weightKg
        val fVar30 = -dt
        val tMaxI = tMaxIBase
        val dVar17 = (50f / (W * 0.08127f)).toDouble()
        val tAbs = plant.tMaxIAbs                    // f(self+0x238) — current tMaxIAbs
        last238 = tAbs
        f228 = tAbs                                  // sync 0x228 ← 0x238 (block-2 rebuilt at this tMaxIAbs)
        val fVar31 = expf(-(dt * tAbs))
        c280 = fVar31
        val fVar20 = c24c
        if (kotlin.math.abs(1f / tMaxI - tAbs) <= 1e-5f) {
            val dVar24 = tMaxI.toDouble(); val dVar19 = (dt + tMaxI).toDouble(); val dVar34 = dVar24 * dVar24
            c258 = ((1.0 - ((dVar34 + dVar19 * dVar19) * fVar20.toDouble()) / (dVar34 + dVar34)) * dVar17).toFloat()
            val d3 = Math.pow(dVar24, 3.0)
            c278 = ((dt.toDouble() * dt.toDouble() * 500.0 * fVar20.toDouble()) / (d3 * (W * 0.02709f).toDouble())).toFloat()
            c27c = ((dt * 1000f * fVar20).toDouble() / (dVar34 * (W * 0.02709f).toDouble())).toFloat()
        } else {
            val s3a = tMaxI * tAbs
            val s6a = tAbs * fVar20
            val fVar36b = fmadd(tAbs, tMaxI, -1.0f)
            var s2a = fmadd(s3a, dt + tMaxI, fVar30)
            var s3b = fmadd(s3a, dt, fVar30)
            s2a = fmadd(tMaxI, -2.0f, s2a)
            var dVar24 = fVar36b.toDouble()
            s3b -= tMaxI
            s2a = fmadd(s6a, s2a, fVar31)
            dVar24 *= dVar24
            s3b *= fVar20
            s3b /= tMaxI
            s3b = fVar31 + s3b
            c258 = ((1.0 - s2a.toDouble() / dVar24) * dVar17).toFloat()
            val s6b = tAbs * 1000f
            c278 = ((s6b.toDouble() / (dVar24 * (W * 0.02709f).toDouble())) * s3b.toDouble()).toFloat()
            c27c = (fVar20 - fVar31) * (s6b / (fVar36b * (W * 0.02709f)))
        }
    }

    /**
     * `PredictStep` gut two-compartment section (port lines 228–302): two meal channels, each a
     * two-compartment chain with an absorption-rate cap. Writes the gut states L[7],L[8] (channel 1) and
     * L[9],L[10] (channel 2) and the coefficients 0x284/0x288/0x28c/0x290/0x294/0x298. `forceCoeff`
     * recomputes the (dt-gated) coefficients (for validation). Validated vs emulator #240.
     */
    fun computeGut(dt: Float, L: FloatArray, forceCoeff: Boolean) {
        val fVar30 = -dt
        val W = weightKg
        // ---- channel 2: compartments st[9]/st[10], τ st[0xf]/st[0x10] → L[9],L[10], coeffs 0x288/98/94 ----
        if (forceCoeff) {
            val tau1 = st[0xf]; val tau2 = st[0x10]
            c288 = expf(fVar30 / tau1)
            c298 = expf(fVar30 / tau2)
            c294 = ((c288 - c298) / (1f / tau2 - 1f / tau1)) / tau1
        }
        run {
            var fVar35 = st[9]; var fVar14 = st[10]
            if (fVar35 + fVar14 <= 0.001f) { L[9] = 0f; L[10] = 0f }
            else {
                fVar35 = c288 * st[9]; L[9] = fVar35
                val fVar25 = f40
                fVar14 = c298 * st[10] + st[9] * c294              // plain float mul/mul/add (not fused)
                var fVar36 = multWk31
                L[10] = fVar14
                if (0f < dt) {
                    val avail = ((st[9] + st[10]) - (fVar35 + fVar14)) / W
                    fVar36 = fVar36 * (f10 / 5.551f) * (dt / fVar25)
                    if (fVar36 < avail) {
                        fVar35 += W * (avail - fVar36)
                        var fVar31 = (st[0x10] * fVar36 * W) / dt
                        L[9] = fVar35
                        if (fVar31 < fVar14) { fVar31 = fVar14 - fVar31; L[9] = fVar31 + fVar35; L[10] = fVar14 - fVar31 }
                    }
                }
            }
        }
        // ---- channel 1: compartments st[7]/st[8], τ st[0x27]/st[0x28] → L[7],L[8], coeffs 0x284/90/8c ----
        if (forceCoeff) {
            val tau1 = st[0x27]; val tau2 = st[0x28]
            c284 = expf(fVar30 / tau1)
            c290 = expf(fVar30 / tau2)
            c28c = ((c284 - c290) / (1f / tau2 - 1f / tau1)) / tau1
        }
        run {
            val fVar32in = st[7]; val fVar20in = st[8]
            if (fVar32in + fVar20in <= 0.001f) { L[7] = 0f; L[8] = 0f }
            else {
                var fVar14 = c284 * st[7]; L[7] = fVar14
                val cap = multWk31 * (f10 / 5.551f) * (dt / f40)
                var fVar32 = c290 * st[8] + st[7] * c28c           // plain float mul/mul/add (not fused)
                val fVar35cap = if (0f <= cap) cap else 0f
                L[8] = fVar32
                if (0f < dt) {
                    val avail = ((st[7] + st[8]) - (fVar14 + fVar32)) / W
                    if (fVar35cap < avail) {
                        fVar14 += W * (avail - fVar35cap)
                        var x = (W * fVar35cap * f28) / dt
                        L[7] = fVar14
                        if (x < fVar32) { x = fVar32 - x; L[7] = fVar14 + x; L[8] = fVar32 - x }
                    }
                }
            }
        }
    }

    /**
     * The full `SubModel1::PredictStep` propagation for one step: advances the whole submodel state one
     * tick (insulin cascade → gut two-compartment → EGP/p/Cs disturbance → gut absorption flux → glucose
     * three-compartment matrix exponential → transition-Jacobian assembly → state writeback). Faithful
     * transcription of the port lines 62–706; validated end-to-end against emulator call #240.
     *
     * Reads the ORIGINAL state from [st], writes into a local mirror, and copies back at the end (so
     * every `f(st+…)` sees pre-step values, matching the binary). The dt-gated coefficient recomputes
     * ([computeInsulinCoeffs]/[computeGut] with forceCoeff) fire only when `dt` changes.
     *
     * @param param2 0 ⇒ always recompute the fresh branches; else the [runInsulin]/[runGut] flags decide.
     * @param cgmObs the CGM for this tick (mmol/L; ≈−999.9 or 0 ⇒ missing ⇒ no reference normalisation).
     * @param refGlu the measured reference glucose used to normalise the prediction (−999.9 ⇒ missing).
     */
    fun predictStep(
        param2: Int, dt: Float, bir: Float, ins: Float,
        refGlu: Float, cgmObs: Float, insAppInput: Float = 0f,
        runInsulin: Boolean = true, runGut: Boolean = true, flag360: Boolean = false
    ) {
        val n = stateLen
        val L = st.copyOf()
        L[0x33] = 0f                                  // port line 76: si(st+0xcc, 0) — reset the per-step log-likelihood slot
        val fVar30 = -dt

        // ---- BIR change → SetBIC (the +（model+0x24/48)*0 term is identically 0) ----
        birStep = bir                                // self+0x220 = SubModel1::GetBIR
        L[0x44] = bir
        if (kotlin.math.abs(bir - bicBir) > 1e-5f) { bicBir = bir; setBIC(bir) }

        val dtChanged = kotlin.math.abs(dt - lastDt) > 1e-5f

        // ==== insulin subsystem (SC cascade), port 100–227 ====
        if (runInsulin || param2 == 0) {
            if (dtChanged) computeInsulinCoeffs(dt)
            else if (kotlin.math.abs(f228 - plant.tMaxIAbs) > 1e-5f) computeInsulinCoeffsBlock2(dt)  // tMaxIAbs adapted
            val fVar20 = c24c                         // dt unchanged ⇒ fVar20 = c24c (line 162); recompute leaves it = c24c (171)
            val s9 = ins * c250; c248 = s9
            L[0] = fmadd(fVar20, st[0], s9) + insAppInput
            val e1 = c24c
            val t1 = c248 + (dt * (ins * e1)) / -60f; c25c = t1
            L[1] = fmadd(e1, st[1], fmadd(c260, st[0], t1))
            c264 = ins * c254
            L[2] = fmadd(c270, st[2], fmadd(c26c, st[1], fmadd(c268, st[0], c264)))
            f240 = (plant.si * (L[2] + st[2])) * 0.5f      // f(self+0x23c) = SI
            L[4] = f240
            if (f240 < 1e-5f) f240 = Float.fromBits(0x3727c5ac)
            c274 = ins * c258
            L[3] = fmadd(c280, st[3], fmadd(c27c, st[1], fmadd(c278, st[0], c274)))
        } else error("PredictStep: snapshot (non-fresh) insulin branch not implemented — estimator path expected")

        // ==== gut two-compartment (both meal channels), port 228–302 ====
        if ((runGut || param2 == 0) && !flag360) computeGut(dt, L, forceCoeff = dtChanged)

        // ==== endogenous production at old/new insulin, minus insulin-independent uptake, + p/Cs relaxation ====
        val egp0 = plant.egp0
        run {
            val vOld = egp0 * expf(-((st[3] - plant.iRef) * LN2) / plant.egpHalf)  // EGP at the OLD (state) insulin glucose
            val vNew = egp0 * expf(-((L[3] - plant.iRef) * LN2) / plant.egpHalf)   // EGP at the NEW insulin glucose
            c2f4 = vOld
            c2fc = vOld
            c2f0 = vNew
            c2f8 = vNew
            L[0x42] = vNew
            L[6] = c2fc
            L[5] = c2f8
            L[6] = c2fc
            L[4] = f240
        }
        val insIndep = ((plant.f01 * 6.5f / 5.5f) * st[0x2c]) / (st[0x2c] + 1.0f)   // f(self+0x230) = F01, saturating in G
        c2f8 -= insIndep
        c2fc -= insIndep
        L[0x43] = insIndep
        // Cs decays with half-life 540 min (gated on usRegression)
        c2e0 = 1.0f
        if (usRegressionOn) c2e0 = expf((LN2 * dt) / -540f)
        L[0x22] = c2e0 * st[0x22]
        // p relaxation blend (half-life 75 min branch, else the >8 mmol branch)
        c2c8 = 1.0f
        var pBlend: Float
        if (!usRegressionOn) {
            pBlend = L[0x21]
        } else {
            var fv = st[0x2c]                                             // predCgm (0xb0)
            var setAndBlend = false
            if (st[0x21] <= st[0x22]) {
                if (fv <= 6.5f) fv = c2c8
                else {
                    fv = expf((LN2 * dt) / -75f); c2c8 = fv
                    val fv14 = dt * 0.0007f * 0.5f
                    if (fv14 < (st[0x22] - st[0x21]) * (1.0f - fv)) {
                        fv = fv14 / (st[0x21] - st[0x22]) + 1.0f; setAndBlend = true
                    }
                }
            } else {
                val denom: Float
                if (fv <= 8.0f) denom = 50.0f
                else {
                    val tt = (fv - 8.0f) * 10.0f
                    denom = (if (tt <= 50.0f) tt else 50.0f) + 50.0f
                }
                fv = expf(-(dt * LN2) / denom); c2c8 = fv
                val fv14 = st[0x21] - st[0x22]
                if (dt * 0.0007f < fv14 * (1.0f - fv)) { fv = 1.0f - (dt * 0.0007f) / fv14; setAndBlend = true }
            }
            if (setAndBlend) c2c8 = fv
            pBlend = fmadd(st[0x21] - st[0x22], fv, st[0x22])
            L[0x21] = pBlend
        }
        c2f8 = pBlend + c2f8
        c2fc = st[0x21] + c2fc

        // ==== gut absorption flux (glucose appearance rate per channel), port 396–436 ====
        if (!flag360 && (runGut || param2 == 0)) {
            if (dt <= 0f) {
                L[0xd] = 0f; L[0xe] = 0f; L[0xb] = 0f; L[0xc] = 0f; c308 = 0f; c300 = 0f
            } else {
                var fVar20 = L[8]
                var fVar32 = (((st[7] + st[8]) - (L[7] + fVar20)) * 5.551f) / vg      // channel-1 appearance
                var fVar14 = (((st[9] + st[0xa]) - (L[9] + L[10])) * 5.551f) / vg     // channel-2 appearance
                if (fVar32 <= 0f) { fVar32 = 0f; c300 = 0f; L[0xb] = 0f }
                else {
                    fVar32 = fVar32 / dt; fVar32 = fVar32 + fVar32
                    fVar20 = (fVar20 / (st[8] + fVar20)) * fVar32; c300 = fVar20; L[0xb] = fVar20
                    fVar32 = fVar32 * (st[8] / (st[8] + L[8]))
                }
                c304 = fVar32; L[0xc] = fVar32
                if (fVar14 <= 0f) { c308 = 0f; L[0xd] = 0f; c30c = 0f; L[0xe] = 0f }
                else {
                    fVar14 = fVar14 / dt; fVar14 = fVar14 + fVar14
                    fVar32 = fVar14 * (L[10] / (L[10] + st[0xa])); c308 = fVar32; L[0xd] = fVar32
                    fVar14 = fVar14 * (st[0xa] / (st[0xa] + L[10])); c30c = fVar14; L[0xe] = fVar14
                }
            }
            L[0x2a] = dt + st[0x2a]
            L[0x2f] = st[0x2f]; L[0x30] = st[0x30]; L[0x31] = st[0x31]
        }

        // ==== glucose: three-compartment linear system, exact matrix exponential over dt (port 470–624) ====
        L[0x3f] = st[0x21] + fmadd(st[0x29], c300, st[0x11] * c308)
        c2f8 = fmadd(c308, st[0x11], c2f8)
        c2fc = fmadd(c30c, st[0x11], c2fc)
        val fBio = st[0x29]
        L[0x29] = fBio
        L[0x11] = st[0x11]
        var x = f240
        val c = f0c
        var a2 = x + 0.07f
        var a12 = a2 + 0.05f
        var a1 = a12 - c
        if (kotlin.math.abs(fmadd(x, 0.07f, -(c * a1))) < 1.0e-4f) {
            do { x += 1.0e-4f; a2 = x + 0.07f; a12 = a2 + 0.05f; a1 = a12 - c }
            while (kotlin.math.abs(fmadd(x, 0.07f, a1 * -c)) < 1.0e-4f)
            f240 = x
        }
        val f25 = x + -0.07f
        val s2a = fmadd(a2, 2.0f, 0.05f) * 0.05f
        val f27 = x * 0.07f
        val f40g = 0.07f - c
        val f32a = fmadd(-c, a1, f27)
        val f25p = f25 + 0.05f
        val f20 = (f25.toDouble() * f25.toDouble() + s2a.toDouble()).toFloat()
        val sq = kotlin.math.sqrt(f20.toDouble()).toFloat()
        val f44pre = fmsub(x, f40g, fmadd((0.05f - c) + 0.07f, 0.07f, c * 0.05f))
        val f26 = f20 + f20
        val f32 = f32a * f26
        val f25n = f25p * (sq / f20)
        val f21 = expf(-(dt * c))
        val s12sq = a12 + sq
        val em = expf(-(dt * (s12sq * 0.5f)))
        val ep = expf(fmadd(dt, sq, -(dt * (s12sq * 0.5f))))
        val f41 = ep - em
        val f18 = sq + sq
        val cOverF32 = c / f32
        val f45 = fmadd(c, -2.0f, a12)
        val s29 = -(ep * fmadd(sq, f45, f20))
        val f22v = em / f18
        val c2b0v = (f41 * 0.05f) / sq
        val s22q = a12 / sq
        c2a4 = (f41 * 0.07f) / sq
        val s17q = (c * 0.07f) / f32
        val s20q = (((x - sq) + -0.120000005f) * ep) / f18
        val inv32 = 1.0f / f32
        val f0540 = 0.05f - f40g
        val f16 = f40g + 0.05f
        val s10q = f40g * f26
        var f43 = fmadd(x, f0540, f16 * 0.120000005f)
        val f16f20 = f16 * f20
        val invX = 1.0f / x
        val f44 = f44pre * sq
        val cem = c * em
        f43 = f43 * sq
        val s28a = -(ep * fmadd(f40g, f20, f44))
        val s24a = fmsub(f40g, f20, f44)
        val s2bv = fnmsub(sq, f45, f20)
        val s26a = f16f20 + f43
        val s21a = f16f20 - f43
        val s30q = 0.05f / f27
        val s15q = s22q + -1.0f
        val s22p = s22q + 1.0f
        val s23a = (c * s26a) * ep
        var s24 = fmadd(em, s24a, s28a)
        val s28 = (0.120000005f - x) - sq
        val emp = em + ep
        val emh = em * 0.5f
        s24 = fmadd(s10q, f21, s24)
        val s28b = fmadd(f22v, s28, 1.0f)
        var s21v = fmadd(cem, s21a, s23a)
        val s4n = -(s10q * f21)
        c2a0 = fmsub(f41, f25n, emp) * 0.5f
        val s26b = fmadd(f41, f25n, emp)
        val s1b = fmadd(em, s2bv, s29)
        val s29b = fmadd(emh, s15q, 1.0f)
        val s20b = s28b + s20q
        val eph = ep * 0.5f
        s21v = fmadd(s4n, x, s21v)
        val s23b = fmadd(f26, f21, s1b)
        c2d0 = cOverF32 * s24
        val s22b = fmsub(s22p, eph, s29b)
        c2b0 = c2b0v
        c2b4 = s26b * 0.5f
        s21v = fmadd(inv32, s21v, 1.0f)
        c2d4 = s17q * s23b
        c2a8 = invX * s20b
        c2b8 = s30q * s22b
        c2d8 = invX * s21v
        val r0: Float; val r5: Float; val r1: Float; val r4: Float; val r2: Float
        if (dt <= 0.0f) {
            r2 = c2fc; r1 = 0f; r5 = 0f; r0 = 0f; r4 = 0f; c2e4 = 0f; c2e8 = 0f
        } else {
            val sqM = sq + -0.120000005f
            var t17 = fmadd(sqM, 0.120000005f, x * 0.02f)
            val sqP = sq + 0.120000005f
            t17 = fmsub(f22v, t17, 0.120000005f)
            val t19 = fmadd(sqP, 0.120000005f, x * -0.02f)
            val e18 = ep / f18
            t17 = fmsub(t19, e18, t17)
            val m1f27 = -1.0f / f27
            t17 = fmadd(m1f27, t17, dt)
            c2e4 = (1.0f / (dt * x)) * t17; L[0x12] = c2e4
            val f25b = f27 + f27
            var t16 = fmadd(a12, 2.0f, -0.120000005f)
            val t20 = fnmsub(a12, s12sq, f25b)
            t16 = t16 - f240
            t16 = fmadd(sq, t16, f25b)
            t16 = t16 - a12 * a12
            var t4b = fmsub(f22v, t16, a12)
            t4b = fmsub(t20, e18, t4b)
            t4b = fmadd(m1f27, t4b, dt)
            c2e8 = (0.05f / (dt * f27)) * t4b; L[0x13] = c2e8
            val xx = f240
            val cc = f0c
            val q6 = f32 / (f26 * xx)
            val f25c = (f25b * sq) * xx
            val u19 = fmadd(f40g, xx - cc, cc * -0.05f)
            var u16 = (x * -0.05f) * (c + -0.1f)
            val inv19 = 1.0f / u19
            val w19 = fmadd(cc, 0.120000005f, f27) / (f27 * cc)
            val v5 = f16 * (sqP * 0.120000005f)
            u16 = fmadd(f27, f0540, u16)
            val v1a = f16 * ((0.120000005f - sq) * 0.120000005f)
            val v3 = u16 + fmadd(xx * 0.05f, sq + xx, v5)
            val v1 = u16 + fmadd(xx * 0.05f, xx - sq, v1a)
            var acc = ((cc / f25c) * -ep) * v3
            acc = fmadd(cc * (em / f25c), v1, acc)
            acc = fmadd(f21, f40g / cc, acc)
            acc = fmadd(q6, dt - w19, acc)
            c2ec = (inv19 / dt) * acc; L[0x14] = c2ec
            r2 = c2fc
            r0 = c2e4; r5 = c2e8; r1 = c2ec
            r4 = (c2f8 - r2) / dt
        }
        c2dc = f21
        val c6 = c2a8; val c7 = c2b8; val c17 = c2d8; val c16 = c2a0; val c3 = c2a4
        c29c = fmadd(c6, r2, r4 * r0)
        c2ac = fmadd(c7, r2, r4 * r5)
        c2cc = fmadd(c17, r2, r4 * r1)
        var t = st[0x16] * c3
        t = fmadd(st[0x15], c16, t)
        t = fmadd(c6, c304, t)
        t = fmadd(r0, c300 - c304, t)
        c2bc = t; L[0x15] = t
        t = st[0x15] * c2b0
        t = fmadd(st[0x16], c2b4, t)
        t = fmadd(c2b8, c304, t)
        t = fmadd(c2e8, c300 - c304, t)
        c2c0 = t; L[0x16] = t
        t = st[0x15] * c2d0
        t = fmadd(st[0x17], c2dc, t)
        t = fmadd(st[0x16], c2d4, t)
        t = fmadd(c2d8, c304, t)
        t = fmadd(c2ec, c300 - c304, t)
        c2c4 = t; L[0x17] = t

        // ==== combine into the predicted state (glucose comps + bioavailability-scaled inputs) ====
        val g0 = st[0x1b]; val g1 = st[0x1c]; val g2 = st[0x1d]
        var q0 = fmadd(c2a8, pBlend, fmadd(c2a4, g1, fmadd(c2a0, g0, c29c)))
        L[0x1b] = q0
        var q1 = fmadd(c2b8, pBlend, fmadd(c2b4, g1, fmadd(c2b0, g0, c2ac)))
        L[0x1c] = q1
        var q2 = fmadd(c2d8, pBlend, fmadd(c2dc, g2, fmadd(c2d4, g1, fmadd(c2d0, g0, c2cc))))
        L[0x1d] = q2
        val d0 = fBio * c2bc; L[0x1e] = d0
        val d1 = fBio * c2c0; L[0x1f] = d1
        val d2 = fBio * c2c4; L[0x20] = d2
        if (q0 + d0 < 0.0f) { q0 = 0.001f - d0; L[0x1b] = q0 }
        if (q1 + d1 < 0.0f) { q1 = 0.001f - d1; L[0x1c] = q1 }
        q2 += d2
        if (q2 < 0.0f) { val s6 = 0.001f - d2; q2 = d2 + s6; L[0x1d] = s6 }
        L[0x1a] = q2
        L[0x18] = d0 + q0
        L[0x19] = d1 + q1

        // ==== normalise the prediction by the measured reference glucose (only when a CGM obs exists) ====
        var q18: Float; var fFinal: Float
        if (kotlin.math.abs(cgmObs) <= 1e-5f || kotlin.math.abs(cgmObs - -999.9f) <= 1e-5f) {
            q18 = L[0x18]; fFinal = L[0x1a]
        } else {
            if (kotlin.math.abs(refGlu - -999.9f) < 1e-5f) error("PredictStep: refGlu missing (crit 0x70)")
            fFinal = L[0x1a]
            val fVar14 = (fFinal / 0.14f) / (kotlin.math.abs(refGlu) + 1e-5f)
            q18 = L[0x18] / fVar14
            L[0x19] = L[0x19] / fVar14
            fFinal = fFinal / fVar14
            L[0x20] = L[0x20] / fVar14
            L[0x1b] = L[0x1b] / fVar14
            L[0x1a] = fFinal
            L[0x18] = q18
            L[0x1e] = L[0x1e] / fVar14
            L[0x1f] = L[0x1f] / fVar14
            L[0x1c] = L[0x1c] / fVar14
            L[0x1d] = L[0x1d] / fVar14
            if (0.0f < L[8]) L[0x29] = L[0x29] / fVar14
        }
        L[0x2c] = q18 / 0.14f
        L[0x2d] = fFinal / 0.14f
        if (1e-5f < kotlin.math.abs(lastDt - dt) && idx == 1) forgettingFactor = expf((LN2 / 150.0f) * fVar30)
        L[0x2b] = dt + st[0x2b]
        L[0x36] = dt + st[0x36]
        L[0x37] = dt + st[0x37]
        L[0x3e] = dt + st[0x3e]
        if (1e-5f < kotlin.math.abs(refGlu - -999.9f)) { L[0x12] = 0f; L[0x13] = 0f; L[0x14] = 0f }
        lastDt = dt
        for (k in 0 until n) st[k] = L[k]
    }

    /**
     * `SubModel1::Learn` — one full EKF cycle: [predictStep] (state + transition Jacobian) → covariance
     * predict `P ← F·P·Fᵀ + Q` → (measurement update | long-gap p-decay) → recombination → GetInsulinForUs.
     * Faithful transcription of the port lines 756–898; validated end-to-end vs emulator Learn #240.
     *
     * @param obs the CGM measurement (model+0x23f8, mmol/L; −999.9 = missing → no update).
     * @param cgmObs the second CGM slot (model+0x2400) that gates PredictStep's reference normalisation.
     */
    fun learn(param2: Int, dt: Float, bir: Float, ins: Float, obs: Float, cgmObs: Float, insAppInput: Float = 0f,
              runInsulin: Boolean = true, runGut: Boolean = true, flag360: Boolean = false) {
        predictStep(param2, dt, bir, ins, refGlu = obs, cgmObs = cgmObs, insAppInput = insAppInput,
                    runInsulin = runInsulin, runGut = runGut, flag360 = flag360)

        // ---- covariance prediction P ← F·P·Fᵀ + Q ----
        val sig = if (st[0x2a] <= 180f) 0.0005034878f else 0.00029047372f
        val q = sig * multWini * sig * multWini
        val dt1 = if (dt <= 1f) 1f else dt
        val qs = q / dt1
        val v = floatArrayOf(dt * c2e4, dt * c2e8, dt, dt * 0f, dt * c2ec, dt * 0.45f * (8.437498e-8f / q))
        val F = Array(6) { FloatArray(6) }
        F[0][0] = c2a0; F[0][1] = c2a4; F[1][0] = c2b0; F[1][1] = c2b4; F[2][2] = c2c8; F[3][3] = 1f
        F[4][0] = c2d0; F[4][1] = c2d4; F[4][4] = c2dc; F[0][2] = c2a8; F[1][2] = c2b8; F[4][2] = c2d8
        F[2][5] = 1f - c2c8; F[5][5] = c2e0
        // Matrix8_mul accumulates with fmadd (product exact, one rounding/term) — this asymmetry is real.
        val FP = Array(6) { r -> FloatArray(6) { c -> var acc = 0f; for (k in 0 until 6) acc = fmadd(F[r][k], P[k][c], acc); acc } }
        val T = Array(6) { r -> FloatArray(6) { c -> var acc = 0f; for (k in 0 until 6) acc = fmadd(FP[r][k], F[c][k], acc); acc } }  // FP·Fᵀ
        for (r in 0 until 6) for (c in 0 until 6) P[r][c] = T[r][c] + qs * v[r] * v[c]

        val tSince = st[0x2b]                                    // minutes since the last measurement update
        st[0x33] = 0f                                            // 0xcc — per-step loglik slot
        st[0x32] = forgettingFactor * st[0x32]                   // 0xc8 — forgetting-weighted loglik accumulator
        if (tSince < 1f || kotlin.math.abs(obs - -999.9f) <= 1e-5f) {   // no valid reading → early return
            if (39.5f < tSince && kotlin.math.abs(obs - -999.9f) < 1e-5f) {
                val e = expf((LN2 / -150f) * tSince)
                st[0x24] = fmadd(st[0x24], e, 0f) / (e + 1f)     // 0x90 — long-gap p-rate decay
            }
            return
        }

        // ---- measurement update ----
        val sinceMeal = st[0x2a]
        val infl = if (sinceMeal <= 27f && !sinceMeal.isNaN() && !(sinceMeal < 5f)) 2.5f else 1f
        var floor = if (tSince <= 15f) tSince else 15f
        floor = kotlin.math.sqrt(fmadd(floor - 1f, 0f, 0.0256f).toDouble()).toFloat()   // = 0.16
        val g35 = if (3.5f <= obs) obs else 3.5f
        val sd = if (g35 * 0.02f <= floor) floor else g35 * 0.02f
        val h = c2c4
        val innov = obs - st[0x2d]                               // 0xb4 = predicted CGM
        val r0 = infl * sd * 0.14f
        val innovM = innov * 0.14f
        val sP = fmadd(h, fmadd(h, P[3][3], P[4][3]), fmadd(h, P[3][4], P[4][4]))
        val S = (r0.toDouble() * r0 + sP.toDouble()).toFloat()  // R summed in double (asm 0x57458)
        st[0x35] = innov / (infl * sd)                          // 0xd4 — normalised innovation
        var ll = if (1e-30f <= S / 0.0196f) S / 0.0196f else 1e-30f
        ll = ((-kotlin.math.ln(ll.toDouble())) - (innovM.toDouble() * innovM) / S.toDouble()).toFloat()
        st[0x33] = ll
        if (idx == 2 || idx == 4) { ll = -1e10f; st[0x33] = Float.fromBits(0xd01502f9.toInt()) }
        st[0x32] = ll + st[0x32]
        val kf = 0.14f / S
        val K = FloatArray(6) { r -> kf * fmadd(h, P[r][3], P[r][4]) }
        val gain = innovM / S
        st[0x1b] = fmadd(gain, fmadd(h, P[0][3], P[0][4]), st[0x1b])   // 0x6c glucose A
        st[0x1c] = fmadd(gain, fmadd(h, P[1][3], P[1][4]), st[0x1c])   // 0x70 glucose B
        st[0x1d] = fmadd(gain, fmadd(h, P[4][3], P[4][4]), st[0x1d])   // 0x74 glucose C (obs)
        // p (0x84): rate-limited ±5.517e-4/min; also an EWMA of the rate (0x90) and the raw rate (0x8c)
        val dp = gain * fmadd(h, P[2][3], P[2][4])
        val rate = dp / tSince
        var lim = if (0f <= rate) 0.0005517241f else -0.0005517241f
        var step = tSince * lim
        if (kotlin.math.abs(rate) <= 0.0005517241f) { step = dp; lim = rate }
        val e150 = expf((LN2 / -150f) * tSince)
        st[0x21] = step + st[0x21]
        st[0x23] = lim
        st[0x24] = fmadd(st[0x24], e150, lim) / (e150 + 1f)
        // Cs (0x88): rate-limited ±2.4828e-4/min, clamped ±0.005
        val dc = gain * fmadd(h, P[5][3], P[5][4])
        val rc = dc / tSince
        var sc = if (0f <= rc) 0.00024827584f else -0.00024827584f
        sc = tSince * sc
        if (kotlin.math.abs(rc) <= 0.00024827584f) sc = dc
        val csv = sc + st[0x22]
        st[0x22] = if (csv < -0.005f) -0.005f else if (0.005f < csv) 0.005f else csv
        // f/bioavailability (0xa4): clamped [0.2, 2.2]
        val fb = fmadd(K[3], innov, st[0x29])
        st[0x29] = if (fb < 0.2f) 0.2f else if (2.2f < fb) 2.2f else fb
        // covariance: P ← (I − K H) P     (H = [0,0,0,h,1,0])
        val IKH = Array(6) { FloatArray(6) }
        for (r in 0 until 6) {
            if (r < 4) IKH[r][r] = 1f
            IKH[r][3] = IKH[r][3] - (fmadd(P[r][3], h, P[r][4]) * h) / S
            if (r == 4) IKH[4][4] = 1f
            IKH[r][4] = IKH[r][4] - fmadd(h, P[r][3], P[r][4]) / S
        }
        IKH[5][5] = 1f
        val newP = Array(6) { r -> FloatArray(6) { c -> var acc = 0f; for (k in 0 until 6) acc = fmadd(IKH[r][k], P[k][c], acc); acc } }
        for (r in 0 until 6) for (c in 0 until 6) P[r][c] = newP[r][c]

        // ---- recombination: f-scaled inputs + reported glucose ----
        val fv = st[0x29]
        st[0x1e] = c2bc * fv                                     // 0x78
        st[0x1f] = fv * c2c0                                     // 0x7c
        st[0x20] = fv * c2c4                                     // 0x80
        val g0 = fmadd(c2bc, fv, st[0x1b]); st[0x18] = g0        // 0x60
        st[0x19] = fmadd(c2c0, fv, st[0x1c])                     // 0x64
        val g2 = fmadd(c2c4, fv, st[0x1d]); st[0x1a] = g2        // 0x68
        st[0x2c] = g0 / 0.14f                                    // 0xb0 reported G
        st[0x2d] = g2 / 0.14f                                    // 0xb4 reported CGM
        if (1f < kotlin.math.abs(obs - g2 / 0.14f)) f208 = 1
        val us = plant.getInsulinForUs(bir, st[0x21], st[0x22]) // NB re-derives the plant at bir
        st[0x2b] = 0f                                            // 0xac reset time-since-update
        st[0x3f] = st[0x21] + fmadd(st[0x29], st[0xb], st[0x11] * st[0xd])   // 0xfc
        st[0x40] = us / bir                                      // 0x100
    }

    /**
     * `SubModel1::IncludeNewMeal` — the FRESH path (`param_2 == 0`, the optimiser/forward rollout;
     * `inLearning == 0` so the learning-only branches of the callees are skipped). The binary works on a
     * local copy of the state that is memmoved back at the end, and the inlined
     * `InitialiseMealParameters` mutates the live state which is then partly read back — the net effect is
     * folded directly into [st] here (only st[0x29]/0x2f/0x30 survive from InitialiseMealParameters).
     *
     * `mealVal` = the horizon meal input this step (model+0x2418[step−1], −999.9/≤0 = none); `bio`/`prob`
     * are `Model1::GetRunningMealBio`/`Prob` (both 1.0 when there is no scheduled meal); `g24b8`/`g24bc`/
     * `g24c4` are the mixture anticipated-meal params from [ImmBank.beforeIncludeNewMeal] (fVar19/fVar20/
     * the ch1-comp2 total); `weight` = model+0x20. `dt` = the step's horizon dt.
     *
     * `st[0x2e]` (byte 0xb8) is zeroed near the top for EVERY call (the copy is re-initialised), then set
     * to 1.0 → 2.0 only on the main meal-reorg branch — so a skip/small step leaves it 0 (this is why a
     * post-reorg step shows st[0x2e] flipping 2→0). Meal-type-idx / SD lookup have no rollout state effect.
     */
    fun includeNewMeal(bio: Float, prob: Float, dt: Float, mealVal: Float,
                       g24b8: Float, g24bc: Float, g24c4: Float, weight: Float,
                       inLearning: Boolean = false, db: MealPriorDb? = null, stepLocalMin: Int = 0) {
        st[0x2e] = 0f                                            // buf[0xb8] cleared before any branch
        val sinceMeal = st[0x2a]
        val fVar16 = kotlin.math.abs(mealVal - (-999.9f))
        if (mealVal <= 0f || fVar16 <= 1e-5f) {
            // no valid meal input this step — the anticipation gate decides whether to reorganise
            if (sinceMeal <= 60f || g24c4 <= 0f ||
                (sinceMeal < 210.5f && 20f <= (g24b8 / weight) * 70f)) return   // skip (st[0x2e] stays 0)
        }
        val fVar18 = if (1e-5f <= fVar16) mealVal else 0f
        if (!(180.5f < sinceMeal || g24b8 <= fVar18)) {
            st[0x7] = fVar18 * bio + st[0x7]                     // small path: add the meal to gut ch1
            return
        }
        // ---- main meal-reorg branch ----
        val dtNeg = -dt
        val old7 = st[0x7]; val old8 = st[0x8]
        val old1e = st[0x1e]; val old1f = st[0x1f]; val old20 = st[0x20]
        val old27 = st[0x27]; val old28 = st[0x28]; val old29 = st[0x29]
        val old2f = st[0x2f]; val old30 = st[0x30]; val old31 = st[0x31]
        if (0f < fVar18) st[0x2a] = 0f
        st[0x15] = 0f; st[0x16] = 0f; st[0x17] = 0f
        st[0x1e] = 0f; st[0x1f] = 0f; st[0x20] = 0f
        st[0x2e] = 1f
        st[0x9] = old7 + st[0x9]; st[0xa] = old8 + st[0xa]        // consolidate gut ch1 → ch2
        st[0x1b] = old1e + st[0x1b]; st[0x1c] = old1f + st[0x1c]  // and the recombination scratch
        st[0x1d] = old20 + st[0x1d]
        if (g24bc < g24b8) {
            st[0x2e] = 2f
            st[0xf] = old27; st[0x10] = old28; st[0x11] = old29
            c288 = expf(dtNeg / old27); c298 = expf(dtNeg / old28)
            c294 = ((c288 - c298) / (1f / old28 - 1f / old27)) / old27
        }
        // InitialiseMealParameters (inLearning=0): gut-coeff rescale + reload the meal τ's (live writes
        // that survive the copy-back are st[0x29]=f40, and the st[0x2f]/st[0x30] scaling by clamp(f)).
        val cf = if (old29 <= 1.2f) (if (old29 < 0.7f) 0.7f else old29) else 1.2f
        var clampW = if (weight <= 100f) weight else 100f
        if (clampW < 35f) clampW = 35f
        if (old31 <= (clampW / 70f) * 40f) {
            if (0f < old31) st[0x30] = cf * old30
        } else {
            st[0x2f] = cf * old2f
        }
        // `InitialiseMealParameters`, inLearning==1 block (0x1560e0+): bank the learned bioavailability (fBio,
        // clamped to [0.7,1.2] = `cf`) into the running prior via `Model1::UpdateRunningMealProbAndBio` (out-param
        // = the running bio SD), THEN reset fBio → nominal `f40` and reset the fBio (state index 3, byte 0xa4) row
        // & column of the 6-D covariance with P[3][3] = SD². Emulator-confirmed (tick_0021): row3/col3 → 0,
        // P[3][3] → SD²; the bank uses the meal's ORIGINAL time bucket (now − sinceMeal), size `st[0x31]` and
        // the current mode-prob `st[0x34]`. With no DB the SD is the capped default 0.3 (→ P[3][3]=0.09). The
        // reset FREEZES fBio while the gut is empty (K[3]≈0), a no-op at the first meal (P still at init).
        val bankSd: Float = if (inLearning && db != null) {
            val bankBucket = db.bucket(stepLocalMin - sinceMeal.toInt())
            db.update(bankBucket, old31, weight, idx, cf, st[0x34])
        } else 0.3f
        st[0x29] = f40
        if (inLearning) {
            for (k in 0 until 6) { P[3][k] = 0f; P[k][3] = 0f }
            P[3][3] = bankSd * bankSd
        }
        if (0f < fVar18) st[0x34] = prob
        st[0x27] = f24; st[0x28] = f28
        c284 = expf(dtNeg / f24); c290 = expf(dtNeg / f28)
        c28c = ((c284 - c290) / (1f / f28 - 1f / f24)) / f24
        st[0x7] = fVar18 * bio; st[0x8] = 0f; st[0x31] = fVar18
    }

    companion object {
        const val FBIO_RESET_VAR = 0.09f             // meal-finalisation fBio variance (local_5c²; P0 default = capped prior SD 0.3²)
        var forgettingFactor = 1f                    // G_SUBMODEL1_FORGETTING_FACTOR (written only by idx 1)
        const val LN2 = 0.6931472f                   // ln(2) as f32 (G_LN2)
        private fun expf(x: Float) = kotlin.math.exp(x.toDouble()).toFloat()
        // AArch64 fused multiply-adds — EXACTLY the port's helpers (double product exact, one double add, round once).
        fun fmadd(n: Float, m: Float, a: Float): Float = (n.toDouble() * m + a).toFloat()        // a + n*m
        fun fmsub(n: Float, m: Float, a: Float): Float = (a.toDouble() - n.toDouble() * m).toFloat()  // a - n*m
        fun fnmadd(n: Float, m: Float, a: Float): Float = (-a.toDouble() - n.toDouble() * m).toFloat() // -a - n*m
        fun fnmsub(n: Float, m: Float, a: Float): Float = (n.toDouble() * m - a).toFloat()        // n*m - a
    }
}

package app.aaps.plugins.aps.camapsfx

/**
 * The clean-room CamAPS controller — the top-level assembly of the validated modules into one control
 * decision, mirroring `MPC::GetRate` (guide §8). The heavy lifting is done by the (bit-exact) [Submodel]
 * estimator, the [ImmBank] IMM rollout, the [Optimiser] LQ solve, and the [OutputPipeline] safety layer;
 * this object just chains them.
 *
 * One tick, given the current estimator state and the assembled horizon:
 *   1. [ImmBank.tickStep]            — advance the 6-D EKF / IMM bank on the new CGM (the estimator update).
 *   2. [Optimiser.determineSetPoint] — the reference glucose trajectory `e` the optimiser tracks.
 *   3. [Optimiser.getPreviousAdvice] — warm-start `u` = the BIR array.
 *   4. [Optimiser.optimise]          — the constrained least-squares control (3 Gauss-Newton iterations).
 *   5. the seven [OutputPipeline] modifiers — the safety envelope (cap / suspend / brake / rescue).
 *   6. [OutputPipeline.finalRate]    — convert to the pump's 0.05 U/h grid.
 *
 * The horizon arrays (dt, cgm, ins, bir, mealin) come from the data-access layer (`GetDataForOptimisation`
 * reading the CGM/insulin/meal history + the 48-slot profile via [Optimiser.getBIRpump]); that layer is the
 * remaining plumbing to run this on live data.
 */
object Controller {

    /**
     * The delivered basal rate for one tick, happy-path (safety modifiers inactive, as in the sanity run):
     * take the optimiser's collocated block-1 advice (block-0 for a bolus step) and round to the pump grid.
     * [conv] = the basal→rate factor (MPC this+0x63f4). Validated end-to-end: u[1]=1.315053 → 1.35 U/h.
     * A live tick runs the seven [OutputPipeline] modifiers on [optimisedU]`[block]` first — they are no-ops
     * whenever glucose/trend/occlusion/staleness are all benign.
     */
    fun deliveredRate(optimisedU: FloatArray, bolus: Int, conv: Float): Float {
        val raw = optimisedU[if (bolus == 0) 1 else 0]
        return OutputPipeline.finalRate(raw, conv)
    }

    /** One control decision's front-half: warm-start, setpoint, LQ solve, delivered rate, and the raw
     *  pre-round/pre-modifier rate (= optimiser block / conv) that the emulator reports as `emu_rawrate`. */
    data class Solve(val warmStart: FloatArray, val setpoint: FloatArray, val optimisedU: FloatArray, val rate: Float, val rawRate: Float,
                     val rollout0: FloatArray = FloatArray(0), val predEventual: Float = 0f)

    /**
     * The optimiser front-half assembly (`MPC::Optimise` 0x14664c wiring): from the assembled horizon
     * ([bir]/[dt]) + this tick's [startGlucose]/[target], build the warm-start ([Optimiser.getPreviousAdvice])
     * and setpoint ([Optimiser.determineSetPoint]), run the constrained LQ solve ([Optimiser.optimise]), and
     * round to the pump grid. This is the live control path *minus* the estimator tick (which runs first, via
     * [ImmBank.tickStep], to update the state [runPfo] rolls from) and the seven [OutputPipeline] safety
     * modifiers (no-ops in the benign case). [p9] = `GetStartingGlucose` (the optimiser's collocation-row
     * gate); [anchor] = the current basal (move-penalty toward last delivery); [conv] = the rate factor.
     */
    fun runControlSolve(
        bir: FloatArray, dt: FloatArray, startGlucose: Float, target: Float, predictLead: Float,
        p1: Int, p2: Int, bolus: Int, p9: Float, w24: Float, w2c: Float, optBir: Float, tau0: Float,
        mealActive: Int, anchor: Float, conv: Float, runPfo: (FloatArray) -> FloatArray,
        lambdaBase: Float = Optimiser.lambdaBase, lambdaBaseMeal: Float = Optimiser.lambdaBaseMeal,
    ): Solve {
        val u0 = Optimiser.getPreviousAdvice(bir, p1)
        val e = Optimiser.determineSetPoint(startGlucose, target, predictLead, p2, dt)
        val rollout0 = runPfo(u0)
        val u = Optimiser.optimise(u0, e, bolus, p1, p2, p9, w24, w2c, optBir, tau0, mealActive, anchor, runPfo,
            lambdaBase = lambdaBase, lambdaBaseMeal = lambdaBaseMeal)
        val rawRate = u[if (bolus == 0) 1 else 0] / conv
        // CAMR's own forecast: the model's predicted glucose at the horizon end UNDER the chosen control u
        // (one more forward rollout on a fresh estimator copy). This is what the controller expects to happen if
        // its rate is followed — the honest "eventual" to display.
        val fc = runPfo(u)
        val predEventual = fc.take(p2).lastOrNull { it > 0.5f } ?: startGlucose
        return Solve(u0, e, u, deliveredRate(u, bolus, conv), rawRate, rollout0, predEventual)
    }

    /** One tick's full result: the delivered rate + the derived operating point (for logging/diffing). */
    data class TickResult(val rate: Float, val conv: Float, val c: Float, val x14: Float, val target: Float, val nSteps: Int, val rawRate: Float = 0f,
        val p9: Float = 0f, val tau0: Float = 0f, val w24: Float = 0f, val optBir: Float = 0f, val anchor: Float = 0f, val startG: Float = 0f,
        val e: FloatArray = FloatArray(0), val u: FloatArray = FloatArray(0),
        val u0: FloatArray = FloatArray(0), val rollout0: FloatArray = FloatArray(0),
        val progGlu: Float = 0f, val predSD: Float = 0f, val occLowestBG: Float = 0f, val occDeltaBIR: Float = 0f,
        val predEventualMmol: Float = 0f)

    /** Fixed reference TDD (the newPatient default fed to ModifyTDD; also the conv numerator). */
    const val REF_TDD = 45.0f

    /**
     * The whole control tick from a parsed input file — the reusable replay entry (mirrors `oneRun`). Derives
     * the operating point (conv = REF_TDD/Basal, profile×conv, x14 = mean, c = [TddAdapt.modifyTDD]), assembles
     * the GDFL learning horizon + GDFO control horizon, runs the cold-init estimator forward ([ImmBank.runLearn]),
     * and solves the control ([runControlSolve]). [coldInit] supplies a fresh 8-submodel cold-init image (the
     * fixed weight-independent template); [mealProb]/[longUpdate] the per-step meal-mode-prob / long-EWMA gate.
     * The horizon control config (p1/p2/…) is passed in [cfg]. Safety modifiers are the benign pass-through.
     */
    fun runTick(
        p: InputParser, basalUday: Float, coldInit: () -> Array<Submodel>, cfg: Tick.Config,
        mealProb: (step: Int, submodel: Int) -> Float, longUpdate: (step: Int) -> Boolean,
        mealBio: (step: Int, submodel: Int) -> Float = { _, _ -> 1f },
        mealDb: MealPriorDb? = null,   // the prior-meal DB (GetRunningMealBio/Prob); when set, overrides mealBio/mealProb
    ): TickResult {
        val conv = REF_TDD / basalUday
        val prof = FloatArray(48) { p.basalProfileUh[it] * conv }
        val x14 = maxOf(0.025f, (prof.sum() / 48f))
        // c = ModifyTDD(REF_TDD) — cold single tick: FractionTDD=1.0, no exercise/hypo unless the tick shows it.
        val c = TddAdapt.modifyTDD(REF_TDD, p.targetProfile, p.localMinuteOfDay / 60, p.localMinuteOfDay % 60,
            prevCGM = p.cgm.lastOrNull()?.a ?: 5.5f, exerciseActive = false, minBG2h = 10f, minGlu1h = 10f,
            fracTDD24 = FloatArray(24) { 1f }, paramsLoaded = true)
        val floorBase = c

        // estimation over the GDFL horizon from cold-init
        val g = Gdfl.assemble(p, prof, conv, floorBase, x14)
        val bank = ImmBank(coldInit(), ImmBank.buildTransition(g.dt[0]))
        val weight = p.weightKg
        val forget = FloatArray(g.n) { java.lang.Math.pow(2.0, -(g.dt[it].toDouble()) / 150.0).toFloat() }
        mealDb?.reset()
        bank.runLearn(g.n, g.dt, g.bir, g.ins, g.cgm, g.cgmObs, g.insApp, g.meal, weight, forget, mealProb, longUpdate,
            mealBio = mealBio, db = mealDb, stepLocalMin = g.stepLocalMin)

        // control off the learned estimator
        val anchor = p.insulinInfusion(p.nowMinute, 15, conv) ?: 0f
        var gdfo = HorizonBuilder.getDataForOptimisation(prof, p.localMinuteOfDay, cfg.controlStep, cfg.dtInt,
            cfg.totalMinutes, cfg.nSteps, floorBase, x14, insulinInfusion = anchor)
        var p9 = 0f; for (i in 0 until 8) p9 += bank.models[i].st[0x34] * bank.models[i].st[0x2c]
        val tau0 = bank.models[0].st[0x2a]
        // HORIZON SIZE (GetRate 0x14540c lines 285-297): shorten by one control step when the occlusion ΔBIR
        // exceeds the smoothed profile (`smoothed < GetDeltaBIR(smoothed)`) ⇒ p1 4→3, p2 7→6, nSteps 6→5.
        val smoothed = gdfo.bir[0]
        val deltaBIR = bank.getDeltaBIR(smoothed)
        val shorten = smoothed < deltaBIR
        val p1 = if (shorten) cfg.p1 - 1 else cfg.p1
        val p2 = if (shorten) cfg.p2 - 1 else cfg.p2
        if (shorten) gdfo = HorizonBuilder.getDataForOptimisation(prof, p.localMinuteOfDay, cfg.controlStep, cfg.dtInt,
            cfg.totalMinutes - cfg.controlStep, cfg.nSteps - 1, floorBase, x14, insulinInfusion = anchor)
        val reducedCgm = Decimate.reduceBg(p.cgm.map { Decimate.C(it.minute, it.a) }, p.nowMinute)
        val cgm3h = reducedCgm.filter { it.t > p.nowMinute - 180 && it.t <= p.nowMinute }
        val meanGlu3h = if (cgm3h.isEmpty()) p9 else cgm3h.map { it.v }.sum() / cgm3h.size
        // finalTarget = the user's TARGET-glucose profile at now (<4.4 or absent ⇒ default 5.8; capped at 11)
        val nowSlot = (p.localMinuteOfDay / 60) * 2 + if (p.localMinuteOfDay % 60 >= 30) 1 else 0
        val profTgt = p.targetProfile[nowSlot].let { if (it < 0f) 5.8f else { val c = minOf(it, 11f); if (c < 4.4f) 5.8f else c } }
        // FastingAndGlucoseStable (0x143670): glucose over the last 4h stayed in [finalTarget−2, finalTarget+1.5]
        val cgm4h = reducedCgm.filter { it.t > p.nowMinute - 240 && it.t <= p.nowMinute }
        val fasting = cgm4h.isNotEmpty() && cgm4h.minOf { it.v } >= profTgt - 2f && cgm4h.maxOf { it.v } <= profTgt + 1.5f
        val offset = if (fasting) -0.4f else 0f
        // mealActive = ModelIMM1::LastMealActive (0x161484): Σ μ_i·(st_i[0xb]+st_i[0xd]) ≥ 0.015 — the gut
        // meal-on-board state, NOT "any meal in the last N h". Gates the optimiser's first-block move-penalty.
        var mealSum = 0f; for (i in 0 until 8) mealSum += bank.models[i].st[0x34] * (bank.models[i].st[0xb] + bank.models[i].st[0xd])
        val mealActive = if (mealSum >= 0.015f) 1 else 0
        val predSD = bank.getPredGlucSD()             // ModelIMM1::GetPredGlucSD → target += ½·predSD
        val progGlu = bank.progressGlucose(gdfo.bir[0], gdfo.dt[0], weight)   // ProgressModel 2-min prediction (trend brake)
        val target = Optimiser.targetGlucose(profTgt, offset, meanGlu3h, p9, predSD, 0f, fastingStable = fasting)
        val optBir = bank.getModifiedBIR(gdfo.bir[0])   // move-penalty BIR = GetModifiedBIR (adapted), not raw profile bir[0]
        val rollTrans = ImmBank.buildTransition(gdfo.dt[1])
        // A meal entered just before `now` is stored +10 min → lands AFTER now, so the estimation (GDFL ends at now)
        // skips it but the optimiser rollout plans for it: GetMeal per forward step (window (t_k−dt_k, t_k]), plus a
        // matching insApp term (the meal announcement), plus GetRunningMealBio/Prob (the DB) for the per-submodel mode.
        val gdfoLocalMin = IntArray(gdfo.len)
        val gdfoMeal = FloatArray(gdfo.len).also { arr ->
            var acc = p.nowMinute - cfg.dtInt.toLong()
            for (k in 0 until gdfo.len) {
                acc += gdfo.dt[k].toLong()
                gdfoLocalMin[k] = ((acc % 1440).toInt() + 1440) % 1440
                var m = 0f; for (r in p.meals) { val mt = r.minute + 10L; if (mt > acc - gdfo.dt[k].toLong() && mt <= acc) m += r.a.toInt().toFloat() }
                arr[k] = m
            }
        }
        val gdfoInsApp = FloatArray(gdfo.len) { gdfo.insApp[it] + gdfoMeal[it] }   // the meal-announcement insulin term
        val runPfo: (FloatArray) -> FloatArray = { u ->
            val fresh = Array(8) { bank.models[it].copy() }
            ImmBank(fresh, rollTrans).predictForOptimise(p2, Optimiser.adviceToInsulinInfusion(u, p1, p2, gdfo.ins),
                gdfo.bir, gdfo.dt, gdfoInsApp, gdfo.cgm, gdfo.cgmObs, gdfoMeal, weight, db = mealDb, stepLocalMin = gdfoLocalMin)
        }
        // startGlucose = GetCGMapproximate(now − predictLead) on the DECIMATED CGM DB (DetermineSetPoint seed)
        val startGlucose = Decimate.getCgmApproximate(reducedCgm, p.nowMinute - 30, target)
        // Dynamic move-penalty λ (GetRate 0x14540c lines 298-302): the low-glucose ticks get a DOUBLED penalty
        // (3.2/2.4 vs 1.6/1.2) that pulls the first control block toward the anchor. The condition is
        // GetStartingGlucose (= p9, vtable+0x80 = 0x15f95c) ≤ targetGlucoseOffset + 6.5.
        val lowGlu = p9 <= offset + 6.5f
        val lambdaBase = if (lowGlu) 3.2f else 1.6f
        val lambdaBaseMeal = if (lowGlu) 2.4f else 1.2f
        val solve = runControlSolve(gdfo.bir, gdfo.dt, startGlucose = startGlucose,
            target = target, predictLead = 30f, p1 = p1, p2 = p2, bolus = cfg.bolus, p9 = p9, w24 = floorBase,
            w2c = x14, optBir = optBir, tau0 = tau0, mealActive = mealActive, anchor = anchor, conv = conv, runPfo = runPfo,
            lambdaBase = lambdaBase, lambdaBaseMeal = lambdaBaseMeal)
        // OUTPUT PIPELINE — the safety modifiers, in the binary's GetRate order (0x14540c lines 315-324), applied
        // to the raw optimiser rate (insulin units) before the 0.05-grid rounding. All are no-ops on benign
        // glucose/trend; only the trend brake fires on the current tick set. Deferred (need signals not yet
        // derived, and dormant here): #1 cap (calcTDD/exact glucose), #4 occlusion ΔBIR, #7 hypo-rescue min-BG.
        val block = if (cfg.bolus == 0) 1 else 0
        var r = solve.optimisedU[block]
        val prevCgm = reducedCgm.lastOrNull()?.let { if (p.nowMinute - it.t <= 90) it.v else 5.5f } ?: 5.5f
        val modGlu = minOf(p9, prevCgm)                                   // min(estGlu, prevCGM) — level/exercise/brake glucose
        val roc = (progGlu - p9) * 30f
        val recentMeal = p.meals.any { it.minute + 10 > p.nowMinute - 60 && it.minute + 10 <= p.nowMinute }
        val exerciseActive = p.exercise.any { it.minute <= p.nowMinute + 60 && it.minute + it.a.toInt() >= p.nowMinute - 60 }
        val birPumpNow = Optimiser.getBIRpump(prof, p.localMinuteOfDay / 60, p.localMinuteOfDay % 60)
        val recentContinuous = reducedCgm.any { it.t > p.nowMinute - 20 && it.t <= p.nowMinute } &&
                               reducedCgm.any { it.t > p.nowMinute - 50 && it.t <= p.nowMinute - 20 }
        val cgmWithin180 = reducedCgm.any { it.t > p.nowMinute - 180 && it.t <= p.nowMinute }
        // min glucose over windows for the hypo-rescue (decimated CGM as both CGM and BG source)
        fun minGlu(win: Int) = reducedCgm.filter { it.t > p.nowMinute - win && it.t <= p.nowMinute }.minOfOrNull { it.v } ?: 999f
        val (mn2h, mn1h, mn30, mn18) = listOf(minGlu(120), minGlu(60), minGlu(30), minGlu(18))
        r = OutputPipeline.modifyMaximumPersonalRange(r, p9, prof, c, c, birPumpNow, 0f, tau0)   // #1 max-rate cap
        r = OutputPipeline.modifyRateGlucoseLevel(r, modGlu, profTgt, recentMeal)          // #2 suspend on low
        r = OutputPipeline.modifyRateGlucoseRate(r, roc, modGlu, false, 0f)                // #3 trend brake
        // #4 occlusion guard: clamp to profile when even an occluded pump would let IOB crash glucose < 3.9
        val occBir = FloatArray(11) { k -> HorizonBuilder.birStep(prof, (((p.nowMinute + k * 25) % 1440 + 1440) % 1440).toInt(), c, x14) }
        val lowestBG = bank.lowestBGIfOcclusion(occBir, weight)
        r = OutputPipeline.modifyRateDeltaBIR(r, gdfo.bir[0], deltaBIR, p9, lowestBG, mealActive == 1)
        r = OutputPipeline.modifyExercise(r, modGlu, exerciseActive)                       // #5 exercise suspend
        r = OutputPipeline.modifyEnoughGlucoseMeasurements(r, birPumpNow, recentContinuous, cgmWithin180)  // #6 staleness
        r = OutputPipeline.rescueCarbReduction(r, mn2h, mn18, mn2h, mn1h, mn18, mn30, tau0, birPumpNow)     // #7 hypo rescue
        var deliveredRate = OutputPipeline.finalRate(r, conv)
        // MIN-RATE BUMP (GetRate 0x14540c lines 335-350): if the recent 68-min infusion is near-zero AND the
        // rate would be ~0, deliver the 0.2 U/h minimum trickle (avoids indefinite total suspension). Gated on
        // the TDD scale c (this+0xc) ≥ 30.
        val inf68 = p.insulinInfusion(p.nowMinute, 68, conv) ?: 0f
        val infThresh = if (c >= 30f) 0.02f else 0.01f
        val bumpThresh = if (c >= 30f) 0.05f else 0.04f
        if (inf68 < infThresh && deliveredRate <= bumpThresh) deliveredRate = 0.2f
        return TickResult(deliveredRate, conv, c, x14, target, g.n, solve.rawRate,
            p9 = p9, tau0 = tau0, w24 = floorBase, optBir = optBir, anchor = anchor, startG = startGlucose,
            e = solve.setpoint, u = solve.optimisedU, u0 = solve.warmStart, rollout0 = solve.rollout0,
            progGlu = progGlu, predSD = predSD, occLowestBG = lowestBG, occDeltaBIR = deltaBIR,
            predEventualMmol = solve.predEventual)
    }
}

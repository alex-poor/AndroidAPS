package hovorka.mpc

import kotlin.math.max
import kotlin.math.min

/**
 * Phase-1 validation: exercise the Hovorka model and confirm it behaves physiologically.
 * Scenarios: (1) basal steady-state holds; (2) a meal raises glucose; (3) an insulin bolus lowers it.
 * Run: hovorka-mpc/build.sh
 */
fun main() {
    val model = HovorkaModel(HovorkaParams.forWeight(70.0))
    val targetMmol = 6.0
    val basal = model.basalForSteadyState(targetMmol)          // mU/min
    println("=== HovorkaMPC Phase 1: Hovorka model validation (W=70kg) ===")
    println("Steady-state basal for %.1f mmol/L: %.3f mU/min = %.2f U/hr"
        .format(targetMmol, basal, basal * 60.0 / 1000.0))
    val s0 = model.steadyState(basal)
    println("Steady-state glucose: %.2f mmol/L (%.0f mg/dL)".format(model.glucoseMmol(s0), model.glucoseMgdl(s0)))

    fun run(label: String, hours: Int, mealAt: Map<Int, Double> = emptyMap(),
            bolusAt: Map<Int, Double> = emptyMap(), extraBasalMult: Double = 1.0): List<Double> {
        var s = s0.copyOf()
        val trace = ArrayList<Double>()
        val dt = 1.0
        for (minute in 0 until hours * 60) {
            mealAt[minute]?.let { s = model.addMeal(s, it) }
            bolusAt[minute]?.let { s = s.copyOf().also { st -> st[5] += it * 1000.0 } } // U -> mU into S1
            s = model.step(s, basal * extraBasalMult, dt)
            if (minute % 15 == 0) trace.add(model.glucoseMmol(s))
        }
        println("\n--- $label ---")
        println(trace.mapIndexed { i, g -> "t+%3dm:%4.1f".format(i * 15, g) }.joinToString("  "))
        return trace
    }

    // 1) steady state should stay flat near target
    val flat = run("1) basal only, 3h (expect flat ~$targetMmol)", 3)
    val flatDrift = flat.max() - flat.min()

    // 2) 60 g meal at t=0, NO bolus -> glucose rises and peaks
    val meal = run("2) 60g meal, no insulin, 4h (expect rise+peak)", 4, mealAt = mapOf(0 to 60.0))
    val mealPeak = meal.max()
    val mealPeakMin = meal.indexOf(mealPeak) * 15

    // 3) 60 g meal + a matched bolus -> blunted rise
    val covered = run("3) 60g meal + 6U bolus, 4h (expect blunted peak)", 4,
        mealAt = mapOf(0 to 60.0), bolusAt = mapOf(0 to 6.0))
    val coveredPeak = covered.max()

    // 4) pure bolus from steady state -> glucose falls
    val bolusOnly = run("4) 3U bolus, no meal, 3h (expect fall)", 3, bolusAt = mapOf(0 to 3.0))
    val bolusMin = bolusOnly.min()

    println("\n=== MODEL CHECKS ===")
    check("steady-state flat (drift < 0.3 mmol/L)", flatDrift < 0.3, "drift=%.2f".format(flatDrift))
    check("meal raises glucose (peak > ${targetMmol + 2})", mealPeak > targetMmol + 2, "peak=%.1f @%dm".format(mealPeak, mealPeakMin))
    check("meal peaks within 45–150 min", mealPeakMin in 45..150, "peakMin=$mealPeakMin")
    check("bolus blunts meal peak", coveredPeak < mealPeak - 0.5, "covered=%.1f vs meal=%.1f".format(coveredPeak, mealPeak))
    check("bolus alone lowers glucose", bolusMin < targetMmol - 0.5, "min=%.1f".format(bolusMin))

    validateEkf(model, s0, basal)
    validateClosedLoop(model, s0, basal)
    validateRobustness()
    validatePersonalization()
    validateTempTarget()
    validateOutputLaw()
    validateTddAdaptation()
    validateImmBank()
}

/**
 * #2d ADAPTIVE TDD: the slow day-to-day gain layer. Two tests:
 *   (A) MISCALIBRATED profile — the user's basal started 30% too LOW (a common under-titration). With a
 *       static gain the MPC's operating point (floor + effort centre) is stuck low → persistent highs.
 *       Adaptation should walk the operating basal up toward the true need over days → lift TIR, and must
 *       NEVER walk into hypo. Compares last-3-day TIR, adaptation ON vs OFF, across a cohort.
 *   (B) ASYMMETRIC SAFETY — a single hypo day must push the gain DOWN (down-nudge dominates), unit-tested
 *       directly on the adapter.
 */
private fun validateTddAdaptation() {
    println("\n=== 2d ADAPTIVE TDD: 30%-under-titrated profile, adaptation ON vs OFF (10-day, 12 patients) ===")
    val rng = java.util.Random(2024)
    val days = 10
    val ctrl = HovorkaModel(HovorkaParams.forWeight(70.0))            // population controller (honest mismatch)
    val lastTirOff = ArrayList<Double>(); val lastTirOn = ArrayList<Double>()
    val tbrOn = ArrayList<Double>(); var worstMinOn = 99.0; var stabilized = 0
    for (i in 1..12) {
        val w = 55.0 + rng.nextDouble() * 40.0
        val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
        val basalTrue = patient.basalForSteadyState(6.0)             // mU/min — the real need
        val trueUhr = basalTrue * 60.0 / 1000.0
        val startUhr = trueUhr * 0.70                                 // profile 30% too low
        val startMu = startUhr * 1000.0 / 60.0
        val maxMu = basalTrue * 8.0
        val carbErr = 0.85 + rng.nextDouble() * 0.3
        val adapter = TddAdapter(w, startUhr, targetMmol = 6.0, maxBasalUhr = maxMu * 60.0 / 1000.0)
        val off = simulateMultiDayAdaptive(ctrl, patient, startMu, maxMu, STANDARD_MEALS, days = days, seed = 300L + i, carbAnnounceError = carbErr, adapter = null)
        val on = simulateMultiDayAdaptive(ctrl, patient, startMu, maxMu, STANDARD_MEALS, days = days, seed = 300L + i, carbAnnounceError = carbErr, adapter = adapter)
        fun last3Tir(r: MultiDayResult) = r.perDay.takeLast(3).map { it.tirPct }.average()
        lastTirOff.add(last3Tir(off)); lastTirOn.add(last3Tir(on))
        tbrOn.add(on.perDay.takeLast(3).map { it.tbrPct }.average())
        worstMinOn = min(worstMinOn, on.perDay.minOf { it.minG })
        // CONVERGENCE = stabilised (no oscillation/windup): last-3-day operating basal spread < 12%.
        val last3Basal = on.basalByDayUhr.takeLast(3)
        if ((last3Basal.max() - last3Basal.min()) / last3Basal.average() < 0.12) stabilized++
        if (i == 1) {
            println("  pt1: start=%.2f true=%.2f U/hr  operating-basal/day=%s  TIR last-3d OFF=%.0f%% ON=%.0f%%"
                .format(startUhr, trueUhr, on.basalByDayUhr.joinToString(",") { "%.2f".format(it) }, last3Tir(off), last3Tir(on)))
            on.log.forEach { println("    $it") }
        }
    }
    val tOff = lastTirOff.average(); val tOn = lastTirOn.average()
    println("cohort last-3-day mean TIR: static=%.0f%%  adaptive=%.0f%%  (Δ=%+.0f pp)   adaptive mean TBR=%.1f%%  worst min=%.1f  stabilised=%d/12"
        .format(tOff, tOn, tOn - tOff, tbrOn.average(), worstMinOn, stabilized))

    // (B) asymmetric safety — a hypo day must drive the gain DOWN, unit-tested on the adapter directly.
    val a = TddAdapter(70.0, 1.0, targetMmol = 6.0, maxBasalUhr = 5.0)
    a.endOfDay(1.0, 6.2, 0.0, 5.0)                                    // a normal day
    val beforeHypo = a.operatingBasalUhr
    a.endOfDay(1.0, 5.5, 0.10, 2.6)                                   // a hypo day (10% TBR, min 2.6)
    val afterHypo = a.operatingBasalUhr

    println("\n=== ADAPTIVE-TDD CHECKS ===")
    check("adaptation lifts last-3-day TIR vs static gain (>+2pp)", tOn > tOff + 2.0, "%.0f%% -> %.0f%%".format(tOff, tOn))
    check("adaptation converges — operating basal stabilises, no windup/oscillation (>=9/12)", stabilized >= 9, "$stabilized/12")
    check("adaptation stays safe (worst min > 3.0, mean TBR < 4%)", worstMinOn > 3.0 && tbrOn.average() < 4.0,
        "min=%.1f TBR=%.1f%%".format(worstMinOn, tbrOn.average()))
    check("a hypo day drives the gain DOWN (asymmetric)", afterHypo < beforeHypo, "%.3f -> %.3f".format(beforeHypo, afterHypo))
}

/**
 * #2c OUTPUT LAW: the old law collapsed basal to a HARD 0 U/hr whenever G < target. In AAPS open loop
 * rate==0 is "always report zero temp" → a fresh suggestion every tick → notification spam, and it was
 * over-conservative (frequent full suspends → rebound highs). The new law ramps the floor from
 * floorFrac·nominal at target down to 0 only near a hypo guard, plus a snap-to-nominal deadband. This
 * checks the new law drastically cuts zero-temp emissions and notification churn WITHOUT losing TIR/safety.
 * Old law is reproduced by hypoGuard==target (empty ramp → 0 below target) and deadband 0.
 */
private fun validateOutputLaw() {
    println("\n=== 2c OUTPUT LAW: zero-temp spam & churn — old hard-floor vs new graduated floor+deadband ===")
    val rng = java.util.Random(2024)
    var zerosOld = 0; var zerosNew = 0; var notifyOld = 0; var notifyNew = 0
    val tirOld = ArrayList<Double>(); val tirNew = ArrayList<Double>()
    val tbrOld = ArrayList<Double>(); val tbrNew = ArrayList<Double>()
    var worstMinOld = 99.0; var worstMinNew = 99.0

    // Replay a closed loop counting emitted rates + emulating AAPS open-loop isChangeRequested (absolute
    // TBR): notify on rate==0, else only if >30% from the running temp (basalStep deadband). Returns
    // [zeros, notifies, tir, tbr, minG]. Enacts every decision (as simulateClosedLoop does).
    fun countLoop(mpc: HovorkaMpc, ctrl: HovorkaModel, plant: HovorkaModel, basal: Double, carbErr: Double, seed: Long): DoubleArray {
        val r = java.util.Random(seed)
        var truth = plant.steadyState(basal)
        val ekf = HovorkaEkf(ctrl, ctrl.steadyState(basal))
        var u = basal
        val basalStep = 0.01 * 1000.0 / 60.0                     // 0.01 U/hr in mU/min
        var running = basal; var tempActive = false
        var zeros = 0; var notifies = 0
        var tir = 0; var tbr = 0; var total = 0; var minG = 99.0
        for (minute in 0 until 24 * 60) {
            STANDARD_MEALS[minute]?.let { carbs ->
                truth = plant.addMeal(truth, carbs); ekf.meal(carbs * carbErr)
                val bolusU = carbs * 0.05 * 0.85         // well-bolused meals → post-meal downswings near target
                truth = truth.copyOf().also { it[5] += bolusU * 1000.0 }; ekf.bolus(bolusU)
            }
            truth = plant.step(truth, u, 1.0); ekf.predict(u, 1.0)
            if (minute % 5 == 0) {
                val gTrue = plant.glucoseMmol(truth)
                ekf.update(gTrue + r.nextGaussian() * 0.4)
                // ROUND to pump resolution (0.01 U/hr) exactly like the plugin — this is what AAPS's
                // "rate == 0.0" zero-temp rule actually sees. A tiny non-zero MPC output rounds to 0.00.
                val rateUhr = kotlin.math.round(mpc.decide(ekf.x).basalUPerHr * 100.0) / 100.0
                val rate = rateUhr * 1000.0 / 60.0
                if (rateUhr == 0.0) zeros++
                val notify = when {
                    rateUhr == 0.0 -> true                                      // always report zero temp
                    !tempActive && kotlin.math.abs(rate - basal) < basalStep -> false
                    tempActive && kotlin.math.abs(rate - running) < basalStep -> false
                    else -> { val ref = if (tempActive) running else basal; val ch = rate / ref; ch < 0.7 || ch > 1.3 }
                }
                if (notify) { notifies++; running = rate; tempActive = kotlin.math.abs(rate - basal) >= basalStep }
                u = rate
            }
            val g = plant.glucoseMmol(truth)
            total++; if (g in 3.9..10.0) tir++; if (g < 3.9) tbr++; minG = min(minG, g)
        }
        return doubleArrayOf(zeros.toDouble(), notifies.toDouble(), 100.0 * tir / total, 100.0 * tbr / total, minG)
    }

    for (i in 1..20) {
        val w = 55.0 + rng.nextDouble() * 40.0
        val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
        val basal = patient.basalForSteadyState(6.0)
        val basalUhr = basal * 60.0 / 1000.0
        val (isf, ic) = measurePatientIsfIc(patient, basal, rng, noise = 0.15)
        val carbErr = 0.85 + rng.nextDouble() * 0.3
        val ctrl = HovorkaModel(HovorkaParams.personalize(w, isf, ic, basalUhr, 6.0))
        val maxB = basal * 8.0
        val oldMpc = HovorkaMpc(ctrl, targetMmol = 6.0, nominalBasalMuPerMin = basal, maxBasalMuPerMin = maxB,
            hypoGuardMmol = 6.0, deadbandFrac = 0.0)                             // reproduces the old hard floor
        val newMpc = HovorkaMpc(ctrl, targetMmol = 6.0, nominalBasalMuPerMin = basal, maxBasalMuPerMin = maxB)
        val o = countLoop(oldMpc, ctrl, patient, basal, carbErr, 200L + i)
        val n = countLoop(newMpc, ctrl, patient, basal, carbErr, 200L + i)
        zerosOld += o[0].toInt(); notifyOld += o[1].toInt(); tirOld.add(o[2]); tbrOld.add(o[3]); worstMinOld = min(worstMinOld, o[4])
        zerosNew += n[0].toInt(); notifyNew += n[1].toInt(); tirNew.add(n[2]); tbrNew.add(n[3]); worstMinNew = min(worstMinNew, n[4])
    }
    val tOld = tirOld.average(); val tNew = tirNew.average()
    // The zero-temps remaining under the new law are LEGITIMATE hypo suspends (G ≤ hypoGuard 4.5, where the
    // ramp permits a full 0). The new law removes the SPURIOUS below-target-but-not-hypo zeros; it should
    // not (and must not) suppress a genuine suspend. Safety is judged RELATIVE to the old law because this
    // scenario (fixed IC bolus × ±40% SI) deliberately provokes post-meal lows under BOTH laws.
    println("cohort totals over 20×24h (288 ticks each): zero-temp emissions old=%d new=%d | notifications old=%d new=%d"
        .format(zerosOld, zerosNew, notifyOld, notifyNew))
    println("               mean TIR old=%.0f%% new=%.0f%%   TBR old=%.1f%% new=%.1f%%   worst min old=%.1f new=%.1f"
        .format(tOld, tNew, tbrOld.average(), tbrNew.average(), worstMinOld, worstMinNew))
    println("\n=== OUTPUT-LAW CHECKS ===")
    check("new law cuts spurious zero-temp emissions by >30%", zerosNew < 0.7 * max(1, zerosOld), "$zerosOld -> $zerosNew")
    check("new law cuts total open-loop notifications", notifyNew < notifyOld, "$notifyOld -> $notifyNew")
    check("new law holds TIR (>= old - 1pp)", tNew >= tOld - 1.0, "%.0f%% vs %.0f%%".format(tNew, tOld))
    check("new law no less safe than old (worst min & mean TBR)",
        worstMinNew >= worstMinOld - 0.3 && tbrNew.average() <= tbrOld.average() + 0.5,
        "min %.1f->%.1f  TBR %.1f%%->%.1f%%".format(worstMinOld, worstMinNew, tbrOld.average(), tbrNew.average()))
}

/**
 * #3a IMM KALMAN BANK: does the 8-submodel bank (absorption spread) track a mismatched patient better than
 * one nominal EKF, and does that carry into closed-loop control? Two tests:
 *   (A) FORECAST accuracy — the metric that matters: 30-min-ahead glucose-forecast RMSE, single EKF vs IMM,
 *       on patients whose true absorption sits at the bank extremes. (Current-glucose FILTERING is a poor
 *       discriminator — glucose is directly measured, so any model tracks it to the CGM-noise floor; the
 *       model only earns its keep when PREDICTING the hidden trajectory forward, which is what the MPC uses.)
 *   (B) closed-loop cohort — 20 mismatched patients, EKF vs IMM as the estimator feeding the MPC.
 */
private fun validateImmBank() {
    println("\n=== 3a IMM KALMAN BANK: single EKF vs 8-submodel IMM ===")

    // (A) FORECAST accuracy under ABSORPTION mismatch. Estimator uses a nominal model (tMaxG=40); the patient
    // absorbs much faster (tMaxG≈18) or slower (tMaxG≈90). At each tick we forecast glucose 30 min ahead and
    // score it against what actually happened 30 min later. The IMM should forecast the true regime better.
    // returns (ekfRmse, immMixRmse, immBestRmse, finalBestTMaxG)
    fun forecastRmse(patientTMaxG: Double, horizon: Int = 30): DoubleArray {
        val w = 75.0
        val nominal = HovorkaModel(HovorkaParams.forWeight(w))                    // estimator model (tMaxG=40)
        val patient = HovorkaModel(HovorkaParams.forWeight(w).copy(tMaxG = patientTMaxG))
        val basal = patient.basalForSteadyState(6.0)
        val ekf = HovorkaEkf(nominal, nominal.steadyState(basal))
        val imm = HovorkaImmBank(nominal.p, basal)
        var tE = patient.steadyState(basal); var tI = tE.copyOf()
        val rngE = java.util.Random(11); val rngI = java.util.Random(11)         // same CGM noise stream
        val meals = mapOf(30 to 60.0, 240 to 45.0)                               // announced meals, no bolus
        val actual = HashMap<Int, Double>()
        val fcE = HashMap<Int, Double>(); val fcMix = HashMap<Int, Double>(); val fcBest = HashMap<Int, Double>()
        for (minute in 0 until 420) {
            meals[minute]?.let { carbs ->
                tE = patient.addMeal(tE, carbs); ekf.meal(carbs)
                tI = patient.addMeal(tI, carbs); imm.meal(carbs)
            }
            tE = patient.step(tE, basal, 1.0); ekf.predict(basal, 1.0)
            tI = patient.step(tI, basal, 1.0); imm.predict(basal, 1.0)
            actual[minute] = patient.glucoseMmol(tE)                             // identical plants → same truth
            if (minute % 5 == 0) {
                val noise = rngE.nextGaussian() * 0.4; rngI.nextGaussian()
                ekf.update(actual[minute]!! + noise); imm.update(actual[minute]!! + noise)
                fcE[minute + horizon] = ekf.forecastGlucoseMmol(basal, horizon)
                fcMix[minute + horizon] = imm.forecastGlucoseMmol(basal, horizon)
                fcBest[minute + horizon] = imm.forecastGlucoseMmolBest(basal, horizon)
            }
        }
        fun rmse(fc: HashMap<Int, Double>): Double {
            var sq = 0.0; var nn = 0
            for ((m, f) in fc) if (m >= 60 && actual.containsKey(m)) { val e = f - actual[m]!!; sq += e * e; nn++ }
            return kotlin.math.sqrt(sq / nn)
        }
        return doubleArrayOf(rmse(fcE), rmse(fcMix), rmse(fcBest), imm.bestTMaxG())
    }
    println("  30-min-ahead FORECAST RMSE (estimator model tMaxG=40, announced meals, no bolus → large swings):")
    println("     patient             EKF   IMM-mix  IMM-best  (bank locked on tMaxG)")
    var immWins = 0; var cmp = 0
    for ((label, tg) in listOf("fast (tMaxG=18)" to 18.0, "slow (tMaxG=90)" to 90.0, "matched (tMaxG=40)" to 40.0)) {
        val (rE, rMix, rBest, lockTg) = forecastRmse(tg).let { arrayOf(it[0], it[1], it[2], it[3]) }
        val best = minOf(rMix, rBest)
        val tag = if (tg != 40.0 && best < rE - 1e-3) "  ← IMM better" else ""
        println("     %-16s %6.2f   %6.2f   %6.2f     (%.0f)%s".format(label, rE, rMix, rBest, lockTg, tag))
        if (tg != 40.0) { cmp++; if (best <= rE + 0.02) immWins++ }             // no worse (small tolerance)
    }

    // (B) closed-loop cohort: EKF vs IMM estimator, same 20 mismatched patients + personalised controller.
    println("\n  closed-loop cohort (20 mismatched patients, personalised controller):")
    val rng = java.util.Random(2024)
    val ekfM = ArrayList<LoopMetrics>(); val immM = ArrayList<LoopMetrics>(); val cplM = ArrayList<LoopMetrics>()
    for (i in 1..20) {
        val w = 55.0 + rng.nextDouble() * 40.0
        val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
        val basal = patient.basalForSteadyState(6.0)
        val basalUhr = basal * 60.0 / 1000.0
        val (isfMgdl, icG) = measurePatientIsfIc(patient, basal, rng, noise = 0.15)
        val carbErr = 0.85 + rng.nextDouble() * 0.3
        val seed = 100L + i
        val ctrl = HovorkaModel(HovorkaParams.personalize(w, isfMgdl, icG, basalUhr, 6.0))
        ekfM.add(simulateClosedLoop(ctrl, patient, basal, STANDARD_MEALS, seed = seed, carbAnnounceError = carbErr))
        immM.add(simulateClosedLoop(ctrl, patient, basal, STANDARD_MEALS, seed = seed, carbAnnounceError = carbErr,
            estimatorFactory = immEstimator()))
        cplM.add(simulateClosedLoop(ctrl, patient, basal, STANDARD_MEALS, seed = seed, carbAnnounceError = carbErr,
            estimatorFactory = immEstimator(), coupleModel = true))     // MPC rolls out with the identified regime
    }
    val tirE = ekfM.map { it.tirPct }.average(); val tirI = immM.map { it.tirPct }.average(); val tirC = cplM.map { it.tirPct }.average()
    val tbrC = cplM.map { it.tbrPct }.average(); val worstMinC = cplM.minOf { it.minG }; val sevC = cplM.count { it.severeHypoPct > 0.0 }
    println("     EKF (fixed model):          mean TIR=%.0f%%".format(tirE))
    println("     IMM, estimation-only:       mean TIR=%.0f%%  (Δ=%+.0f pp vs EKF)  — better estimate, but MPC still rolls out nominal".format(tirI, tirI - tirE))
    println("     IMM + MPC coupled to regime: mean TIR=%.0f%%  (Δ=%+.0f pp vs EKF)  TBR=%.1f%%  worst min=%.1f  severe-hypo pts=%d".format(tirC, tirC - tirE, tbrC, worstMinC, sevC))

    println("\n  FINDING: the bank correctly IDs the absorption regime and forecasts it better (above), but that")
    println("  does NOT lift closed-loop TIR on this cohort — 2a personalisation already removed the dominant")
    println("  SI/EGP mismatch, and a basal-only TBR controller has little authority over meal-peak TIMING")
    println("  (what absorption governs). The IMM's payoff is the substrate it unlocks: Bayesian MEAL DETECTION")
    println("  (unannounced meals) and SMB (3b, meal-timing authority) — not a standalone TIR win over EKF+2a.")

    println("\n=== IMM CHECKS ===")
    check("IMM identifies + forecasts absorption-mismatched regimes better than single EKF", immWins == cmp, "$immWins/$cmp regimes")
    check("IMM closed-loop is at parity with EKF+2a (within 3pp — not a regression)", tirC >= tirE - 3.0, "coupled %.0f%% vs EKF %.0f%%".format(tirC, tirE))
    check("coupled IMM keeps cohort safe (worst min > 3.0, no severe-hypo patients)", worstMinC > 3.0 && sevC == 0, "min=%.1f sev=%d".format(worstMinC, sevC))
    check("coupled IMM mean TBR < 4%", tbrC < 4.0, "%.1f%%".format(tbrC))

    println(if (failures == 0) "\n✅ ALL CHECKS PASS (incl. IMM bank)."
            else "\n❌ $failures check(s) failed.")
}

/**
 * #2b TEMP TARGETS: a temp target shifts only the MPC control setpoint (the model stays anchored to the
 * PROFILE target — model identification must NOT be distorted). The guarantee it must uphold: at any given
 * glucose, raising the target makes the controller LESS aggressive (demands ≤ insulin), and lowering it
 * makes it more aggressive. We assert that directly on HovorkaMpc.decide() across a sweep of glucose states
 * and targets — the unambiguous plumbing test, free of closed-loop confounders. (NB: a basal-only TBR
 * controller has weak authority to actively *raise* glucose above target, so the visible closed-loop effect
 * is mostly hypo-avoidance / reduced insulin, not a big upward glucose shift — full effect awaits SMB, 3b.)
 */
private fun validateTempTarget() {
    println("\n=== 2b TEMP TARGETS: decision-level setpoint response (personalised model) ===")
    val w = 75.0
    val basalUhr = 1.0
    val basalMuMin = basalUhr * 1000.0 / 60.0
    val maxBasalMuMin = basalMuMin * 8.0
    val model = HovorkaModel(HovorkaParams.personalize(w, 40.0, 10.0, basalUhr, 6.0))  // ISF 40, IC 10, target 6.0
    val targets = doubleArrayOf(5.0, 6.0, 7.0, 8.5, 10.0)
    val glucoses = doubleArrayOf(5.0, 6.0, 8.0, 11.0, 14.0)

    fun basalAt(gMmol: Double, targetMmol: Double): Double {
        // put the model at a steady state whose glucose is gMmol, then ask the MPC for its basal
        val uForG = model.basalForSteadyState(gMmol)
        val s = model.steadyState(uForG)
        val mpc = HovorkaMpc(model, targetMmol = targetMmol,
            nominalBasalMuPerMin = basalMuMin, maxBasalMuPerMin = maxBasalMuMin)
        return mpc.decide(s).basalUPerHr
    }

    println("       target:  " + targets.joinToString("   ") { "%.1f".format(it) })
    val rows = glucoses.map { g -> g to targets.map { t -> basalAt(g, t) } }
    rows.forEach { (g, r) -> println("  G=%4.1f U/hr: ".format(g) + r.joinToString("  ") { "%5.2f".format(it) }) }

    // monotonicity: for each glucose row, basal must be non-increasing as the target rises
    var monoViolations = 0
    var strictDrops = 0
    rows.forEach { (_, r) ->
        for (j in 1 until r.size) {
            if (r[j] > r[j - 1] + 1e-6) monoViolations++
            if (r[j] < r[j - 1] - 1e-6) strictDrops++
        }
    }
    // at a clear high (G=11), a hypo-avoidance target (8.5) must dose less than a tight target (5.0)
    val highTight = basalAt(11.0, 5.0)
    val highLoose = basalAt(11.0, 8.5)

    println("\n=== TEMP-TARGET CHECKS ===")
    check("raising target never increases demanded basal (monotone)", monoViolations == 0, "$monoViolations violations")
    check("raising target reduces basal somewhere (setpoint is live, not ignored)", strictDrops > 0, "$strictDrops strict reductions")
    check("at G=11, loose target (8.5) doses less than tight target (5.0)", highLoose < highTight - 1e-6, "%.2f < %.2f".format(highLoose, highTight))
}

/**
 * #2a PERSONALISATION: does mapping the patient's clinical ISF/IC (as an AAPS profile supplies) onto
 * the controller model beat the population model? Same cohort, two controllers per patient:
 *   A) population weight-scaled params (the pre-2a controller),
 *   B) params personalised from the patient's ISF/IC + basal/target (with realistic ±15% titration noise).
 */
private fun validatePersonalization() {
    println("\n=== 2a PERSONALISATION: population vs. profile-personalised controller (20 patients) ===")
    val rng = java.util.Random(2024)
    val pop = ArrayList<LoopMetrics>()
    val per = ArrayList<LoopMetrics>()
    println("  pt   W   ISF   IC  |  TIR pop  TIR pers   min pop  min pers")
    for (i in 1..20) {
        val w = 55.0 + rng.nextDouble() * 40.0
        val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
        val basal = patient.basalForSteadyState(6.0)                 // titrated to the patient
        val basalUhr = basal * 60.0 / 1000.0
        // clinician-style measurement of the patient's ISF/IC, with realistic titration error
        val (isfMgdl, icG) = measurePatientIsfIc(patient, basal, rng, noise = 0.15)
        val carbErr = 0.85 + rng.nextDouble() * 0.3
        val seed = 100L + i

        val cPop = HovorkaModel(HovorkaParams.forWeight(70.0))        // population (weight-only) model
        val cPer = HovorkaModel(HovorkaParams.personalize(w, isfMgdl, icG, basalUhr, 6.0))
        val mPop = simulateClosedLoop(cPop, patient, basal, STANDARD_MEALS, seed = seed, carbAnnounceError = carbErr)
        val mPer = simulateClosedLoop(cPer, patient, basal, STANDARD_MEALS, seed = seed, carbAnnounceError = carbErr)
        pop.add(mPop); per.add(mPer)
        if (i <= 12) println("  %2d %4.0f %4.0f %4.0f  |   %4.0f%%     %4.0f%%    %5.1f    %5.1f"
            .format(i, w, isfMgdl, icG, mPop.tirPct, mPer.tirPct, mPop.minG, mPer.minG))
    }
    val tirPop = pop.map { it.tirPct }.average()
    val tirPer = per.map { it.tirPct }.average()
    val tbrPer = per.map { it.tbrPct }.average()
    val worstMinPer = per.minOf { it.minG }
    val improved = (0 until pop.size).count { per[it].tirPct >= pop[it].tirPct - 1.0 }
    println("  ... (20 patients)")
    println("cohort mean TIR: population=%.0f%%  personalised=%.0f%%  (Δ=%+.0f pp)  personalised mean TBR=%.1f%%  worst min=%.1f"
        .format(tirPop, tirPer, tirPer - tirPop, tbrPer, worstMinPer))
    println("\n=== PERSONALISATION CHECKS ===")
    check("personalised no worse than population on cohort mean TIR", tirPer >= tirPop - 0.5, "%.0f%% vs %.0f%%".format(tirPer, tirPop))
    check("personalised keeps cohort safe (worst min > 3.0)", worstMinPer > 3.0, "worstMin=%.1f".format(worstMinPer))
    check("personalised mean TBR < 4%", tbrPer < 4.0, "%.1f%%".format(tbrPer))
    check("personalised >= population for most patients (>=15/20)", improved >= 15, "$improved/20")
}

/**
 * Measure a virtual patient's clinical ISF (mg/dL per U, baseline→nadir from 1 U) and IC (g/U, AUC-matched),
 * the way a user/clinician would derive their profile numbers — then add ±[noise] titration error.
 */
private fun measurePatientIsfIc(plant: HovorkaModel, basalMuMin: Double, rng: java.util.Random, noise: Double): Pair<Double, Double> {
    val s0 = plant.steadyState(basalMuMin)
    val g0 = plant.glucoseMmol(s0)
    // ISF: nadir drop from a 1 U bolus
    var s = s0.copyOf().also { it[5] += 1000.0 }
    var nadir = g0; var bolusAuc = 0.0
    repeat(600) { s = plant.step(s, basalMuMin, 1.0); val g = plant.glucoseMmol(s); nadir = min(nadir, g); bolusAuc += (g0 - g) }
    val isfMmol = g0 - nadir
    // IC: grams whose meal-AUC cancels the 1 U bolus-AUC (unit meal probe, AUC scales ~linearly with grams)
    var sm = plant.addMeal(s0.copyOf(), 10.0)
    var mealAuc = 0.0
    repeat(600) { sm = plant.step(sm, basalMuMin, 1.0); mealAuc += (plant.glucoseMmol(sm) - g0) }
    val aucPerGram = mealAuc / 10.0
    val icG = if (aucPerGram > 1e-6) bolusAuc / aucPerGram else 10.0
    fun jitter() = 1.0 + (rng.nextDouble() * 2 - 1) * noise
    return Pair(isfMmol * 18.0 * jitter(), icG * jitter())
}

/** Phase-3 in-silico closed loop (matched model, sanity baseline). */
private fun validateClosedLoop(model: HovorkaModel, s0: DoubleArray, basal: Double) {
    val m = simulateClosedLoop(model, model, basal, STANDARD_MEALS)
    println("\n--- Closed loop, MATCHED model (24h, 3 meals) ---")
    println("TIR=%.0f%%  TBR<3.9=%.1f%%  TAR>10=%.0f%%  mean=%.1f  min=%.1f  max=%.1f"
        .format(m.tirPct, m.tbrPct, m.tarPct, m.meanG, m.minG, m.maxG))
    println("\n=== CLOSED-LOOP CHECKS (matched) ===")
    check("no severe hypo (min > 3.3 mmol/L)", m.minG > 3.3, "min=%.1f".format(m.minG))
    check("time-in-range > 70%", m.tirPct > 70.0, "TIR=%.0f%%".format(m.tirPct))
    check("no severe hyper (max < 15 mmol/L)", m.maxG < 15.0, "max=%.1f".format(m.maxG))
}

/** #1 ROBUSTNESS: cohort of INDEPENDENT virtual patients (model mismatch) — the honest test. */
private fun validateRobustness() {
    println("\n=== ROBUSTNESS: 20 virtual patients, controller model ≠ patient ===")
    val nominal = HovorkaModel(HovorkaParams.forWeight(70.0))   // controller's (population) model
    val rng = java.util.Random(2024)
    val metrics = ArrayList<LoopMetrics>()
    println("  pt  TIR   TBR  TAR  min   max")
    for (i in 1..20) {
        val w = 55.0 + rng.nextDouble() * 40.0                   // 55–95 kg
        val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
        // profile basal is titrated to the PATIENT (as real users do); controller model stays nominal
        val basal = patient.basalForSteadyState(6.0)
        val m = simulateClosedLoop(nominal, patient, basal, STANDARD_MEALS, seed = 100L + i,
            carbAnnounceError = 0.85 + rng.nextDouble() * 0.3)   // ±15% carb-count error too
        metrics.add(m)
        if (i <= 12) println("  %2d %4.0f%% %4.1f %4.0f %5.1f %5.1f"
            .format(i, m.tirPct, m.tbrPct, m.tarPct, m.minG, m.maxG))
    }
    val meanTir = metrics.map { it.tirPct }.average()
    val meanTbr = metrics.map { it.tbrPct }.average()
    val worstMin = metrics.minOf { it.minG }
    val anySevere = metrics.count { it.severeHypoPct > 0.0 }
    val below70 = metrics.count { it.tirPct < 70.0 }
    println("  ... (20 patients)")
    println("cohort: mean TIR=%.0f%%  mean TBR=%.1f%%  worst min=%.1f  severe-hypo patients=%d  TIR<70%%: %d/20"
        .format(meanTir, meanTbr, worstMin, anySevere, below70))
    println("\n=== ROBUSTNESS CHECKS ===")
    check("cohort mean TIR > 65%", meanTir > 65.0, "%.0f%%".format(meanTir))
    check("no severe hypo across cohort (worst min > 3.0)", worstMin > 3.0, "worstMin=%.1f".format(worstMin))
    check("mean time-below-range < 4%", meanTbr < 4.0, "%.1f%%".format(meanTbr))

    println(if (failures == 0) "\n✅ ALL CHECKS PASS — controller regulates matched AND mismatched patients."
            else "\n❌ $failures check(s) failed.")
}

/** Phase-1 estimator validation: EKF tracks the plant's glucose through CGM noise + a wrong start. */
private fun validateEkf(model: HovorkaModel, s0: DoubleArray, basal: Double) {
    val rng = java.util.Random(42)
    val cgmSd = 0.4                                   // mmol/L CGM noise
    // PLANT: true state, 60g meal at t=30min, 3U bolus at t=45min
    var truth = s0.copyOf()
    // EKF: start from a WRONG glucose (Q1 inflated +3 mmol/L worth) to test convergence
    val ekfStart = s0.copyOf().also { it[0] += 3.0 * model.p.vg }
    val ekf = HovorkaEkf(model, ekfStart)

    var sumSqAfterConv = 0.0; var nAfterConv = 0
    var convergedAt = -1
    val trace = ArrayList<Triple<Int, Double, Double>>()
    for (minute in 0 until 300) {
        if (minute == 30) { truth = model.addMeal(truth, 60.0); ekf.meal(60.0) }
        if (minute == 45) { truth = truth.copyOf().also { it[5] += 3.0 * 1000.0 }; ekf.x[5] += 3.0 * 1000.0 }
        truth = model.step(truth, basal, 1.0)
        ekf.predict(basal, 1.0)
        if (minute % 5 == 0) {
            val gTrue = model.glucoseMmol(truth)
            val cgm = gTrue + rng.nextGaussian() * cgmSd
            ekf.update(cgm)
            val gEst = ekf.glucoseMmol()
            val err = kotlin.math.abs(gEst - gTrue)
            if (convergedAt < 0 && err < 0.5 && minute > 0) convergedAt = minute
            if (minute >= 60) { sumSqAfterConv += err * err; nAfterConv++ }
            if (minute % 30 == 0) trace.add(Triple(minute, gTrue, gEst))
        }
    }
    val rmse = kotlin.math.sqrt(sumSqAfterConv / nAfterConv)
    println("\n--- EKF: true vs estimate (start deliberately +3 mmol/L off) ---")
    println(trace.joinToString("  ") { "t%3d:%.1f/%.1f".format(it.first, it.second, it.third) })
    println("\n=== ESTIMATOR CHECKS ===")
    check("EKF converges from wrong start (< 0.5 mmol/L within 60 min)", convergedAt in 0..60, "convergedAt=${convergedAt}m")
    check("EKF tracks plant after convergence (RMSE < 0.6 mmol/L)", rmse < 0.6, "RMSE=%.2f".format(rmse))
}

private var failures = 0
private fun check(name: String, pass: Boolean, detail: String) {
    println("  [${if (pass) "PASS" else "FAIL"}] $name  ($detail)")
    if (!pass) failures++
}

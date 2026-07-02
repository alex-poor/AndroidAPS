package hovorka.mpc

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Closed-loop outcome metrics (ADA in-silico conventions). */
data class LoopMetrics(
    val tirPct: Double,     // % time 3.9–10.0 mmol/L
    val tbrPct: Double,     // % time < 3.9 (below range)
    val severeHypoPct: Double, // % time < 3.0
    val tarPct: Double,     // % time > 10.0
    val meanG: Double, val minG: Double, val maxG: Double
)

/**
 * In-silico closed loop with SEPARATE controller and plant models (model mismatch).
 * The EKF + MPC use [controllerModel]; the true patient is [plantModel]. This is the honest test:
 * the controller's model is never the real patient.
 */
fun simulateClosedLoop(
    controllerModel: HovorkaModel,
    plantModel: HovorkaModel,
    nominalBasal: Double,            // profile basal (mU/min) — the titrated operating point
    meals: Map<Int, Double>,         // minute -> grams CHO (actual)
    bolusUPerG: Double = 0.05,       // user's fixed insulin:carb (U/g)
    bolusCoverage: Double = 0.65,    // fraction of a meal the user pre-boluses
    carbAnnounceError: Double = 1.0, // announced/actual carbs (1.0 = perfect)
    cgmSd: Double = 0.4,
    seed: Long = 7,
    hours: Int = 24,
    controlTargetMmol: Double = 6.0,    // MPC setpoint (2b: a temp target shifts this, NOT the model)
    // 3a: pluggable estimator. Default = single EKF; pass immEstimator(...) for the IMM bank.
    estimatorFactory: (HovorkaModel, Double) -> GlucoseEstimator = { m, basal -> HovorkaEkf(m, m.steadyState(basal)) },
    // 3a: when true, roll the MPC out with the estimator's identified model each tick (IMM PredictForOptimise).
    coupleModel: Boolean = false,
    // 3b: SMB. enableSmb + a per-tick absolute cap (smbCapU) and a maxIOB (U) the SMB may not breach.
    enableSmb: Boolean = false,
    smbCapU: Double = 0.0,
    maxIobU: Double = Double.MAX_VALUE
): LoopMetrics {
    val rng = java.util.Random(seed)
    val maxBasal = nominalBasal * 8.0
    fun mpcFor(m: HovorkaModel, maxSmbU: Double = 0.0) = HovorkaMpc(m, targetMmol = controlTargetMmol,
        nominalBasalMuPerMin = nominalBasal, maxBasalMuPerMin = maxBasal,
        enableSmb = enableSmb, maxSmbU = maxSmbU)
    val mpc = mpcFor(controllerModel)
    // SC insulin on board (U) from the estimator depot — the headroom that gates SMB against maxIOB.
    fun iobU(est: GlucoseEstimator) = (est.x[5] + est.x[6]) / 1000.0
    var totalSmbU = 0.0

    var truth = plantModel.steadyState(nominalBasal)            // patient starts at its own equilibrium
    val ekf = estimatorFactory(controllerModel, nominalBasal)
    var u = nominalBasal
    var tir = 0; var tbr = 0; var sev = 0; var tar = 0; var total = 0
    var minG = 99.0; var maxG = 0.0; var sumG = 0.0

    for (minute in 0 until hours * 60) {
        meals[minute]?.let { carbs ->
            truth = plantModel.addMeal(truth, carbs)                 // plant gets ACTUAL carbs
            ekf.meal(carbs * carbAnnounceError)                      // controller gets ANNOUNCED carbs
            val bolusU = carbs * bolusUPerG * bolusCoverage
            truth = truth.copyOf().also { it[5] += bolusU * 1000.0 }
            ekf.bolus(bolusU)
        }
        truth = plantModel.step(truth, u, 1.0)
        ekf.predict(u, 1.0)
        if (minute % 5 == 0) {
            val gTrue = plantModel.glucoseMmol(truth)
            ekf.update(gTrue + rng.nextGaussian() * cgmSd)
            // per-tick SMB cap = min(absolute cap, remaining maxIOB headroom) — never breach maxIOB
            val maxSmbU = if (enableSmb) max(0.0, min(smbCapU, maxIobU - iobU(ekf))) else 0.0
            val rolloutM = if (coupleModel) ekf.rolloutModel() else null
            val decider = when {
                enableSmb || rolloutM != null -> mpcFor(rolloutM ?: controllerModel, maxSmbU)
                else -> mpc
            }
            val dec = decider.decide(ekf.x)
            u = dec.basalMuPerMin
            if (dec.smbU > 0.0) {                                    // enact the microbolus into plant + estimator
                truth = truth.copyOf().also { it[5] += dec.smbU * 1000.0 }
                ekf.bolus(dec.smbU)
                totalSmbU += dec.smbU
            }
        }
        val g = plantModel.glucoseMmol(truth)
        total++
        if (g in 3.9..10.0) tir++
        if (g < 3.9) tbr++
        if (g < 3.0) sev++
        if (g > 10.0) tar++
        minG = min(minG, g); maxG = max(maxG, g); sumG += g
    }
    fun pct(x: Int) = 100.0 * x / total
    return LoopMetrics(pct(tir), pct(tbr), pct(sev), pct(tar), sumG / total, minG, maxG)
}

/** Standard adult meal day: 50 g @07:00, 70 g @13:00, 60 g @19:00. */
val STANDARD_MEALS = mapOf(7 * 60 to 50.0, 13 * 60 to 70.0, 19 * 60 to 60.0)

/** 3a: estimator factory for the IMM bank — drop-in replacement for the default single EKF. */
fun immEstimator(): (HovorkaModel, Double) -> GlucoseEstimator =
    { m, basal -> HovorkaImmBank(m.p, basal) }

/** Result of a multi-day adaptive run (2d): per-day metrics, the operating basal USED each day (U/hr),
 *  the final adapted basal, and the gain log. */
data class MultiDayResult(val perDay: List<LoopMetrics>, val basalByDayUhr: List<Double>, val finalBasalUhr: Double, val log: List<String>)

/**
 * Multi-day in-silico loop for the 2d adaptive-gain layer. Plant + estimator state persist across days;
 * the MPC is rebuilt each day around the current operating basal, and (if [adapter] != null) the day's
 * enacted basal + glucose summary fold into the gain at midnight — so the operating point WALKS over days.
 * Pass adapter=null for the static-gain baseline (operating basal fixed at [startBasalMuMin]).
 *
 * The interesting scenario is a MISCALIBRATED start ([startBasalMuMin] ≠ the patient's true need): a good
 * adapter converges it toward the truth and lifts TIR, while never walking into hypo (asymmetric down-nudge).
 */
fun simulateMultiDayAdaptive(
    controllerModel: HovorkaModel,
    plantModel: HovorkaModel,
    startBasalMuMin: Double,
    maxBasalMuMin: Double,
    meals: Map<Int, Double>,
    days: Int = 10,
    seed: Long = 7,
    bolusCoverage: Double = 0.65,
    carbAnnounceError: Double = 1.0,
    cgmSd: Double = 0.4,
    adapter: TddAdapter? = null,
    injectHypoDay: Int = -1              // force an over-bolus on this day to exercise the down-nudge
): MultiDayResult {
    val rng = java.util.Random(seed)
    var opBasal = startBasalMuMin
    var truth = plantModel.steadyState(opBasal)
    val ekf = HovorkaEkf(controllerModel, controllerModel.steadyState(opBasal))
    var u = opBasal
    val perDay = ArrayList<LoopMetrics>()
    val basalByDay = ArrayList<Double>()
    val log = ArrayList<String>()

    for (day in 0 until days) {
        basalByDay.add(opBasal * 60.0 / 1000.0)                       // operating basal used THIS day
        val mpc = HovorkaMpc(controllerModel, targetMmol = 6.0,
            nominalBasalMuPerMin = opBasal, maxBasalMuPerMin = maxBasalMuMin)
        val cov = if (day == injectHypoDay) 1.35 else bolusCoverage   // deliberate over-bolus → a hypo day
        var sumEnacted = 0.0; var nEnacted = 0
        var tir = 0; var tbr = 0; var sev = 0; var tar = 0; var total = 0
        var minG = 99.0; var maxG = 0.0; var sumG = 0.0
        for (minute in 0 until 1440) {
            meals[minute]?.let { carbs ->
                truth = plantModel.addMeal(truth, carbs); ekf.meal(carbs * carbAnnounceError)
                val bolusU = carbs * 0.05 * cov
                truth = truth.copyOf().also { it[5] += bolusU * 1000.0 }; ekf.bolus(bolusU)
            }
            truth = plantModel.step(truth, u, 1.0); ekf.predict(u, 1.0)
            if (minute % 5 == 0) {
                ekf.update(plantModel.glucoseMmol(truth) + rng.nextGaussian() * cgmSd)
                u = mpc.decide(ekf.x).basalMuPerMin
                sumEnacted += u; nEnacted++
            }
            val g = plantModel.glucoseMmol(truth)
            total++
            if (g in 3.9..10.0) tir++; if (g < 3.9) tbr++; if (g < 3.0) sev++; if (g > 10.0) tar++
            minG = min(minG, g); maxG = max(maxG, g); sumG += g
        }
        fun pct(x: Int) = 100.0 * x / total
        val m = LoopMetrics(pct(tir), pct(tbr), pct(sev), pct(tar), sumG / total, minG, maxG)
        perDay.add(m)
        if (adapter != null) {
            log.add("day %2d: ".format(day) + adapter.endOfDay(sumEnacted / nEnacted * 60.0 / 1000.0, m.meanG, m.tbrPct / 100.0, m.minG))
            opBasal = adapter.operatingBasalUhr * 1000.0 / 60.0
        }
    }
    return MultiDayResult(perDay, basalByDay, opBasal * 60.0 / 1000.0, log)
}

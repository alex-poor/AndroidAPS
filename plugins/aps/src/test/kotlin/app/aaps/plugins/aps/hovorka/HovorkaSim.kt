package app.aaps.plugins.aps.hovorka

import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-JVM in-silico test harness for the Hovorka control core. Ported from the standalone validation
 * module so the plugin's own classes are exercised in a closed loop under model mismatch. Not a test
 * class — shared helpers used by the Hovorka*Test suites.
 */

/** Closed-loop outcome metrics (ADA in-silico conventions). */
data class LoopMetrics(
    val tirPct: Double,        // % time 3.9–10.0 mmol/L
    val tbrPct: Double,        // % time < 3.9 (below range)
    val severeHypoPct: Double, // % time < 3.0
    val tarPct: Double,        // % time > 10.0
    val meanG: Double, val minG: Double, val maxG: Double
)

/** Standard adult meal day: 50 g @07:00, 70 g @13:00, 60 g @19:00. */
val STANDARD_MEALS = mapOf(7 * 60 to 50.0, 13 * 60 to 70.0, 19 * 60 to 60.0)

/** Estimator factory for the IMM bank — drop-in replacement for the default single EKF. */
fun immEstimator(): (HovorkaModel, Double) -> GlucoseEstimator =
    { m, basal -> HovorkaImmBank(m.p, basal) }

/**
 * In-silico closed loop with SEPARATE controller and plant models (model mismatch). The EKF + MPC use
 * [controllerModel]; the true patient is [plantModel] — the controller's model is never the real patient.
 */
fun simulateClosedLoop(
    controllerModel: HovorkaModel,
    plantModel: HovorkaModel,
    nominalBasal: Double,             // profile basal (mU/min) — the titrated operating point
    meals: Map<Int, Double>,          // minute -> grams CHO (actual)
    bolusUPerG: Double = 0.05,
    bolusCoverage: Double = 0.65,     // fraction of a meal the user pre-boluses
    carbAnnounceError: Double = 1.0,  // announced/actual carbs (1.0 = perfect)
    cgmSd: Double = 0.4,
    seed: Long = 7,
    hours: Int = 24,
    controlTargetMmol: Double = 6.0,
    estimatorFactory: (HovorkaModel, Double) -> GlucoseEstimator = { m, basal -> HovorkaEkf(m, m.steadyState(basal)) },
    coupleModel: Boolean = false,
    enableSmb: Boolean = false,
    smbCapU: Double = 0.0,
    maxIobU: Double = Double.MAX_VALUE,
    announceMeals: Boolean = true,
    mealDetection: Boolean = false,
    bodyWeightKg: Double = 70.0
): LoopMetrics {
    val rng = Random(seed)
    val detector = if (mealDetection) HovorkaMealDetector(bodyWeightKg) else null
    var lastMealMin = -100000
    val maxBasal = nominalBasal * 8.0
    fun mpcFor(m: HovorkaModel, maxSmbU: Double = 0.0) = HovorkaMpc(
        m, targetMmol = controlTargetMmol,
        nominalBasalMuPerMin = nominalBasal, maxBasalMuPerMin = maxBasal,
        enableSmb = enableSmb, maxSmbU = maxSmbU
    )
    val mpc = mpcFor(controllerModel)
    fun iobU(est: GlucoseEstimator) = (est.x[5] + est.x[6]) / 1000.0

    var truth = plantModel.steadyState(nominalBasal)
    val ekf = estimatorFactory(controllerModel, nominalBasal)
    var u = nominalBasal
    var tir = 0; var tbr = 0; var sev = 0; var tar = 0; var total = 0
    var minG = 99.0; var maxG = 0.0; var sumG = 0.0

    for (minute in 0 until hours * 60) {
        meals[minute]?.let { carbs ->
            truth = plantModel.addMeal(truth, carbs)
            lastMealMin = minute
            if (announceMeals) {
                ekf.meal(carbs * carbAnnounceError)
                val bolusU = carbs * bolusUPerG * bolusCoverage
                truth = truth.copyOf().also { it[5] += bolusU * 1000.0 }
                ekf.bolus(bolusU)
            }
        }
        truth = plantModel.step(truth, u, 1.0)
        ekf.predict(u, 1.0)
        if (minute % 5 == 0) {
            val gTrue = plantModel.glucoseMmol(truth)
            val gMeas = gTrue + rng.nextGaussian() * cgmSd
            val priorG = ekf.glucoseMmol()
            ekf.update(gMeas)
            if (detector != null) {
                val announcedActive = announceMeals && (minute - lastMealMin) in 0..90
                val dCho = detector.update(gMeas - priorG, gMeas, minute % 1440, announcedActive)
                if (dCho > 0.0) ekf.meal(dCho)
            }
            val maxSmbU = if (enableSmb) max(0.0, min(smbCapU, maxIobU - iobU(ekf))) else 0.0
            val rolloutM = if (coupleModel) ekf.rolloutModel() else null
            val decider = when {
                enableSmb || rolloutM != null -> mpcFor(rolloutM ?: controllerModel, maxSmbU)
                else -> mpc
            }
            val dec = decider.decide(ekf.x)
            u = dec.basalMuPerMin
            if (dec.smbU > 0.0) {
                truth = truth.copyOf().also { it[5] += dec.smbU * 1000.0 }
                ekf.bolus(dec.smbU)
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

/** Result of a multi-day adaptive run: per-day metrics + the operating basal used each day (U/hr). */
data class MultiDayResult(val perDay: List<LoopMetrics>, val basalByDayUhr: List<Double>, val finalBasalUhr: Double)

/**
 * Multi-day loop for the adaptive-gain layer. Plant + estimator persist across days; the MPC is rebuilt
 * each day around the current operating basal, and (if [adapter] != null) the day's enacted basal +
 * glucose summary fold into the gain at midnight. Pass adapter=null for the static-gain baseline.
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
    adapter: TddAdapter? = null
): MultiDayResult {
    val rng = Random(seed)
    var opBasal = startBasalMuMin
    var truth = plantModel.steadyState(opBasal)
    val ekf = HovorkaEkf(controllerModel, controllerModel.steadyState(opBasal))
    var u = opBasal
    val perDay = ArrayList<LoopMetrics>()
    val basalByDay = ArrayList<Double>()

    for (day in 0 until days) {
        basalByDay.add(opBasal * 60.0 / 1000.0)
        val mpc = HovorkaMpc(
            controllerModel, targetMmol = 6.0,
            nominalBasalMuPerMin = opBasal, maxBasalMuPerMin = maxBasalMuMin
        )
        var sumEnacted = 0.0; var nEnacted = 0
        var tir = 0; var tbr = 0; var sev = 0; var tar = 0; var total = 0
        var minG = 99.0; var maxG = 0.0; var sumG = 0.0
        for (minute in 0 until 1440) {
            meals[minute]?.let { carbs ->
                truth = plantModel.addMeal(truth, carbs); ekf.meal(carbs * carbAnnounceError)
                val bolusU = carbs * 0.05 * bolusCoverage
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
            if (g in 3.9..10.0) tir++
            if (g < 3.9) tbr++
            if (g < 3.0) sev++
            if (g > 10.0) tar++
            minG = min(minG, g); maxG = max(maxG, g); sumG += g
        }
        fun pct(x: Int) = 100.0 * x / total
        val m = LoopMetrics(pct(tir), pct(tbr), pct(sev), pct(tar), sumG / total, minG, maxG)
        perDay.add(m)
        if (adapter != null) {
            adapter.endOfDay(sumEnacted / nEnacted * 60.0 / 1000.0, m.meanG, m.tbrPct / 100.0, m.minG)
            opBasal = adapter.operatingBasalUhr * 1000.0 / 60.0
        }
    }
    return MultiDayResult(perDay, basalByDay, opBasal * 60.0 / 1000.0)
}

/** Measure a plant's effective ISF (mg/dL/U) and IC (g/U) by probing it — used to personalise a controller. */
fun measurePatientIsfIc(plant: HovorkaModel, basalMuMin: Double, rng: Random, noise: Double): Pair<Double, Double> {
    val s0 = plant.steadyState(basalMuMin)
    val g0 = plant.glucoseMmol(s0)
    var s = s0.copyOf().also { it[5] += 1000.0 }
    var nadir = g0; var bolusAuc = 0.0
    repeat(600) { s = plant.step(s, basalMuMin, 1.0); val g = plant.glucoseMmol(s); nadir = min(nadir, g); bolusAuc += (g0 - g) }
    val isfMmol = g0 - nadir
    var sm = plant.addMeal(s0.copyOf(), 10.0)
    var mealAuc = 0.0
    repeat(600) { sm = plant.step(sm, basalMuMin, 1.0); mealAuc += (plant.glucoseMmol(sm) - g0) }
    val aucPerGram = mealAuc / 10.0
    val icG = if (aucPerGram > 1e-6) bolusAuc / aucPerGram else 10.0
    fun jitter() = 1.0 + (rng.nextDouble() * 2 - 1) * noise
    return Pair(isfMmol * 18.0 * jitter(), icG * jitter())
}

package app.aaps.plugins.aps.hovorka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/**
 * In-silico cohort safety-envelope test (TBR-only, model mismatch). Ported from the standalone validation
 * harness. The controller always uses a nominal population model; each virtual patient is a DIFFERENT
 * (perturbed) plant — the honest test where the controller's model is never the real patient.
 *
 * Asserts the safety envelope the plugin must hold: reasonable cohort TIR, and — most importantly — NO
 * severe hypo and a bounded worst-case minimum across the whole cohort.
 *
 * This is the closed-loop analogue of the standalone `validateRobustness` cohort. SMB / IMM bank / meal
 * detection are OFF here (default TBR-only path); their own tests are a TODO.
 */
class HovorkaMpcCohortTest {

    /** % time-in-range / below / severe / above, plus min glucose — ADA in-silico conventions. */
    private data class Metrics(val tir: Double, val tbr: Double, val severe: Double, val minG: Double)

    /** Standard adult meal day: 50 g @07:00, 70 g @13:00, 60 g @19:00. */
    private val standardMeals = mapOf(7 * 60 to 50.0, 13 * 60 to 70.0, 19 * 60 to 60.0)

    private fun simulateClosedLoop(
        controllerModel: HovorkaModel,
        plantModel: HovorkaModel,
        nominalBasal: Double,
        carbAnnounceError: Double,
        seed: Long
    ): Metrics {
        val rng = Random(seed)
        val maxBasal = nominalBasal * 8.0
        val mpc = HovorkaMpc(
            model = controllerModel, targetMmol = 6.0,
            nominalBasalMuPerMin = nominalBasal, maxBasalMuPerMin = maxBasal
        )
        var truth = plantModel.steadyState(nominalBasal)
        val ekf = HovorkaEkf(controllerModel, controllerModel.steadyState(nominalBasal))
        var u = nominalBasal
        var tir = 0; var tbr = 0; var sev = 0; var total = 0
        var minG = 99.0
        for (minute in 0 until 24 * 60) {
            standardMeals[minute]?.let { carbs ->
                truth = plantModel.addMeal(truth, carbs)          // plant gets ACTUAL carbs
                ekf.meal(carbs * carbAnnounceError)               // estimator told (with count error)
                val bolusU = carbs * 0.05 * 0.65                  // user pre-boluses 65% at 0.05 U/g
                truth = truth.copyOf().also { it[5] += bolusU * 1000.0 }
                ekf.bolus(bolusU)
            }
            truth = plantModel.step(truth, u, 1.0)
            ekf.predict(u, 1.0)
            if (minute % 5 == 0) {
                val gMeas = plantModel.glucoseMmol(truth) + rng.nextGaussian() * 0.4
                ekf.update(gMeas)
                u = mpc.decide(ekf.x).basalMuPerMin
            }
            val g = plantModel.glucoseMmol(truth)
            total++
            if (g in 3.9..10.0) tir++
            if (g < 3.9) tbr++
            if (g < 3.0) sev++
            minG = min(minG, g)
        }
        fun pct(x: Int) = 100.0 * x / total
        return Metrics(pct(tir), pct(tbr), pct(sev), minG)
    }

    @Test fun `cohort of 20 mismatched patients stays safe`() {
        val nominal = HovorkaModel(HovorkaParams.forWeight(70.0))   // controller's population model
        val rng = Random(2024)
        val metrics = ArrayList<Metrics>()
        for (i in 1..20) {
            val w = 55.0 + rng.nextDouble() * 40.0                  // 55–95 kg
            val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
            val basal = patient.basalForSteadyState(6.0)            // titrated to the PATIENT
            metrics += simulateClosedLoop(
                controllerModel = nominal, plantModel = patient, nominalBasal = basal,
                carbAnnounceError = 0.85 + rng.nextDouble() * 0.3,  // ±15% carb-count error
                seed = 100L + i
            )
        }
        val meanTir = metrics.map { it.tir }.average()
        val meanTbr = metrics.map { it.tbr }.average()
        val worstMin = metrics.minOf { it.minG }
        val severePatients = metrics.count { it.severe > 0.0 }

        // Safety envelope (matches the standalone validateRobustness bar).
        assertThat(severePatients).isEqualTo(0)          // no patient ever goes below 3.0 mmol/L
        assertThat(worstMin).isGreaterThan(3.0)          // bounded worst-case minimum
        assertThat(meanTbr).isLessThan(4.0)              // little time below range
        assertThat(meanTir).isGreaterThan(65.0)          // regulates matched AND mismatched patients
    }
}

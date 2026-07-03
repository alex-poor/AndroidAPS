package app.aaps.plugins.aps.hovorka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * In-silico cohort safety-envelope test (TBR-only, model mismatch). The controller always uses a nominal
 * population model; each virtual patient is a DIFFERENT (perturbed) plant — the honest test where the
 * controller's model is never the real patient.
 *
 * Asserts the safety envelope the plugin must hold: reasonable cohort TIR, and — most importantly — NO
 * severe hypo and a bounded worst-case minimum across the whole cohort. Analogue of the standalone
 * `validateRobustness` cohort. SMB / IMM / meal detection are covered by their own tests.
 */
class HovorkaMpcCohortTest {

    @Tag("slow")
    @Test fun `cohort of 20 mismatched patients stays safe`() {
        val nominal = HovorkaModel(HovorkaParams.forWeight(70.0))   // controller's population model
        val rng = Random(2024)
        val metrics = ArrayList<LoopMetrics>()
        for (i in 1..20) {
            val w = 55.0 + rng.nextDouble() * 40.0                  // 55–95 kg
            val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
            val basal = patient.basalForSteadyState(6.0)            // titrated to the PATIENT
            metrics += simulateClosedLoop(
                controllerModel = nominal, plantModel = patient, nominalBasal = basal, meals = STANDARD_MEALS,
                carbAnnounceError = 0.85 + rng.nextDouble() * 0.3,  // ±15% carb-count error
                seed = 100L + i
            )
        }
        val meanTir = metrics.map { it.tirPct }.average()
        val meanTbr = metrics.map { it.tbrPct }.average()
        val worstMin = metrics.minOf { it.minG }
        val severePatients = metrics.count { it.severeHypoPct > 0.0 }

        assertThat(severePatients).isEqualTo(0)          // no patient ever goes below 3.0 mmol/L
        assertThat(worstMin).isGreaterThan(3.0)          // bounded worst-case minimum
        assertThat(meanTbr).isLessThan(4.0)              // little time below range
        assertThat(meanTir).isGreaterThan(65.0)          // regulates matched AND mismatched patients
    }
}

package app.aaps.plugins.aps.hovorka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Tests for the two remaining opt-in estimator features: the IMM Kalman bank (absorption identification)
 * and Bayesian unannounced-meal detection. The bar for both is SAFETY FIRST — they must never walk the
 * cohort into hypo, and the meal detector (a false positive = an unwarranted insulin response) must not
 * invent phantom carbs on a quiet day. Analogue of the standalone `validateImmBank` / meal-detection runs.
 */
class HovorkaMpcImmMealTest {

    @Test fun `IMM bank estimator keeps the cohort safe`() {
        val nominal = HovorkaModel(HovorkaParams.forWeight(70.0))
        val rng = Random(2024)
        val metrics = ArrayList<LoopMetrics>()
        for (i in 1..12) {
            val w = 55.0 + rng.nextDouble() * 40.0
            val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
            val basal = patient.basalForSteadyState(6.0)
            metrics += simulateClosedLoop(
                controllerModel = nominal, plantModel = patient, nominalBasal = basal, meals = STANDARD_MEALS,
                carbAnnounceError = 0.85 + rng.nextDouble() * 0.3, seed = 100L + i,
                estimatorFactory = immEstimator(), coupleModel = true
            )
        }
        assertThat(metrics.count { it.severeHypoPct > 0.0 }).isEqualTo(0)
        assertThat(metrics.minOf { it.minG }).isGreaterThan(3.0)
    }

    @Test fun `meal detection reacts to unannounced meals without hypo`() {
        val ctrl = HovorkaModel(HovorkaParams.forWeight(70.0))
        val rng = Random(2024)
        val off = ArrayList<LoopMetrics>()
        val on = ArrayList<LoopMetrics>()
        for (i in 1..12) {
            val w = 55.0 + rng.nextDouble() * 40.0
            val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
            val basal = patient.basalForSteadyState(6.0)
            val carbErr = 0.85 + rng.nextDouble() * 0.3
            val seed = 200L + i
            // Unannounced meals: the plant gets carbs; the estimator is told nothing and no bolus is given.
            off += simulateClosedLoop(
                ctrl, patient, basal, STANDARD_MEALS, seed = seed, carbAnnounceError = carbErr,
                announceMeals = false, mealDetection = false, bodyWeightKg = w
            )
            on += simulateClosedLoop(
                ctrl, patient, basal, STANDARD_MEALS, seed = seed, carbAnnounceError = carbErr,
                announceMeals = false, mealDetection = true, bodyWeightKg = w
            )
        }
        val peakOff = off.map { it.maxG }.average()
        val peakOn = on.map { it.maxG }.average()
        val tarOff = off.map { it.tarPct }.average()
        val tarOn = on.map { it.tarPct }.average()

        assertThat(peakOn).isLessThan(peakOff)                 // reacts earlier → lower peak
        assertThat(tarOn).isLessThan(tarOff)                   // less time-above-range
        assertThat(on.count { it.severeHypoPct > 0.0 }).isEqualTo(0)  // biased to miss → never a severe hypo
    }

    @Test fun `meal detection invents no phantom carbs from CGM noise`() {
        // A quiet day at a stable glucose: only sub-deadzone, zero-mean CGM noise on the innovation, no
        // real rise. The leaky deadzoned integrator must never confirm a meal → zero grams injected.
        val detector = HovorkaMealDetector(weightKg = 75.0)
        val rng = Random(42)
        var injected = 0.0
        for (tick in 0 until 288) {                    // 24 h at 5-min ticks
            val innov = rng.nextGaussian() * 0.2       // below the 0.3 mmol/L deadzone
            val gMeas = 6.5 + rng.nextGaussian() * 0.2 // stable, above the hypo guard
            injected += detector.update(innov, gMeas, tick * 5 % 1440, announcedActive = false)
        }
        assertThat(injected).isEqualTo(0.0)
        assertThat(detector.totalInjectedG).isEqualTo(0.0)
    }
}

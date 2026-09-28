package app.aaps.plugins.aps.camaps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CONTRACTS for the two stages that were present in the source but unreachable from the plugin until they
 * were wired: §6.6 `ModifyExercise` and §7 `AdjustTDDbasedOnCGM`.
 *
 * Both had been written, documented and left with no caller — `exercising` defaulted to false and nothing
 * ever passed it, and [CamapsTddAdapter] had no reference anywhere in the plugin. Compiling and passing
 * tests said nothing about either, because there was no code path to reach.
 */
class CamapsStagesTest {

    /** A plant whose glucose is whatever the state's first element says, and which never moves. */
    private class FrozenPlant(private val g: Double) : ControlModel {
        override fun glucoseMmol(s: DoubleArray) = g
        override fun step(s: DoubleArray, u: Double, dtMin: Double) = s
    }

    private fun rate(glucoseMmol: Double, exercising: Boolean, basalUhr: Double = 0.85): Double {
        val basalMu = basalUhr * 1000.0 / 60.0
        return CamapsMpc(
            FrozenPlant(glucoseMmol),
            targetMmol = 5.8,
            nominalBasalMuPerMin = basalMu,
            maxBasalMuPerMin = 10.0 * 1000.0 / 60.0,
            maxRateMuPerMin = 3.0 * basalMu,
            observedSlopeMmolPerH = 0.0,
            exercising = exercising,
            cgmGapMin = 5.0,
            smoothedBasalMuPerMin = basalMu
        ).decide(DoubleArray(9)).basalUPerHr
    }

    // ---- §6.6 ModifyExercise -------------------------------------------------------------------

    @Test
    fun `the exercise threshold is a glucose in mmol per L, not a duration`() {
        assertEquals(8.0, CamapsMpc.NO_INSULIN_DURING_EXERCISE_MMOL, 1e-9)
    }

    @Test
    fun `exercise suspends at or below 8_0 and only then`() {
        for (g in listOf(4.0, 6.0, 7.9, 8.0))
            assertEquals(0.0, rate(g, exercising = true), 1e-9,
                         "exercising at $g mmol/L must suspend entirely")
        assertTrue(rate(12.0, exercising = true) > 0.0,
                   "exercising at 12 mmol/L is above the threshold and must still deliver")
    }

    @Test
    fun `without exercise the ordinary suspend threshold applies instead`() {
        // 6.0 is above target-1.3 = 4.5, so it must NOT suspend when not exercising -- this is the whole
        // point of the stage and the assertion that fails if `exercising` is ignored.
        assertTrue(rate(6.0, exercising = false) > 0.0,
                   "6.0 mmol/L with no exercise must not suspend")
        assertEquals(0.0, rate(4.0, exercising = false), 1e-9,
                     "4.0 mmol/L is below target-1.3 and must suspend regardless")
    }

    // ---- §7 AdjustTDDbasedOnCGM ---------------------------------------------------------------

    private fun window(mean: Double, n: Int = 96, min: Double? = null): List<Double> =
        List(n) { mean }.toMutableList().also { if (min != null) it[0] = min }

    @Test
    fun `too few entries is neutral`() {
        assertEquals(1.0, CamapsTddAdapter.factor(List(CamapsTddAdapter.MIN_ENTRIES - 1) { 12.0 }, 5.8), 1e-9)
    }

    @Test
    fun `above target asks for more insulin and below target for less`() {
        val high = CamapsTddAdapter.factor(window(10.0), 5.8)
        val low = CamapsTddAdapter.factor(window(5.0), 5.8)
        assertTrue(high > 1.0, "mean 10.0 against a 5.8 target must ask for more, got $high")
        assertTrue(low < 1.0, "mean 5.0 against a 5.8 target must ask for less, got $low")
    }

    @Test
    fun `the correction is clamped both ways`() {
        assertTrue(CamapsTddAdapter.factor(window(30.0), 5.8) <= CamapsTddAdapter.MAX_CORRECTION + 1e-9)
        assertTrue(CamapsTddAdapter.factor(window(2.5), 5.8) >= CamapsTddAdapter.MIN_CORRECTION - 1e-9)
    }

    /**
     * The override that is the whole reason this ships OFF: one reading below 3.3 in the 23-hour window
     * discounts the correction by 0.8 and blocks any increase, even at a mean glucose far above target.
     * On this patient that fires on 18 of 29 days.
     */
    @Test
    fun `a single low in the window blocks any increase`() {
        val clean = CamapsTddAdapter.factor(window(10.0), 5.8)
        val withLow = CamapsTddAdapter.factor(window(10.0, min = 3.0), 5.8)
        assertTrue(clean > 1.0, "sanity: mean 10.0 must otherwise ask for more")
        assertTrue(withLow <= 1.0,
                   "one reading at 3.0 must block the increase; got $withLow against $clean")
    }

    @Test
    fun `a low above 4_4 does not trigger the override`() {
        assertTrue(CamapsTddAdapter.factor(window(10.0, min = 5.0), 5.8) > 1.0,
                   "a minimum of 5.0 is above both thresholds and must not discount")
    }

    @Test
    fun `the blend takes at most a half-weight step toward the new value`() {
        val blended = CamapsTddAdapter.blend(storedTdd = 40.0, newTdd = 60.0)
        assertTrue(blended in 40.0..50.0 + 1e-9,
                   "the blend must not overshoot halfway; got $blended")
    }
}

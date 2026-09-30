package app.aaps.plugins.aps.camaps

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * CONTRACT: the estimator must carry an OBSERVED trend into its forecast, and must let go of it again
 * once glucose stops moving.
 *
 * This exists because a wrong line of linear algebra went unnoticed through every other test. The
 * covariance update read
 * ```
 *   P[i][j] -= kg[i] * (h[j] * s) * kg[j]        // instead of P -= K S K^T
 * ```
 * and `h` is zero in every entry but Q1, so P was reduced in one column only, scaled by 1/vg, and left
 * asymmetric. Nothing threw, nothing diverged, glucose still tracked the CGM — but the Q1<->Fx
 * cross-covariance that carries a measured trend into the flux state was corrupted, and the replica
 * silently threw away about 90% of its trend response: driven at a measured -3.6 mmol/L/h its 60-minute
 * forecast fell 0.38 mmol/L/h. Against the real controller that cost 4 of the 5 cells where CamAPS
 * suspends and the replica did not.
 *
 * Unit tests of the output stages ([AttenuationTest], [MaximumPersonalRangeTest]) cannot see this: they
 * exercise pure functions of a slope that is handed to them. Only driving the filter shows it.
 */
class CamapsEstimatorTrendTest {

    private val weight = 70.0
    private val isf = 2.3
    private val basalUhr = 0.85
    private val basalMu = basalUhr * 1000.0 / 60.0

    private fun estimator() = CamapsEstimator(
        weight, isf, basalUhr,
        egpHalfMuPerL = CamapsPlugin.EGP_HALF_MU_PER_L,
        qFlux = CamapsPlugin.Q_FLUX,
        qBio = CamapsPlugin.Q_BIO
    )

    /**
     * Six hours of CGM at 15-minute cadence: flat, then the last [fallMin] minutes falling at
     * [slopePerHour], then optionally held flat at the final value for [holdMin].
     */
    private fun drive(slopePerHour: Double, holdMin: Int = 0, bgNow: Double = 10.0,
                      fallMin: Double = 45.0): CamapsEstimator {
        val est = estimator()
        val slopePerMin = slopePerHour / 60.0
        for (m in 0..360) {
            val back = (360 - m).toDouble()
            if (m > 0) est.predict(basalMu, 1.0)
            if (back % 15.0 == 0.0) {
                val v = when {
                    back <= holdMin           -> bgNow
                    back <= holdMin + fallMin -> bgNow - slopePerMin * (back - holdMin)
                    else                      -> bgNow - slopePerMin * fallMin
                }
                est.update(maxOf(2.2, v))
            }
        }
        return est
    }

    /** Forecast rate of fall over the next hour at plain basal, mmol/L per hour. */
    private fun forecastFallPerHour(est: CamapsEstimator): Double {
        val (m, x) = est.best()
        var s = x.copyOf()
        val g0 = m.glucoseMmol(s)
        repeat(60) { s = m.step(s, basalMu, 1.0) }
        return g0 - m.glucoseMmol(s)
    }

    @Test
    fun `a flat history forecasts flat`() {
        val fall = forecastFallPerHour(drive(0.0))
        assertTrue(abs(fall) < 0.25, "flat CGM must not forecast movement, got $fall mmol/L/h")
    }

    @Test
    fun `an observed fall is carried into the forecast`() {
        // The number that matters: with the broken covariance update these came out near 0.3-0.5.
        for (obs in listOf(-1.2, -1.8, -2.4, -3.6)) {
            val fall = forecastFallPerHour(drive(obs))
            assertTrue(fall >= 0.75 * -obs,
                       "observed $obs mmol/L/h must carry into the forecast; got only -$fall")
            assertTrue(fall <= 1.25 * -obs,
                       "observed $obs mmol/L/h must not be amplified; got -$fall")
        }
    }

    @Test
    fun `an observed rise is carried into the forecast`() {
        for (obs in listOf(1.2, 2.4, 3.6)) {
            val rise = -forecastFallPerHour(drive(obs, bgNow = 12.0))
            assertTrue(rise >= 0.6 * obs,
                       "observed +$obs mmol/L/h must carry into the forecast; got only +$rise")
        }
    }

    /**
     * The `recovery` probe family, as a contract. Driven at -3.6 mmol/L/h and then held flat, the real
     * controller is back at its flat-history rate within 30 minutes, so the filter must let go of the
     * flux at a comparable speed. A random-walk state with too little process noise does not: at
     * qFlux=1e-3 the replica was still suspended 45 minutes after the fall stopped.
     */
    @Test
    fun `the forecast lets go of a fall once glucose is flat again`() {
        val falling = forecastFallPerHour(drive(-3.6, holdMin = 0))
        val held45 = forecastFallPerHour(drive(-3.6, holdMin = 45))
        assertTrue(falling > 2.0, "sanity: the falling case must forecast a fall, got $falling")
        assertTrue(held45 < 0.35 * falling,
                   "45 min of flat CGM must mostly clear the flux: falling=$falling held=$held45")
    }

    /**
     * Guards the specific failure mode of the old code: the covariance is symmetric. An asymmetric P is
     * not a valid covariance and is what made the cross-terms drift. Checked through behaviour that is
     * only stable if P stays well-formed — the filter must converge to the same forecast whether it
     * reached a flat 10 mmol/L directly or via a fall and a long flat hold.
     */
    @Test
    fun `a long flat hold converges to the flat_history forecast`() {
        val direct = forecastFallPerHour(drive(0.0))
        val viaFall = forecastFallPerHour(drive(-3.6, holdMin = 180))
        assertTrue(abs(direct - viaFall) < 0.3,
                   "after 3 h flat the history must not matter: direct=$direct viaFall=$viaFall")
    }
}

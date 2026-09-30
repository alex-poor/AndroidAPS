package app.aaps.plugins.aps.camaps

import app.aaps.plugins.aps.hovorka.HovorkaModel
import app.aaps.plugins.aps.hovorka.HovorkaParams
import kotlin.math.abs
import kotlin.math.max

/**
 * Hovorka plus ONE extra state: an unmodelled glucose flux, as a random walk.
 *
 * Extracted verbatim from EgpDisturbanceReplay.kt (hovorka-mpc) so CamapsGainCheck (hovorka-mpc) can use it without dragging in
 * that harness's dependencies. The original was built to test whether such a state explains the dawn
 * rise, and on that question it was rejected. It is reused here for a different one: our forecast
 * discards ~86% of an observed fall (glucose 6.5 falling 3.0 mmol/L/h forecasts to 6.09 at +60 min),
 * which is why the CamAPS replica cannot predict the hypo that would make it suspend. This is the only
 * state in the model that can carry an unexplained trend forward.
 *
 * Paired with `HovorkaEkf(unclampedState = DIST, distInitVar, distProcessNoiseVar)`, which is what makes
 * the state a true random walk that may go negative.
 */
open class EgpFluxModel(p: HovorkaParams) : HovorkaModel(p) {

    override val nStates = 11

    override fun derivative(s: DoubleArray, u: Double): DoubleArray {
        val base = super.derivative(s, u)          // reads s[0..9] only
        val out = DoubleArray(nStates)
        System.arraycopy(base, 0, out, 0, base.size)
        out[0] += s[DIST]                          // the disturbance is a flux straight into accessible glucose
        out[DIST] = 0.0                            // random walk: no deterministic drift
        return out
    }

    /** RK4 as the base class, except the disturbance is allowed to be NEGATIVE. */
    override fun step(s: DoubleArray, u: Double, dtMin: Double): DoubleArray {
        val k1 = derivative(s, u)
        val k2 = derivative(add(s, k1, dtMin / 2), u)
        val k3 = derivative(add(s, k2, dtMin / 2), u)
        val k4 = derivative(add(s, k3, dtMin), u)
        val out = DoubleArray(nStates)
        for (i in 0 until nStates) {
            val v = s[i] + dtMin / 6.0 * (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i])
            out[i] = if (i == DIST) v else max(0.0, v)
        }
        return out
    }

    private fun add(s: DoubleArray, d: DoubleArray, h: Double) = DoubleArray(nStates) { i ->
        val v = s[i] + h * d[i]; if (i == DIST) v else max(0.0, v)
    }

    /** Same convergence-not-counting contract as the base class, over the 11-element state. */
    override fun steadyState(u: Double, minutes: Int): DoubleArray {
        var s = DoubleArray(nStates)
        s[0] = p.vg * 6.0; s[1] = p.vg * 3.0; s[5] = u * p.tMaxI; s[6] = u * p.tMaxI
        var prevG = glucoseMmol(s)
        for (i in 1..minutes) {
            s = step(s, u, 1.0)
            if (i % 60 == 0) {
                val g = glucoseMmol(s)
                if (abs(g - prevG) < 1e-4) return s
                prevG = g
            }
        }
        return s
    }

    companion object { const val DIST = 10 }
}

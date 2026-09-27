package app.aaps.plugins.aps.hovorka

import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.random.Random

/**
 * CONTRACT: the allocation-free integrator is bit-for-bit the one it replaced.
 *
 * [HovorkaModel.stepInPlace] exists purely for speed (the rollouts run ~150k RK4 steps per loop tick). A
 * performance change must not move a single dose, so this pins it against the ORIGINAL allocating code,
 * reproduced verbatim below, on exact equality — not a tolerance.
 */
class HovorkaModelStepTest {

    private val p = HovorkaParams.personalize(
        w = 67.0, isfMgdlPerU = 2.3 * 18.0, icGPerU = 9.5, basalUPerHr = 0.45, targetMmol = 7.0, tMaxGmin = 90.0
    )
    private val model = HovorkaModel(p)

    // --- the original implementation, verbatim -----------------------------------------------------------
    private fun refDerivative(s: DoubleArray, u: Double): DoubleArray {
        val q1 = s[0]; val q2 = s[1]
        val x1 = s[2]; val x2 = s[3]; val x3 = s[4]
        val s1 = s[5]; val s2 = s[6]; val ins = s[7]
        val d1 = s[8]; val d2 = s[9]
        val g = max(q1, 0.0) / p.vg
        val f01c = if (g >= 4.5) p.f01 else p.f01 * g / 4.5
        val fr = if (g >= 9.0) 0.003 * (g - 9.0) * p.vg else 0.0
        val ug = d2 / p.tMaxG
        val dQ1 = -f01c - x1 * q1 + p.k12 * q2 - fr + ug + p.egp0 * (1.0 - x3)
        val dQ2 = x1 * q1 - (p.k12 + x2) * q2
        val dx1 = -p.ka1 * x1 + p.kb1 * ins
        val dx2 = -p.ka2 * x2 + p.kb2 * ins
        val dx3 = -p.ka3 * x3 + p.kb3 * ins
        val dS1 = u - s1 / p.tMaxI
        val dS2 = (s1 - s2) / p.tMaxI
        val dI = s2 / (p.tMaxI * p.vi) - p.ke * ins
        val dD1 = -d1 / p.tMaxG
        val dD2 = (d1 - d2) / p.tMaxG
        return doubleArrayOf(dQ1, dQ2, dx1, dx2, dx3, dS1, dS2, dI, dD1, dD2)
    }

    private fun refAdd(s: DoubleArray, d: DoubleArray, h: Double): DoubleArray {
        val out = DoubleArray(10)
        for (i in 0 until 10) out[i] = max(0.0, s[i] + h * d[i])
        return out
    }

    private fun refStep(s: DoubleArray, u: Double, dtMin: Double): DoubleArray {
        val k1 = refDerivative(s, u)
        val k2 = refDerivative(refAdd(s, k1, dtMin / 2), u)
        val k3 = refDerivative(refAdd(s, k2, dtMin / 2), u)
        val k4 = refDerivative(refAdd(s, k3, dtMin), u)
        val out = DoubleArray(10)
        for (i in 0 until 10)
            out[i] = max(0.0, s[i] + dtMin / 6.0 * (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]))
        return out
    }
    // -------------------------------------------------------------------------------------------------------

    private fun randomState(r: Random): DoubleArray {
        val ss = model.steadyState(7.5)
        return DoubleArray(10) { i -> ss[i] * (0.3 + 2.0 * r.nextDouble()) + if (i == 8) r.nextDouble() * 300.0 else 0.0 }
    }

    @Test
    fun `stepInPlace and step equal the original RK4 exactly, step by step`() {
        val r = Random(42)
        repeat(2_000) { n ->
            val s = randomState(r)
            val u = r.nextDouble() * 40.0
            val dt = listOf(0.5, 1.0, 5.0)[n % 3]
            val expected = refStep(s, u, dt)
            assertWithMessage("step #$n").that(model.step(s, u, dt)).isEqualTo(expected)
            val inPlace = s.copyOf().also { model.stepInPlace(it, u, dt) }
            assertWithMessage("stepInPlace #$n").that(inPlace).isEqualTo(expected)
            assertWithMessage("derivative #$n").that(model.derivative(s, u)).isEqualTo(refDerivative(s, u))
        }
    }

    @Test
    fun `a long rollout with a meal and changing infusion lands on the same bits`() {
        var ref = model.addMeal(model.steadyState(7.5), 80.0)
        val fast = ref.copyOf()
        for (minute in 0 until 6 * 60) {
            val u = if (minute % 90 < 30) 25.0 else 3.0
            ref = refStep(ref, u, 1.0)
            model.stepInPlace(fast, u, 1.0)
        }
        assertWithMessage("6 h rollout").that(fast).isEqualTo(ref)
    }

    @Test
    fun `step does not modify its input`() {
        val s = model.steadyState(7.5)
        val before = s.copyOf()
        model.step(s, 20.0, 1.0)
        assertWithMessage("input").that(s).isEqualTo(before)
    }
}

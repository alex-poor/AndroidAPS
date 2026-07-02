package app.aaps.plugins.aps.hovorka

import kotlin.math.max
import kotlin.math.min

/**
 * Hovorka glucoregulatory model — a physiological plant/model for T1D closed-loop control.
 *
 * Equations: canonical Hovorka 2004 ("Nonlinear model predictive control of glucose concentration
 * in subjects with type 1 diabetes", Physiol. Meas. 25:905). Parameters are published population
 * values EXCEPT tMaxI / tMaxG, which are from our analysis (see report/algorithm-spec.md); a nominal
 * controller (report/algorithm-spec.md): tMaxI = 45 min (decoded), tMaxG bank 16.6–140 min.
 *
 * This is the shared substrate for BOTH:
 *   - the state ESTIMATOR (EKF) — infer hidden state from CGM,
 *   - the in-silico PLANT — validate the controller before hardware.
 *
 * State vector s[10] (all in the model's native units):
 *   0 Q1  accessible glucose mass          (mmol)
 *   1 Q2  non-accessible glucose mass       (mmol)
 *   2 x1  insulin action on transport       (1/min)  (remote effect)
 *   3 x2  insulin action on disposal        (1/min)
 *   4 x3  insulin action on EGP (suppression, dimensionless fraction)
 *   5 S1  subcutaneous insulin, comp 1      (mU)
 *   6 S2  subcutaneous insulin, comp 2      (mU)
 *   7 I   plasma insulin concentration      (mU/L)
 *   8 D1  gut glucose, comp 1               (mmol)
 *   9 D2  gut glucose, comp 2               (mmol)
 *
 * Inputs: u = insulin infusion (mU/min); carbs handled as impulses into D1 (see [addMeal]).
 */
class HovorkaModel(val p: HovorkaParams) {

    val nStates = 10

    /** Plasma glucose concentration G = Q1/VG (mmol/L). */
    fun glucoseMmol(s: DoubleArray): Double = s[0] / p.vg

    fun glucoseMgdl(s: DoubleArray): Double = glucoseMmol(s) * 18.0

    /** d(state)/dt for insulin infusion u (mU/min). Returns a fresh derivative array. */
    fun derivative(s: DoubleArray, u: Double): DoubleArray {
        val q1 = s[0]; val q2 = s[1]
        val x1 = s[2]; val x2 = s[3]; val x3 = s[4]
        val s1 = s[5]; val s2 = s[6]; val ins = s[7]
        val d1 = s[8]; val d2 = s[9]

        val g = max(q1, 0.0) / p.vg                       // mmol/L
        // non-insulin-dependent glucose flux (brain etc.), auto-regulated below 4.5 mmol/L
        val f01c = if (g >= 4.5) p.f01 else p.f01 * g / 4.5
        // renal clearance above ~9 mmol/L
        val fr = if (g >= 9.0) 0.003 * (g - 9.0) * p.vg else 0.0
        // gut absorption rate into plasma
        val ug = d2 / p.tMaxG

        val dQ1 = -f01c - x1 * q1 + p.k12 * q2 - fr + ug + p.egp0 * (1.0 - x3)
        val dQ2 = x1 * q1 - (p.k12 + x2) * q2
        val dx1 = -p.ka1 * x1 + p.kb1 * ins
        val dx2 = -p.ka2 * x2 + p.kb2 * ins
        val dx3 = -p.ka3 * x3 + p.kb3 * ins
        val dS1 = u - s1 / p.tMaxI
        val dS2 = (s1 - s2) / p.tMaxI
        val dI  = s2 / (p.tMaxI * p.vi) - p.ke * ins
        val dD1 = -d1 / p.tMaxG                            // meals enter via addMeal() impulse
        val dD2 = (d1 - d2) / p.tMaxG

        return doubleArrayOf(dQ1, dQ2, dx1, dx2, dx3, dS1, dS2, dI, dD1, dD2)
    }

    /** RK4 step of dt minutes, constant infusion u over the step. Returns a new state. */
    fun step(s: DoubleArray, u: Double, dtMin: Double): DoubleArray {
        val k1 = derivative(s, u)
        val k2 = derivative(add(s, k1, dtMin / 2), u)
        val k3 = derivative(add(s, k2, dtMin / 2), u)
        val k4 = derivative(add(s, k3, dtMin), u)
        val out = DoubleArray(nStates)
        for (i in 0 until nStates)
            out[i] = max(0.0, s[i] + dtMin / 6.0 * (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]))
        return out
    }

    /** Deposit a meal (grams CHO) as an impulse into the gut compartment D1. */
    fun addMeal(s: DoubleArray, carbsG: Double): DoubleArray {
        val out = s.copyOf()
        out[8] += p.ag * carbsG * MMOL_PER_G_CHO
        return out
    }

    /**
     * Basal insulin infusion (mU/min) that holds the model at glucose Gt (mmol/L) in steady state,
     * with no meals. Steady-state glucose decreases monotonically with u, so bisect on u.
     */
    fun basalForSteadyState(gTargetMmol: Double, maxMinutes: Int = 6000): Double {
        fun gAt(u: Double) = glucoseMmol(steadyState(u, maxMinutes))
        var lo = 0.0                 // no insulin -> high glucose
        var hi = 5.0                 // mU/min upper bracket; grow until glucose is below target
        while (gAt(hi) > gTargetMmol && hi < 200.0) hi *= 2.0
        repeat(40) {
            val mid = 0.5 * (lo + hi)
            if (gAt(mid) > gTargetMmol) lo = mid else hi = mid
        }
        return 0.5 * (lo + hi)
    }

    /** Run the model to (approximate) steady state at constant infusion u, no meals. */
    fun steadyState(u: Double, minutes: Int = 6000): DoubleArray {
        var s = doubleArrayOf(
            p.vg * 6.0, p.vg * 3.0, 0.0, 0.0, 0.0, u * p.tMaxI, u * p.tMaxI, 0.0, 0.0, 0.0
        )
        repeat(minutes) { s = step(s, u, 1.0) }
        return s
    }

    private fun add(s: DoubleArray, d: DoubleArray, h: Double): DoubleArray {
        val out = DoubleArray(nStates)
        for (i in 0 until nStates) out[i] = max(0.0, s[i] + h * d[i])
        return out
    }

    companion object {
        const val MMOL_PER_G_CHO = 1000.0 / 180.0   // 1 g glucose ≈ 5.556 mmol
    }
}

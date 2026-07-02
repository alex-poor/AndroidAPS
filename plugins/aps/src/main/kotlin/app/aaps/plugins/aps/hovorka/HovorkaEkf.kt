package app.aaps.plugins.aps.hovorka

import kotlin.math.max

/**
 * Extended Kalman Filter over the Hovorka state, driven by CGM. This is the single-EKF phase;
 * the IMM bank (8 submodels) is a later enhancement (report/hovorka-plugin-plan.md Phase 5).
 *
 * Measurement: z = G = Q1/VG (mmol/L), so H = [1/VG, 0, 0, ...].
 * Prediction uses the model's RK4 step; the state-transition Jacobian F is computed by FINITE
 * DIFFERENCES — a finite-difference Jacobian (report/algorithm-spec.md §2).
 *
 * Inputs known to the filter: insulin infusion u(t) (mU/min) and meals (grams CHO, via [meal]).
 */
class HovorkaEkf(
    private val model: HovorkaModel,
    initialState: DoubleArray,
    private val measNoiseVar: Double = 0.5,          // R: CGM variance (mmol/L)^2
    processNoiseScale: Double = 1e-3                 // Q diagonal scale
) {
    val n = model.nStates
    var x = initialState.copyOf(); private set
    private val P = eye(n, 1.0)                       // covariance
    private val Q = eye(n, processNoiseScale)         // process noise

    init { // meal/insulin compartments start uncertain
        P[8][8] = 5.0; P[9][9] = 5.0; P[5][5] = 2.0; P[6][6] = 2.0
    }

    fun glucoseMmol() = model.glucoseMmol(x)

    /** Known meal input (grams CHO) — deposit into the estimate and inflate gut covariance. */
    fun meal(carbsG: Double) {
        x = model.addMeal(x, carbsG)
        P[8][8] += carbsG * 0.5
    }

    /** Predict one dt-minute step under insulin infusion u (mU/min). */
    fun predict(u: Double, dtMin: Double) {
        val f = numericJacobian(u, dtMin)
        x = model.step(x, u, dtMin)
        // P = F P F^T + Q
        val fp = matmul(f, P)
        val fpft = matmul(fp, transpose(f))
        for (i in 0 until n) for (j in 0 until n) P[i][j] = fpft[i][j] + Q[i][j]
    }

    /** Correct with a CGM measurement (mmol/L). */
    fun update(gMeasMmol: Double) {
        // H = d(G)/dx = [1/VG, 0...]; innovation y = z - Hx
        val hInv = 1.0 / model.p.vg
        val yInnov = gMeasMmol - x[0] * hInv
        // S = H P H^T + R  (scalar since 1 measurement)
        val s = P[0][0] * hInv * hInv + measNoiseVar
        // K = P H^T / S  (column vector)
        val k = DoubleArray(n) { P[it][0] * hInv / s }
        for (i in 0 until n) x[i] = max(0.0, x[i] + k[i] * yInnov)
        // P = (I - K H) P
        val newP = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0 until n) {
            var v = 0.0
            for (l in 0 until n) {
                val ikh = (if (i == l) 1.0 else 0.0) - k[i] * (if (l == 0) hInv else 0.0)
                v += ikh * P[l][j]
            }
            newP[i][j] = v
        }
        for (i in 0 until n) for (j in 0 until n) P[i][j] = newP[i][j]
    }

    /** F ≈ ∂(step)/∂x by central finite differences (finite differences). */
    private fun numericJacobian(u: Double, dtMin: Double): Array<DoubleArray> {
        val f = Array(n) { DoubleArray(n) }
        val base = model.step(x, u, dtMin)
        for (j in 0 until n) {
            val d = max(1e-4, x[j] * 0.02)              // δ = state/50, floored
            val xp = x.copyOf(); xp[j] += d
            val sp = model.step(xp, u, dtMin)
            for (i in 0 until n) f[i][j] = (sp[i] - base[i]) / d
        }
        return f
    }

    // --- tiny matrix helpers ---
    private fun eye(m: Int, v: Double) = Array(m) { i -> DoubleArray(m) { j -> if (i == j) v else 0.0 } }
    private fun transpose(a: Array<DoubleArray>) = Array(a[0].size) { i -> DoubleArray(a.size) { j -> a[j][i] } }
    private fun matmul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val r = Array(a.size) { DoubleArray(b[0].size) }
        for (i in a.indices) for (kk in b.indices) { val aik = a[i][kk]; if (aik != 0.0) for (j in b[0].indices) r[i][j] += aik * b[kk][j] }
        return r
    }
}

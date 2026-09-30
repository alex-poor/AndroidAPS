package app.aaps.plugins.aps.camapsfx

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One submodel's 6-D Extended Kalman Filter — faithful transcription of `SubModel1::Learn`
 * (camaps-port, DiffTest bit-exact; guide §5). States (by the submodel-record offsets they live at):
 *   0 glucose A (0x6c) · 1 glucose B (0x70) · 2 p (0x84) · 3 f/bioavailability (0xa4) ·
 *   4 glucose C, the observed one (0x74) · 5 Cs (0x88).
 * P0 = diag(196,100,3.08e-5,0.09,196,3.08e-5).
 *
 * This covers the covariance prediction (`P ← F·P·Fᵀ + Q`, rank-1 Q, forgetting) and the full
 * measurement update (H, S in double, K, the bounded state steps for p/Cs/f, `P ← (I−KH)P`).
 * The transition Jacobian entries come from `PredictStep` (offsets 0x2a0…0x2ec) — passed in via [Jac]
 * until `PredictStep` itself is transcribed; validated against the emulator's dumped fields.
 */
class Ekf(
    val idx: Int,                       // submodel index (1..8); 2 & 4 are pinned out of the mixture
    val multWini: Float                 // self+0x34 — scales the rank-1 process-noise magnitude
) {
    /** The analytic transition-Jacobian fields PredictStep writes (guide §5.2 field map). */
    class Jac(
        val f00: Float, val f01: Float, val f02: Float,       // 0x2a0/2a4/2a8
        val f10: Float, val f11: Float, val f12: Float,       // 0x2b0/2b4/2b8
        val f22: Float,                                       // 0x2c8  (F[2][5] = 1 − f22)
        val f40: Float, val f41: Float, val f42: Float, val f44: Float,  // 0x2d0/2d4/2dc/2d8
        val f55: Float,                                       // 0x2e0
        val h3: Float,                                        // 0x2c4  (measurement sensitivity to f)
        val recomb0: Float, val recomb1: Float, val recomb2: Float,     // self+700 / 0x2c0 / 0x2c4
        val qv0: Float, val qv1: Float, val qv4: Float        // 0x2e4/2e8/2ec — process-noise directions
    )

    val P = Array(6) { FloatArray(6) }
    // RAW filter states (propagated by PredictStep; updated by K here)
    var s0 = 0f; var s1 = 0f; var s4 = 0f       // 0x6c, 0x70, 0x74 (glucose compartments A,B,C)
    var p = 0f; var cs = 0f; var f = 1f          // 0x84, 0x88, 0xa4
    // f-recombined OUTPUTS (reported; NOT fed back as state) — 0x60/0x64/0x68, and reported G / model CGM
    var predCgm = 0f                              // 0xb4 = (recomb2·f + s4)/0.14, used as next step's innov base
    var reportedG = 0f                            // 0xb0 = (recomb0·f + s0)/0.14
    var logLik = 0f                               // 0xcc (per-step)

    fun initP0() {
        for (r in 0 until 6) P[r].fill(0f)
        val d = floatArrayOf(196f, 100f, 3.08025e-5f, 0.09f, 196f, 3.08025e-5f)
        for (i in 0 until 6) P[i][i] = d[i]
    }

    private fun fma(a: Float, b: Float, c: Float) = Math.fma(a, b, c)

    /**
     * Covariance prediction `P ← F·P·Fᵀ + Q` for a step of `dt` min (`sinceMeal` = minutes since the last
     * meal, controls the process-noise magnitude). The predicted STATE comes from PredictStep (feed g0/g1/
     * g2/p/cs/f/predCgm before calling); this only advances P.
     */
    fun predictCovariance(j: Jac, dt: Float, sinceMeal: Float) {
        val sig = if (sinceMeal <= 180f) 0.0005034878f else 0.00029047372f
        val q = sig * multWini * sig * multWini
        val dt1 = if (dt <= 1f) 1f else dt
        val qs = q / dt1
        val v = floatArrayOf(dt * j.qv0, dt * j.qv1, dt, 0f, dt * j.qv4, dt * 0.45f * (8.437498e-8f / q))
        // F (6×6), only the mapped entries are nonzero
        val F = Array(6) { FloatArray(6) }
        F[0][0] = j.f00; F[0][1] = j.f01; F[0][2] = j.f02
        F[1][0] = j.f10; F[1][1] = j.f11; F[1][2] = j.f12
        F[2][2] = j.f22; F[2][5] = 1f - j.f22
        F[3][3] = 1f
        F[4][0] = j.f40; F[4][1] = j.f41; F[4][2] = j.f42; F[4][4] = j.f44
        F[5][5] = j.f55
        // T = F·P·Fᵀ
        val FP = Array(6) { r -> FloatArray(6) { c -> var s = 0f; for (k in 0 until 6) s += F[r][k] * P[k][c]; s } }
        val T = Array(6) { r -> FloatArray(6) { c -> var s = 0f; for (k in 0 until 6) s += FP[r][k] * F[c][k]; s } }
        for (r in 0 until 6) for (c in 0 until 6) P[r][c] = T[r][c] + qs * v[r] * v[c]   // + rank-1 Q
    }

    /**
     * Measurement update for CGM `obs` (mmol/L; −999.9 = missing), `tSince` = minutes since the last
     * update, `sinceMeal` = minutes since the last meal. Returns after updating state, P and [logLik].
     * Faithful to `SubModel1::Learn` lines 808–898.
     */
    fun measurementUpdate(j: Jac, obs: Float, tSince: Float, sinceMeal: Float) {
        logLik = 0f
        if (tSince < 1f || abs(obs - (-999.9f)) <= 1e-5f) return   // no valid reading

        val infl = if (sinceMeal <= 27f && !sinceMeal.isNaN() && !(sinceMeal < 5f)) 2.5f else 1.0f
        val floorBase = if (tSince <= 15f) tSince else 15f
        val floor = sqrt(fma(floorBase - 1f, 0f, 0.0256f))         // = sqrt(0.0256) = 0.16
        val g35 = if (3.5f <= obs) obs else 3.5f
        val sd = if (g35 * 0.02f <= floor) floor else g35 * 0.02f
        val h = j.h3
        val innov = obs - predCgm
        val r0 = infl * sd * 0.14f
        val innovM = innov * 0.14f
        val sP = fma(h, fma(h, P[3][3], P[4][3]), fma(h, P[3][4], P[4][4]))
        val S = (r0.toDouble() * r0 + sP.toDouble()).toFloat()     // R summed in double (asm 0x57458)

        var ll = if (1e-30f <= S / 0.0196f) S / 0.0196f else 1e-30f
        ll = ((-ln(ll.toDouble())) - (innovM.toDouble() * innovM) / S.toDouble()).toFloat()
        logLik = if (idx == 2 || idx == 4) -1e10f else ll          // modes 2,4 pinned out

        val kf = 0.14f / S
        val K = FloatArray(6) { r -> kf * fma(h, P[r][3], P[r][4]) }
        val gain = innovM / S
        // raw glucose states 0,1,4 (0x6c,0x70,0x74)
        s0 = fma(gain, fma(h, P[0][3], P[0][4]), s0)
        s1 = fma(gain, fma(h, P[1][3], P[1][4]), s1)
        s4 = fma(gain, fma(h, P[4][3], P[4][4]), s4)
        // p (0x84): rate-limited to ±5.517e-4 per minute
        val dp = gain * fma(h, P[2][3], P[2][4])
        val rate = dp / tSince
        var step = if (abs(rate) <= 0.0005517241f) dp else tSince * (if (rate >= 0f) 0.0005517241f else -0.0005517241f)
        p += step
        // Cs (0x88): rate-limited ±2.4828e-4/min, clamped ±0.005
        val dc = gain * fma(h, P[5][3], P[5][4])
        val rc = dc / tSince
        var scStep = if (abs(rc) <= 0.00024827584f) dc else tSince * (if (rc >= 0f) 0.00024827584f else -0.00024827584f)
        cs += scStep
        if (cs < -0.005f) cs = -0.005f else if (0.005f < cs) cs = 0.005f
        // f/bioavailability (0xa4): K[3]·innov, clamped [0.2, 2.2]
        f = fma(K[3], innov, f)
        if (f < 0.2f) f = 0.2f else if (2.2f < f) f = 2.2f

        // P ← (I − K H) P     (H = [0,0,0,h,1,0])
        val IKH = Array(6) { FloatArray(6) }
        for (r in 0 until 6) {
            if (r < 4) IKH[r][r] = 1f
            IKH[r][3] = IKH[r][3] - (fma(P[r][3], h, P[r][4]) * h) / S
            if (r == 4) IKH[4][4] = 1f
            IKH[r][4] = IKH[r][4] - fma(h, P[r][3], P[r][4]) / S
        }
        IKH[5][5] = 1f
        val newP = Array(6) { r -> FloatArray(6) { c -> var s = 0f; for (k in 0 until 6) s += IKH[r][k] * P[k][c]; s } }
        for (r in 0 until 6) for (c in 0 until 6) P[r][c] = newP[r][c]

        // recombined OUTPUTS from the updated f — do NOT overwrite the raw states s0/s1/s4
        reportedG = fma(j.recomb0, f, s0) / 0.14f          // 0x60→0xb0
        predCgm = fma(j.recomb2, f, s4) / 0.14f            // 0x68→0xb4 (next step's innov base)
    }
}

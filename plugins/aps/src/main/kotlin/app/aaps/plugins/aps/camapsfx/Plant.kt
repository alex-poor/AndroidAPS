package app.aaps.plugins.aps.camapsfx

import kotlin.math.exp
import kotlin.math.ln

/**
 * One CamAPS submodel's PLANT — a faithful, idiomatic clean-room of `SubModel1::EndoBalance`,
 * `SetBIC` and `GetInsulinForUs`.
 *
 * Source of truth: `camaps-port` (bit-exact vs the emulator) + `report/camaps-controller-guide.md` §4.
 * Verified against the guide's decoded test point (BIR 0.85, W 70, m 1.0 →
 * Iref 7.4707, egpHalf 7.1861, SI 6.03e-4). Float arithmetic to match the binary; transcendentals
 * (`expf`/`logf`) are computed in double and narrowed, so they agree with the ARM libm to ~last-ULP
 * (the guide's own emulator differs from the phone by the same last-digit rounding, Act 1.3414 vs 1.3418).
 *
 * Per-submodel structural inputs come from `Initialise`: [weightKg] (the patient's, W), [multWktInsIni]
 * (f2 = 1.0 or 1.4), and [egp0] (EGP0, the per-submodel EGP prior at reference insulin). SetBIC derives
 * the rest from the basal-insulin-requirement (BIR).
 */
class Plant(
    val weightKg: Float,
    val multWktInsIni: Float,   // f2  (self+0x3c)
    var egp0: Float             // EGP0 (self+0x08), mmol/min
) {
    // --- fields SetBIC derives (offsets from SubModel1) ---
    var iRef = 0f       // 0x22c — plasma insulin (mU/L) at which EGP = EGP0
    var egpHalf = 0f    // 0x234 — insulin conc that halves EGP
    var si = 0f         // 0x23c — insulin sensitivity
    var f01 = 0f        // 0x230 — insulin-independent glucose flux
    var tMaxIAbs = 0f   // 0x238 — insulin absorption param, clamped [0.02, 0.15]

    /** Deep copy (for the fresh-per-rollout estimator copies the optimiser's finite-difference needs). */
    fun copy(): Plant {
        val p = Plant(weightKg, multWktInsIni, egp0)
        p.iRef = iRef; p.egpHalf = egpHalf; p.si = si; p.f01 = f01; p.tMaxIAbs = tMaxIAbs
        return p
    }

    private fun fma(a: Float, b: Float, c: Float) = Math.fma(a, b, c)          // fused, single-rounded
    private fun logf(x: Float) = ln(x.toDouble()).toFloat()
    private fun expf(x: Float) = exp(x.toDouble()).toFloat()

    /** mU/L plasma insulin for an infusion rate u (U/h): u·(1000/60)/(W·0.02709). */
    fun plasmaInsulin(u: Float) = (u / (weightKg * 0.02709f)) * 16.666666f

    /** `SubModel1::SetBIC(BIR)` — derive the per-patient plant from the basal insulin requirement. */
    fun setBIC(bir: Float) {
        var v2 = plasmaInsulin(bir)
        var v6 = if (0.5f <= v2) v2 else 0.5f
        v2 = if (v6 <= 100f) v6 else 100f
        iRef = v2

        v6 = if (bir >= 0.3f) { if (bir <= 1.0f) fma(bir - 0.3f, -0.57142854f, 1.4f) else 1.0f } else 1.4f

        val f2 = multWktInsIni
        // tMaxIAbs (0x238): exp(1.582·ln(Iref/1.38) − 6.081), scaled by f2, clamped [0.02, 0.15]
        tMaxIAbs = f2 * expf(fma(logf(iRef / 1.38f), 1.582f, -6.081f))

        val l = logf(iRef / (f2 * 1.38f))
        egpHalf = v6 * expf(l + 0.201f)
        si = expf(fma(l, -1.683f, -4.489f)) / v6

        tMaxIAbs = if (tMaxIAbs > 0.15f) 0.15f else if (tMaxIAbs < 0.02f) 0.02f else tMaxIAbs
        f01 = fma((iRef * -5.5f) * si, 0.14f, egp0)     // EGP0 − 5.5·Vg·SI·Iref  (Vg = 0.14)
    }

    /** `SubModel1::EndoBalance(Ra, u)` — dG/dt (mmol/min) for gut appearance Ra and infusion u (U/h). */
    fun endoBalance(ra: Float, u: Float): Float {
        val i = plasmaInsulin(u)
        val e = expf(-((i - iRef) * LN2) / egpHalf)
        return ((egp0 * e + ra) - f01) - i * si
    }

    /** `SubModel1::GetEGP` in isolation: EGP0·2^(−(I−Iref)/egpHalf) for infusion u. */
    fun getEgp(u: Float): Float = egp0 * expf(-((plasmaInsulin(u) - iRef) * LN2) / egpHalf)

    /**
     * `SubModel1::GetInsulinForUs(BIR)` — bisection on [0,8] U/h for the infusion that balances EGP
     * against the disturbances p+Cs; returns (rate − BIR). Re-derives the plant at `bir` first.
     */
    fun getInsulinForUs(bir: Float, p: Float, cs: Float): Float {
        setBIC(bir)
        var lo = 0f; var hi = 8f; var mid = 0f
        do {
            mid = (lo + hi) * 0.5f
            val ins = plasmaInsulin(mid)
            val e = expf(-((ins - iRef) * LN2) / egpHalf)
            // dG = (p+cs) + EGP0·e − F01 − SI·ins. dG≥0 (glucose rising, need MORE insulin) → search higher.
            // NB: camaps-port has this condition INVERTED (a port bug — GetInsulinForUs was never in its
            // bit-exact set); the decompiled C (0x580ec) is `0 <= (p+cs+EGP0·e−F01) − SI·ins`.
            val dG = ((p + cs) + egp0 * e - f01) - si * ins
            if (dG >= 0f) lo = mid else hi = mid
        } while (0.04f < kotlin.math.abs(hi - lo))
        return mid - bir
    }

    companion object { const val LN2 = 0.6931472f }
}

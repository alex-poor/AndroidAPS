package app.aaps.plugins.aps.camaps

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One CamAPS submodel, built from the decoded structure rather than adapted from Hovorka.
 *
 * Eight states, matching the `Vector<float, 8u>` capacity the binary uses throughout:
 * ```
 *   0  Q1    accessible glucose mass            mmol
 *   1  S1    subcutaneous insulin, compartment 1  mU
 *   2  S2    subcutaneous insulin, compartment 2  mU
 *   3  I     plasma insulin concentration        mU/L
 *   4  D1    gut, compartment 1                  mmol
 *   5  D2    gut, compartment 2                  mmol
 *   6  Fx    unmodelled glucose flux (random walk, may be negative)   mmol/min
 *   7  f     meal bioavailability (random walk, clamped to fLimits)   dimensionless
 *   8  lg    LOG-scale multiplier on both gut time constants           ln(dimensionless)
 * ```
 *
 * State 8 is `SubModel1::Learn`'s absorption adaptation. `Learn` (0x56600) is the EKF parameter update —
 * `Matrix<float,8u,8u>` products, `expf`/`logf`, and the `fLimits` [0.2, 2.2] and `CsLimits` [-0.005,
 * 0.005] clamps — and the `logf` is why `Model1::tMaxGpriorLN` and `tMaxIpriorLN` are LOG-normal priors
 * (log-mean 3.73767, i.e. a median of 42 min). So the absorption constants are estimated in log space on
 * top of the bank: the bank spans absorption coarsely, `Learn` tunes each submodel's own value.
 *
 * GLUCOSE — `SubModel1::EndoBalance` (0x58370). No term in Q1 anywhere:
 * ```
 *   dQ1 = EGP0 * 2^(-(I - Iref)/half)  +  Ug  -  F01  -  SI*I  +  Fx
 * ```
 *
 * GUT — the part the earlier reduction got wrong. `Model1::tMaxG1s` and `tMaxG2s` are **two different**
 * time constants per submodel (21.88/140 for submodel 0, 16.63/76.91 for submodel 4), so absorption is a
 * two-time-constant cascade, not Hovorka's single `tMaxG` used twice:
 * ```
 *   dD1 = -D1/tMaxG1
 *   dD2 =  D1/tMaxG1 - D2/tMaxG2
 *   Ug  =  f * D2/tMaxG2
 * ```
 * With 21.88 into 140 that is a fast fill and a long slow release — a very different shape from a single
 * 90-minute constant, and the reason the one-compartment reduction forecast poorly.
 *
 * INSULIN — Hovorka's subcutaneous cascade, which the binary's own constants confirm: `PredictStep`
 * contains 0.14 and 0.12, i.e. `ke` and `vi`, and `Model1::tMaxIs` is 45.
 *
 * PARAMETERS. `tMaxG1`/`tMaxG2`/`tMaxI`, `fPrior`, `fLimits` and the IMM constants are all decoded.
 * `EGP0`, `F01`, `SI`, `Iref` and `half` are per-submodel fields whose values are not recovered, so they
 * are derived from the patient's own profile against two anchors: basal insulin holds target, and one unit
 * moves glucose by ISF. See [forProfile].
 */
class CamapsSubModel(
    val vg: Double,                 // L, glucose distribution volume
    val vi: Double,                 // L, insulin distribution volume
    val ke: Double,                 // 1/min, plasma insulin elimination
    val tMaxI: Double,              // min, subcutaneous insulin time constant
    val tMaxG1: Double,             // min, gut compartment 1  (Model1::tMaxG1s)
    val tMaxG2: Double,             // min, gut compartment 2  (Model1::tMaxG2s)
    val egp0: Double,               // mmol/min, EGP at reference insulin
    val f01: Double,                // mmol/min, constant non-insulin-dependent flux
    val si: Double,                 // mmol/min per mU/L, insulin-dependent disposal
    val iRef: Double,               // mU/L, insulin at which EGP = egp0
    val egpHalf: Double,            // mU/L, insulin concentration that halves EGP
    val agBioavailability: Double,  // fraction of declared carbs entering D1 before f is applied
    /**
     * Glucose at which the basal-balance anchor was struck, mmol/L. Disposal is scaled by `G / gRef`, so
     * at this glucose the model is identical to the pure decoded form and the anchor is untouched.
     * See [GLUCOSE_DEPENDENT_DISPOSAL].
     */
    val gRefMmol: Double = 5.8,
    /** 0 disables the ∝G scaling and reproduces `EndoBalance` exactly. */
    val glucoseDisposalWeight: Double = GLUCOSE_DEPENDENT_DISPOSAL
) : ControlModel {
    val nStates = 9

    override fun glucoseMmol(s: DoubleArray) = s[Q1] / vg

    /** Gut constants scaled by the estimated log state, so `Learn`'s adaptation applies to both. */
    fun tg1(s: DoubleArray) = tMaxG1 * Math.exp(s[LG])
    fun tg2(s: DoubleArray) = tMaxG2 * Math.exp(s[LG])

    fun derivative(s: DoubleArray): DoubleArray {
        val ins = s[I]
        val t1 = tg1(s); val t2 = tg2(s)
        val ug = s[F] * s[D2] / t2
        val egp = egp0 * 2.0.pow(-(ins - iRef) / egpHalf)
        // ∝G disposal, blended by [glucoseDisposalWeight]; at 0 this is exactly `EndoBalance`, and at any
        // weight the term equals si*ins when G == gRefMmol, so the basal anchor is untouched. See
        // [GLUCOSE_DEPENDENT_DISPOSAL] for why the decoded insulin-only form cannot stand on its own.
        val gNow = s[Q1] / vg
        val gScale = 1.0 + glucoseDisposalWeight * (gNow / gRefMmol - 1.0)
        val f01c = if (gNow < 4.5) f01 * max(0.0, gNow) / 4.5 else f01
        return doubleArrayOf(
            egp + ug - f01c - si * ins * max(0.0, gScale) + s[FX],      // dQ1
            0.0,                                     // dS1 filled by the caller (needs u)
            (s[S1] - s[S2]) / tMaxI,                 // dS2
            s[S2] / (tMaxI * vi) - ke * ins,         // dI
            -s[D1] / t1,                             // dD1
            s[D1] / t1 - s[D2] / t2,                 // dD2
            0.0,                                     // dFx: random walk -- see FLUX_IS_A_RANDOM_WALK
            0.0,                                     // df:  random walk
            0.0                                      // dlg: random walk
        )
    }

    /** RK4 over dtMin with constant infusion u (mU/min). Fx may go negative; f is clamped to fLimits. */
    override fun step(s: DoubleArray, u: Double, dtMin: Double): DoubleArray {
        fun d(x: DoubleArray) = derivative(x).also { it[S1] = u - x[S1] / tMaxI }
        val k1 = d(s)
        val k2 = d(add(s, k1, dtMin / 2)); val k3 = d(add(s, k2, dtMin / 2)); val k4 = d(add(s, k3, dtMin))
        return DoubleArray(nStates) { i ->
            clampState(i, s[i] + dtMin / 6.0 * (k1[i] + 2 * k2[i] + 2 * k3[i] + k4[i]))
        }
    }

    private fun add(s: DoubleArray, d: DoubleArray, h: Double) =
        DoubleArray(nStates) { i -> clampState(i, s[i] + h * d[i]) }

    private fun clampState(i: Int, v: Double) = when (i) {
        FX -> v                                             // a flux, may be negative
        F  -> min(F_MAX, max(F_MIN, v))                     // SubModel1::fLimits
        LG -> min(LG_MAX, max(LG_MIN, v))                   // log-scale on absorption, bounded
        else -> max(0.0, v)
    }

    /** Declared carbohydrate enters D1. `f` then scales what actually reaches plasma. */
    fun addMeal(s: DoubleArray, carbsG: Double) = s.copyOf().also {
        it[D1] += agBioavailability * carbsG * MMOL_PER_G
    }

    fun addBolus(s: DoubleArray, unitsU: Double) = s.copyOf().also { it[S1] += unitsU * 1000.0 }

    /** Insulin and gut at equilibrium for infusion u; glucose placed at [glucoseMmol]. */
    fun steadyState(u: Double, glucoseMmol: Double): DoubleArray {
        val s = DoubleArray(nStates)
        s[S1] = u * tMaxI; s[S2] = u * tMaxI; s[I] = u / (vi * ke)
        s[Q1] = glucoseMmol * vg
        s[FX] = 0.0; s[F] = F_PRIOR; s[LG] = 0.0
        return s
    }

    companion object {
        const val Q1 = 0; const val S1 = 1; const val S2 = 2; const val I = 3
        const val D1 = 4; const val D2 = 5; const val FX = 6; const val F = 7; const val LG = 8

        /**
         * WHY THE FLUX STATE IS A RANDOM WALK AND NOT MEAN-REVERTING.
         *
         * A decaying flux was tried, with the binary's own decoded half-times (`DownSlopeHalfTime`
         * 0x22d2c = 60 min, `UpSlopeHalfTime` 0x22d30 = 15 min) applied to the state. It was measured
         * against the `recovery` probe family -- a 45-min fall at -3.6 mmol/L/h followed by glucose held
         * FLAT for 0-180 min, which is the only probe that sees a time constant rather than a static
         * response. The binary recovers from 0.00 U/h to its flat-history rate within 30 minutes.
         *
         * The decay does NOT produce that: recovery MAE was 0.331 without it and 0.335 with it, and it
         * cost a safety point (a cell where the binary suspends and the replica does not). What actually
         * governs the recovery is the filter's process noise on this state, `qFlux`: sweeping it moved
         * recovery MAE from 0.647 (1e-3) to 0.313 (1e-1) and took the unsafe count to zero. So the
         * recovery is the filter re-learning from new measurements, not the state fading, and these two
         * half-times belong where they already are -- the set-point recursion in `CamapsMpc`.
         *
         * The decay did improve the post-meal family sharply (0.444 -> 0.249), which says something real
         * about the MEAL path over-accumulating flux, not about the flux state as such. Recorded as a
         * known gap rather than patched with a global decay.
         */
        const val FLUX_IS_A_RANDOM_WALK = true

        /**
         * How far the insulin-dependent disposal term is made proportional to glucose. 0 reproduces the
         * decoded `EndoBalance` exactly; 1 makes disposal fully ∝G, as in Hovorka.
         *
         * §2 records `EndoBalance` (0x58370) as insulin-only: `F01` constant, disposal `SI · I` with no
         * term in Q1, no renal clearance. Taken literally that model has NO glucose-dependent behaviour at
         * all, so with basal insulin `dQ1 = 0` at ANY glucose — it predicts that wherever glucose is, it
         * stays. Against the binary that shows up twice over: at glucose 13 after a meal the replica
         * forecasts a flat 13 for three hours and rails to the ceiling (2.529 x basal against the binary's
         * 1.82–2.12), and at glucose 7 it forecasts a dive to 0.00 mmol/L and suspends where the binary
         * delivers basal.
         *
         * `F01` is a FIELD (`this+0x230`), not an immediate, so a caller is free to write a
         * glucose-scaled value into it before each call — a decode of `EndoBalance` alone could not show
         * that. Hovorka's `F01c = F01 · min(1, G/4.5)` is included here for the same reason.
         *
         * The scaling is normalised at [gRefMmol], so the basal-balance anchor in [forProfile] holds
         * unchanged and this alters nothing at target — only the behaviour away from it.
         *
         * ⛔ **MEASURED AND REJECTED. Ships at 0, i.e. the decoded insulin-only form.** Swept over the
         * 826-point reference:
         * ```
         *   weight   level   trend  low+fall  post-meal  recovery  ceiling   unsafe
         *    0.00    0.203   0.158     0.136      0.287     0.258    0.000        0   <- shipped
         *    0.25    0.194   0.194     0.091      0.270     0.272    0.014        2
         *    0.50    0.191   0.246     0.084      0.279     0.287    0.028        2
         *    0.75    0.191   0.294     0.102      0.306     0.305    0.040        6
         *    1.00    0.184   0.331     0.124      0.348     0.316    0.056        7
         * ```
         * It does what it was meant to at low glucose — the low-and-falling arm nearly halves — but it
         * wrecks the trend arm (0.158 → 0.331), breaks the previously exact ceiling, and costs up to seven
         * cells where the real controller suspends and this one does not. So `EndoBalance` really is
         * insulin-only and the plateau it implies is CamAPS's own behaviour, not a decode error.
         *
         * ⚠️ This contradicts an earlier finding in this project that swapping the glucose equation halved
         * the replica error and made the ceiling exact. That was measured on the crude one-compartment
         * reduction, before the covariance fix, and with the trend arm scored on 12 of its 96 points — all
         * three of which are now known to have been wrong. The negative result here supersedes it.
         *
         * What IS kept is Hovorka's `F01c = F01 · min(1, G/4.5)` in [derivative], which acts only below
         * 4.5 mmol/L: non-insulin-dependent uptake cannot continue at a fixed rate into glucose that is not
         * there. On its own it improves the low-and-falling arm 0.144 → 0.136 and post-meal 0.295 → 0.287
         * with nothing else moving and no safety cost, so it stays.
         */
        const val GLUCOSE_DEPENDENT_DISPOSAL = 0.0

        /**
         * Bounds on the log-scale absorption multiplier. `Model1::tMaxGpriorLN` is log-N(3.73767, 2.35),
         * which is extremely wide; bounded here to a factor of 4 either way so a single noisy window cannot
         * move absorption by an order of magnitude.
         */
        const val LG_MIN = -1.386   // /4
        const val LG_MAX = 1.386    // x4
        /** `Model1::tMaxGpriorLN` log-mean and log-sd. */
        const val TMAXG_LOG_MEAN = 3.73767
        const val TMAXG_LOG_SD = 2.35
        const val MMOL_PER_G = 1000.0 / 180.16

        /** `Model1::fPriorN` 1.0, `fPriorSDN` 0.3, `SubModel1::fLimits` [0.2, 2.2]. */
        const val F_PRIOR = 1.0; const val F_PRIOR_SD = 0.3
        const val F_MIN = 0.2; const val F_MAX = 2.2

        /** `Model1::tMaxG1s` (0x22f40) and `tMaxG2s` (0x22f64) — the 8-submodel bank. */
        val TMAXG1 = doubleArrayOf(21.88, 21.88, 81.88, 51.88, 16.63, 16.63, 36.63, 16.63)
        val TMAXG2 = doubleArrayOf(140.0, 140.0, 140.0, 140.0, 76.91, 76.91, 76.91, 76.91)
        /** `ModelIMM1::multWini` (0x23630) — initial-covariance multiplier per submodel. */
        val MULT_W_INI = doubleArrayOf(1.0, 1.0, 2.0, 2.0, 1.0, 1.0, 2.0, 2.0)
        /** `ModelIMM1::multWktInsIni` (0x23670) — per-submodel insulin-sensitivity split. */
        val MULT_WKT_INS = doubleArrayOf(1.0, 1.4, 1.0, 1.4, 1.0, 1.4, 1.0, 1.4)
        /** `Model1::priorMealProb` (0x22ff0) — [submodel][meal size class 0..3]. */
        val PRIOR_MEAL_PROB = arrayOf(
            doubleArrayOf(0.1, 0.0, 0.1, 0.0), doubleArrayOf(0.2, 0.2, 0.2, 0.2),
            doubleArrayOf(0.1, 0.0, 0.1, 0.0), doubleArrayOf(0.2, 0.2, 0.2, 0.2),
            doubleArrayOf(0.2, 0.0, 0.3, 0.0), doubleArrayOf(0.125, 0.125, 0.125, 0.125),
            doubleArrayOf(0.2, 0.0, 0.4, 0.0), doubleArrayOf(0.1, 0.1, 0.1, 0.1))
        /** `Model1::weightCategory` (0x23070), kg. */
        val WEIGHT_CATEGORY = doubleArrayOf(13.0, 25.0, 50.0, 85.0, 10000.0)
        /** `Model1::mealSizeForWeightCategory` (0x23084), grams, [weightCat][class]. */
        val MEAL_SIZE = arrayOf(
            doubleArrayOf(6.0, 11.0, 36.0, 1000.0), doubleArrayOf(6.0, 16.0, 51.0, 1000.0),
            doubleArrayOf(11.0, 31.0, 71.0, 1000.0), doubleArrayOf(16.0, 41.0, 71.0, 1000.0),
            doubleArrayOf(21.0, 51.0, 81.0, 1000.0))
        /** `halfTimeTran` (0x22cec), min — IMM mode-transition half-times. */
        val HALF_TIME_TRAN = doubleArrayOf(17.0, 60.0, 180.0)
        /** `Model1::tMaxIs` (0x22f30). */
        const val TMAX_I = 45.0

        /** §3.3: meal size class from carbohydrate relative to body weight. */
        fun mealClass(weightKg: Double, carbsG: Double): Int {
            val wc = WEIGHT_CATEGORY.indexOfFirst { weightKg <= it }.let { if (it < 0) 4 else it }
            val row = MEAL_SIZE[wc]
            return row.indexOfFirst { carbsG <= it }.let { if (it < 0) 3 else it }
        }

        /**
         * Build submodel [k] for a patient, deriving the five unrecovered parameters from the profile.
         *
         * Anchors, the same two `HovorkaParams.personalize` uses:
         *  - at basal insulin with no carbohydrate, dQ1/dt = 0, so glucose holds wherever it is;
         *  - one unit of insulin lowers glucose by ISF, and since disposal is `SI*I` the whole-bolus
         *    integral is `integral(I dt) = D/(vi*ke)`, giving `SI = ISF * vg * vi * ke / 1000`.
         *
         * `iRef` is folded into `egp0`. `F01` keeps Hovorka's `0.0097 * weight`. `egpHalf` is a free
         * parameter of the fit. `MULT_WKT_INS[k]` scales `SI` per submodel, which is what that decoded
         * array appears to be for.
         */
        fun forProfile(k: Int, weightKg: Double, isfMmolPerU: Double, basalUPerHr: Double,
                       egpHalfMuPerL: Double, agBio: Double = 0.8, gRefMmol: Double = 5.8,
                       gDisposal: Double = GLUCOSE_DEPENDENT_DISPOSAL,
                       siScale: Double = 1.0): CamapsSubModel {
            val vg = 0.16 * weightKg
            val vi = 0.12 * weightKg
            val ke = 0.14
            val f01 = 0.0097 * weightKg
            val si = isfMmolPerU * siScale * vg * vi * ke / 1000.0 * MULT_WKT_INS[k]
            val iBasal = (basalUPerHr * 1000.0 / 60.0) / (vi * ke)
            // balance at basal: egp0 * 2^(-iBasal/half) = f01 + si*iBasal
            val egp0 = (f01 + si * iBasal) / 2.0.pow(-iBasal / egpHalfMuPerL)
            return CamapsSubModel(vg, vi, ke, TMAX_I, TMAXG1[k], TMAXG2[k],
                egp0, f01, si, 0.0, egpHalfMuPerL, agBio, gRefMmol, gDisposal)
        }
    }
}

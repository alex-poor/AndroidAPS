package hovorka.mpc

import kotlin.math.max
import kotlin.math.min

/**
 * Hovorka model parameters. Population values from Hovorka 2004 (Physiol. Meas.) and the widely-used
 * parameter set; tMaxI/tMaxG from our analysis (report/algorithm-spec.md).
 *
 * Weight-scaled quantities (F01, EGP0, VI, VG) are computed from bodyWeightKg in [forWeight].
 */
data class HovorkaParams(
    val bodyWeightKg: Double,

    // --- glucose subsystem ---
    val k12: Double = 0.066,        // 1/min, Q2 -> Q1 transfer
    val vg: Double,                 // L, glucose distribution volume (= 0.16 * W)
    val f01: Double,                // mmol/min, non-insulin-dependent glucose flux (= 0.0097 * W)
    val egp0: Double,               // mmol/min, endogenous glucose production at zero insulin (= 0.0161 * W)

    // --- insulin action (deactivation ka*, activation kb* = ka*·SI*) ---
    val ka1: Double = 0.006,        // 1/min
    val ka2: Double = 0.06,
    val ka3: Double = 0.03,
    val kb1: Double = 0.006 * 51.2e-4,   // = ka1·SIT ≈ 3.072e-5   (transport sensitivity)
    val kb2: Double = 0.06  * 8.2e-4,    // = ka2·SID ≈ 4.92e-5    (disposal sensitivity)
    val kb3: Double = 0.03  * 520e-4,    // = ka3·SIE ≈ 1.56e-3    (EGP sensitivity)

    // --- insulin kinetics ---
    val ke: Double = 0.138,         // 1/min, plasma insulin elimination
    val vi: Double,                 // L, insulin distribution volume (= 0.12 * W)
    val tMaxI: Double = 45.0,       // min, SC insulin time-to-peak — *** design value 45 (report/algorithm-spec.md) ***

    // --- meal / gut absorption ---
    val ag: Double = 0.8,           // carb bioavailability
    val tMaxG: Double = 40.0        // min, gut absorption time-to-peak — absorption bank 16.6..140 min (nominal 40)
) {
    companion object {
        /** Build a nominal parameter set for a given body weight (kg). */
        fun forWeight(w: Double, tMaxImin: Double = 45.0, tMaxGmin: Double = 40.0) = HovorkaParams(
            bodyWeightKg = w,
            vg = 0.16 * w,
            f01 = 0.0097 * w,
            egp0 = 0.0161 * w,
            vi = 0.12 * w,
            tMaxI = tMaxImin,
            tMaxG = tMaxGmin
        )

        /**
         * The 8 tMaxG values of the IMM submodel bank (report/algorithm-spec.md, tMaxG1),
         * spanning slow↔fast carb absorption. Used later for the IMM estimator; for the single-EKF
         * phase we use a nominal tMaxG.
         */
        val TMAXG_BANK = doubleArrayOf(21.9, 21.9, 81.9, 51.9, 16.6, 16.6, 36.6, 16.6)

        // Weight categories (kg) and dose rate modifiers (report/algorithm-spec.md §6)
        val WEIGHT_CATEGORY = doubleArrayOf(13.0, 25.0, 50.0, 85.0, 1e4)
        const val NOMINAL_WEIGHT = 45.0
        const val LOW_WEIGHT = 20.0
        const val RATE_MOD_NOMINAL = 1.0
        const val RATE_MOD_LOW = 1.45
        const val MIN_TDD_PER_KG_T1D = 0.15   // U/kg/day

        /**
         * Build a patient-PERSONALISED parameter set from an AAPS profile (improvement 2a).
         *
         * The user has already titrated two clinical quantities — ISF (mg/dL per U) and IC (g per U) —
         * plus a basal/target operating point. We map those onto Hovorka physiology instead of using
         * population insulin sensitivities:
         *   1. a single insulin-sensitivity multiplier on kb1/kb2/kb3 so the model's correction-bolus
         *      ISF (baseline→nadir drop from 1 U) matches the profile ISF;
         *   2. endogenous glucose production egp0 anchored so the model holds [targetMmol] at the
         *      profile basal (this is the "anchor steady state to profile basal+target" step);
         *   3. carb bioavailability ag so the model's insulin:carb balance (bolus AUC vs. meal AUC)
         *      matches the profile IC.
         * Steps 1 and 2 are near-orthogonal (sensitivity vs. glucose level) so a few fixed-point
         * sweeps converge; step 3 is one shot once SI is fixed.
         *
         * ROBUSTNESS: both the SI multiplier and egp0 are BOUNDED to physiological ranges (relative to the
         * population base) and the SI multiplier is solved ABSOLUTELY from the base kb each sweep (never
         * compounded) — so an extreme or self-inconsistent profile clamps to the nearest sane model instead
         * of diverging to a degenerate one (a model with ss@basal≈0 would make the controller refuse all
         * insulin). The loop always ENDS on an egp0 anchor so steadyState(basal)==target holds.
         *
         * Falls back to population weight scaling when ISF/basal/target are absent (<= 0).
         */
        fun personalize(
            w: Double,
            isfMgdlPerU: Double,
            icGPerU: Double,
            basalUPerHr: Double,
            targetMmol: Double,
            tMaxImin: Double = 45.0,
            tMaxGmin: Double = 40.0
        ): HovorkaParams {
            val base = forWeight(w, tMaxImin, tMaxGmin)
            if (isfMgdlPerU <= 0.0 || basalUPerHr <= 0.0 || targetMmol <= 0.0) return base
            val basalMuMin = basalUPerHr * 1000.0 / 60.0
            val isfMmolPerU = isfMgdlPerU / 18.0
            val egp0Lo = EGP0_MIN_MUL * base.egp0
            val egp0Hi = EGP0_MAX_MUL * base.egp0

            fun withParams(siMul: Double, egp0: Double) =
                base.copy(kb1 = base.kb1 * siMul, kb2 = base.kb2 * siMul, kb3 = base.kb3 * siMul, egp0 = egp0)

            // Solve ONCE, in order (no outer iteration — chasing egp0↔SI via the confounded ISF measurement
            // has multiple fixed points and can run away to a degenerate high-SI/high-EGP corner):
            //   1. anchor egp0 to hold target at basal with population SI,
            var egp0 = anchorEgp0(base, basalMuMin, targetMmol).coerceIn(egp0Lo, egp0Hi)
            //   2. solve the absolute SI multiplier to match ISF at that egp0,
            val siMul = solveSiMul(base, egp0, basalMuMin, isfMmolPerU, targetMmol).coerceIn(SI_MUL_MIN, SI_MUL_MAX)
            //   3. re-anchor egp0 with the final SI so steadyState(basal)==target holds exactly.
            egp0 = anchorEgp0(withParams(siMul, egp0), basalMuMin, targetMmol).coerceIn(egp0Lo, egp0Hi)
            var p = withParams(siMul, egp0)
            // (3): carb bioavailability from IC (only if a sane IC was supplied).
            if (icGPerU > 0.0) p = p.copy(ag = calibrateAgToIc(p, basalMuMin, icGPerU))
            return p
        }

        /** steady-state glucose (mmol/L) at constant infusion, cheap horizon for calibration. */
        private fun ssGlucose(p: HovorkaParams, basalMuMin: Double): Double {
            val m = HovorkaModel(p)
            return m.glucoseMmol(m.steadyState(basalMuMin, minutes = CAL_SS_MIN))
        }

        /** egp0 such that steadyState(basal) == targetMmol (glucose rises monotonically with egp0). */
        private fun anchorEgp0(p: HovorkaParams, basalMuMin: Double, targetMmol: Double): Double {
            var lo = 1e-5
            var hi = max(p.egp0, 1e-3)
            while (ssGlucose(p.copy(egp0 = hi), basalMuMin) < targetMmol && hi < 100.0) hi *= 1.6
            while (ssGlucose(p.copy(egp0 = lo), basalMuMin) > targetMmol && lo > 1e-8) lo *= 0.5
            repeat(34) {
                val mid = 0.5 * (lo + hi)
                if (ssGlucose(p.copy(egp0 = mid), basalMuMin) < targetMmol) lo = mid else hi = mid
            }
            return 0.5 * (lo + hi)
        }

        /**
         * Model correction ISF: baseline→nadir glucose drop (mmol/L) from a 1 U bolus, starting PINNED at
         * [startGmmol]. Pinning the start glucose is essential: it makes the measurement comparable across
         * SI multipliers (otherwise a high multiplier crashes the steady state toward 0, the nadir floors,
         * and the drop reads as tiny — inverting the solve and driving SI to the clamp).
         */
        private fun modelIsfMmol(p: HovorkaParams, basalMuMin: Double, startGmmol: Double): Double {
            val m = HovorkaModel(p)
            val s0 = m.steadyState(basalMuMin, minutes = CAL_SS_MIN).copyOf()
            val gSteady = m.glucoseMmol(s0)
            if (gSteady > 1e-6) { val scale = startGmmol / gSteady; s0[0] *= scale; s0[1] *= scale }
            else s0[0] = startGmmol * p.vg
            val g0 = m.glucoseMmol(s0)
            var s = s0.copyOf().also { it[5] += 1000.0 }        // 1 U -> mU into SC comp 1
            var nadir = g0
            repeat(360) { s = m.step(s, basalMuMin, 1.0); nadir = min(nadir, m.glucoseMmol(s)) }
            return g0 - nadir
        }

        /**
         * Solve the ABSOLUTE SI multiplier (relative to the population base kb) that makes modelISF match
         * the target, at a fixed egp0. Solved from `base` (not compounded) and bounded to [SI_MUL_MIN,
         * SI_MUL_MAX]; if the target is unreachable inside the bounds we return the boundary (clamped),
         * never a runaway. modelISF is monotone increasing in the multiplier.
         */
        private fun solveSiMul(base: HovorkaParams, egp0: Double, basalMuMin: Double, targetIsfMmol: Double, startGmmol: Double): Double {
            fun isfAt(mul: Double) =
                modelIsfMmol(base.copy(kb1 = base.kb1 * mul, kb2 = base.kb2 * mul, kb3 = base.kb3 * mul, egp0 = egp0), basalMuMin, startGmmol)
            var lo = SI_MUL_MIN; var hi = SI_MUL_MAX
            if (isfAt(hi) <= targetIsfMmol) return hi
            if (isfAt(lo) >= targetIsfMmol) return lo
            repeat(28) {
                val mid = 0.5 * (lo + hi)
                if (isfAt(mid) < targetIsfMmol) lo = mid else hi = mid
            }
            return 0.5 * (lo + hi)
        }

        /**
         * Carb bioavailability ag matching the profile IC: the glucose AUC from a meal of [icGPerU]
         * grams should cancel the (negative) AUC from the 1 U that covers it. Meal AUC scales ~linearly
         * with ag, so solve in one shot. Clamped to a physiological [0.3, 1.0].
         */
        private fun calibrateAgToIc(p: HovorkaParams, basalMuMin: Double, icGPerU: Double): Double {
            val m = HovorkaModel(p)
            val s0 = m.steadyState(basalMuMin, minutes = CAL_SS_MIN)
            val g0 = m.glucoseMmol(s0)
            fun mealAuc(ag: Double): Double {
                val mm = HovorkaModel(p.copy(ag = ag))
                var s = mm.addMeal(s0.copyOf(), icGPerU)
                var auc = 0.0
                repeat(600) { s = mm.step(s, basalMuMin, 1.0); auc += (mm.glucoseMmol(s) - g0) }
                return auc
            }
            var s = s0.copyOf().also { it[5] += 1000.0 }
            var bolusAuc = 0.0
            repeat(600) { s = m.step(s, basalMuMin, 1.0); bolusAuc += (g0 - m.glucoseMmol(s)) }
            val mealAuc1 = mealAuc(1.0)
            if (mealAuc1 <= 1e-6) return p.ag
            return (bolusAuc / mealAuc1).coerceIn(0.3, 1.0)
        }

        private const val CAL_SS_MIN = 3000        // steady-state horizon for calibration (min)
        // physiological bounds keeping personalisation from diverging on extreme/inconsistent profiles
        private const val SI_MUL_MIN = 0.2         // insulin sensitivity vs. population base
        private const val SI_MUL_MAX = 5.0
        private const val EGP0_MIN_MUL = 0.3       // endogenous glucose production vs. population base
        private const val EGP0_MAX_MUL = 3.0

        /**
         * A random virtual PATIENT: perturb physiology away from the population nominal so the
         * controller (which uses nominal params) faces genuine model mismatch. Ranges are clinically
         * plausible: insulin sensitivity ±40%, EGP/F01 ±20%, tMaxI 40–70, tMaxG 30–60, AG 0.7–0.9.
         */
        fun randomPatient(rng: java.util.Random, w: Double): HovorkaParams {
            fun j(frac: Double) = 1.0 + (rng.nextDouble() * 2 - 1) * frac   // ×(1±frac)
            val siMul = j(0.40)
            return HovorkaParams(
                bodyWeightKg = w,
                vg = 0.16 * w,
                f01 = 0.0097 * w * j(0.20),
                egp0 = 0.0161 * w * j(0.20),
                ka1 = 0.006, ka2 = 0.06, ka3 = 0.03,
                kb1 = 0.006 * 51.2e-4 * siMul,
                kb2 = 0.06 * 8.2e-4 * siMul,
                kb3 = 0.03 * 520e-4 * siMul,
                ke = 0.138,
                vi = 0.12 * w,
                tMaxI = 40.0 + rng.nextDouble() * 30.0,     // 40–70 min
                ag = 0.7 + rng.nextDouble() * 0.2,          // 0.7–0.9
                tMaxG = 30.0 + rng.nextDouble() * 30.0      // 30–60 min
            )
        }
    }
}

package app.aaps.plugins.aps.hovorka

import kotlin.math.abs
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
         *
         * (1) and (2) are NOT independent — egp0 is a function of the sensitivity, since only one egp0 holds
         * target at the profile basal for a given SI. They are therefore solved together, as a single bounded
         * bisection on the multiplier with egp0 re-anchored inside it; see the comment on the solve. (3) is
         * one shot once SI is fixed.
         *
         * ROBUSTNESS: both the SI multiplier and egp0 are BOUNDED to physiological ranges relative to the
         * population base, and the multiplier is solved ABSOLUTELY from the base kb (never compounded) — so an
         * extreme or self-inconsistent profile clamps to the nearest sane model instead of diverging to a
         * degenerate one (a model with ss@basal≈0 would make the controller refuse all insulin). Because egp0
         * is derived from the multiplier rather than chased alongside it, there is no fixed-point iteration
         * and no runaway corner to guard against.
         *
         * Falls back to population weight scaling when ISF/basal/target are absent (<= 0).
         * Pinned by PersonalizeCheck: the returned model must have the ISF and operating point it was asked
         * for. Nothing pinned that before, which is how a systematic 37% ISF error shipped.
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

            fun withSi(siMul: Double) =
                base.copy(kb1 = base.kb1 * siMul, kb2 = base.kb2 * siMul, kb3 = base.kb3 * siMul)

            // CONSISTENT SOLVE (fixed 2026-08-14). egp0 is NOT an independent unknown: for any insulin
            // sensitivity there is exactly one egp0 that holds [targetMmol] at the profile basal. Making that
            // dependence explicit collapses a two-unknown problem to a ONE-dimensional bounded bisection on
            // the multiplier alone — no fixed-point iteration, so none of the runaway the previous ordering
            // was written to avoid, and no inconsistency either.
            //
            // WHAT WAS WRONG BEFORE. The old code solved in three ordered steps: anchor egp0 at POPULATION
            // sensitivity, solve the multiplier against that egp0, then RE-anchor egp0 with the solved
            // multiplier. That last step moves the very quantity the solve was conditioned on, and nothing
            // re-solves afterwards. Traced on this user's profile (67 kg, 0.45 U/hr, target 7.0, ISF 2.3):
            // step 2 found siMul 0.803 giving exactly ISF 2.30 at egp0 = 1.22x base; step 3 then moved egp0
            // to 1.01x base, and the model that actually shipped had ISF 3.16 — 37% too insulin-sensitive.
            // The error is systematic and one-directional, not noise. It also cascaded: `calibrateAgToIc`
            // balances the meal against a bolus that was too strong, so `ag` clamped at its 1.0 ceiling and
            // the carb side came out ~20% weak as well.
            fun paramsFor(siMul: Double): HovorkaParams {
                val p = withSi(siMul)
                return p.copy(egp0 = anchorEgp0(p, basalMuMin, targetMmol).coerceIn(egp0Lo, egp0Hi))
            }
            // modelISF is monotone increasing in the multiplier (verified by sweep across 0.005..5.0), so a
            // plain bisection is sound; outside the bracket we clamp to the boundary exactly as before.
            fun isfFor(siMul: Double) = modelIsfMmol(paramsFor(siMul), basalMuMin, targetMmol)
            var lo = SI_MUL_MIN
            var hi = SI_MUL_MAX
            val siMul = when {
                isfFor(lo) >= isfMmolPerU -> lo
                isfFor(hi) <= isfMmolPerU -> hi
                else -> {
                    repeat(SI_SOLVE_STEPS) {
                        val mid = 0.5 * (lo + hi)
                        if (isfFor(mid) < isfMmolPerU) lo = mid else hi = mid
                    }
                    0.5 * (lo + hi)
                }
            }
            // egp0 is already the anchored value FOR this multiplier, so steadyState(basal)==target holds and
            // there is no trailing re-anchor to invalidate the ISF match.
            var p = paramsFor(siMul)
            // carb bioavailability from IC (only if a sane IC was supplied).
            if (icGPerU > 0.0) p = p.copy(ag = calibrateAgToIc(p, basalMuMin, icGPerU))
            return p
        }

        /** steady-state glucose (mmol/L) at constant infusion, cheap horizon for calibration. */
        private fun ssGlucose(p: HovorkaParams, basalMuMin: Double): Double {
            val m = HovorkaModel(p)
            return m.glucoseMmol(m.steadyState(basalMuMin, minutes = CAL_SS_MIN))
        }

        /**
         * egp0 such that steadyState(basal) == targetMmol (glucose rises monotonically with egp0).
         *
         * Now runs inside the SI bisection rather than twice per personalise, so cost matters. Steady-state
         * glucose is very nearly PROPORTIONAL to egp0 at fixed insulin sensitivity, so a few proportional
         * updates land within a fraction of a percent where bisection needed ~34 evaluations. Bisection is
         * retained as the fallback: the proportional step is only trusted while it is converging, so a
         * non-linear corner degrades to the old behaviour instead of returning a wrong anchor.
         */
        private fun anchorEgp0(p: HovorkaParams, basalMuMin: Double, targetMmol: Double): Double {
            var e = p.egp0.coerceAtLeast(1e-6)
            repeat(ANCHOR_PROPORTIONAL_STEPS) {
                val g = ssGlucose(p.copy(egp0 = e), basalMuMin)
                if (g <= 1e-9) return@repeat
                if (abs(g - targetMmol) < ANCHOR_TOL_MMOL) return e
                e *= (targetMmol / g).coerceIn(0.25, 4.0)      // bounded so one bad step cannot fling it away
            }
            // fallback: bracket around the proportional estimate and bisect, as before
            var lo = e * 0.25
            var hi = e * 4.0
            while (ssGlucose(p.copy(egp0 = hi), basalMuMin) < targetMmol && hi < 100.0) hi *= 1.6
            while (ssGlucose(p.copy(egp0 = lo), basalMuMin) > targetMmol && lo > 1e-8) lo *= 0.5
            repeat(24) {
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
         * Carb bioavailability ag matching the profile IC: a meal of [icGPerU] grams taken TOGETHER with the
         * 1 U that covers it should be glucose-neutral over the following hours.
         *
         * Meal AUC scales ~linearly with ag, so this is solved in one shot rather than iterated. Clamped to a
         * physiological [0.3, 1.0].
         *
         * A DIRECT SOLVE WAS TRIED AND REVERTED (2026-08-14). Bisecting ag so that the COMBINED meal+bolus
         * trajectory has zero net area is the more principled formulation, and it is worse in practice: it
         * drove ag to the 1.0 ceiling on 5 of the 8 profiles in PersonalizeCheck, against 1 for this method,
         * and left this user's own profile no better (net −1.4 vs +2.5 mmol/L·h). The reason is that neither
         * formulation is really sound — the model has no counter-regulation and `HovorkaModel.step` floors
         * glucose at zero, so on a sensitive profile a 1 U bolus produces a long, deep, truncated tail whose
         * area no physiological amount of carbohydrate can offset. The residual is a MODEL limitation, not a
         * calibration bug, and swapping one approximation for another on a live dosing path buys nothing.
         *
         * What did fix most of the carb-side error was the ISF solve above: the old `ag` clamped at 1.0 on
         * this user largely because it was balancing against a bolus that was 37% too strong. With the ISF
         * consistent, ag lands at 0.87 and a profile-matched meal is within ~2.5 mmol/L·h of neutral.
         * PersonalizeCheck reports the residual so it stays visible.
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
            return (bolusAuc / mealAuc1).coerceIn(AG_MIN, AG_MAX)
        }

        // Steady-state CEILING for calibration. Was 3000, which is not converged on a low-sensitivity model:
        // the anchor held target at 3000 min while the same model settled 0.3 mmol/L lower by 6000, so the
        // controller's operating point sat below the target it had just been calibrated to. HovorkaModel
        // .steadyState now stops as soon as glucose stops moving, so a generous ceiling costs nothing for the
        // models that settle quickly and is correct for the ones that do not.
        private const val CAL_SS_MIN = HovorkaModel.SS_MAX_MIN
        // SI bisection: the bracket is [0.2, 5.0], so 18 halvings resolve the multiplier to ~2e-5 — far finer
        // than the ISF measurement itself. Each step now costs an egp0 anchor, hence not the old 28.
        private const val SI_SOLVE_STEPS = 18
        private const val ANCHOR_PROPORTIONAL_STEPS = 8
        private const val ANCHOR_TOL_MMOL = 1e-4   // steady state this close to target is exact enough
        private const val AG_MIN = 0.3             // physiological carb bioavailability bounds
        private const val AG_MAX = 1.0
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

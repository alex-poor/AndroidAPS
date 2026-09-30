package app.aaps.plugins.aps.camaps

import app.aaps.plugins.aps.hovorka.HovorkaParams
import kotlin.math.max

/**
 * CamAPS's plant: **dG/dt does not depend on G.**
 *
 * `SubModel1::EndoBalance` (0x58370) in full:
 * ```
 *   I  = u / (P * 0.02709) * (1000/60)
 *   dG = EGP0 * 2^(-(I - I_ref)/half)  +  Ra  -  F01  -  SI * I
 * ```
 * There is no term in `G` anywhere. Hovorka's glucose equation is the opposite — `x1*Q1`
 * (insulin-dependent transport), `Fr` (renal clearance) and `F01c` below 4.5 are all proportional to
 * `Q1` — and that difference is the single largest source of error in this replica. Measured against 747
 * sanity-verified points from the real binary, swapping only the glucose equation takes the
 * point-weighted error from 0.585 to 0.244 x profile basal and the ceiling arm to exactly 0.000, with the
 * post-meal arm dropping 0.941 -> 0.297. See report/camaps-measured-response.md §19-20.
 *
 * The exponential EGP term is the counter-regulation Hovorka has no equivalent of: as insulin falls below
 * basal, endogenous production RISES and brakes the fall. Hovorka's `egp0 * (1 - x3)` is linear in an
 * insulin-effect state and cannot exceed `egp0`. Its absence is the root cause recorded for the
 * eventualBG crater, and restoring it is what fixed the post-meal regime.
 *
 * WHAT IS DECODED vs INFERRED — read this before changing anything:
 *  - DECODED: the equation's shape; that disposal is proportional to insulin with no glucose term; that
 *    EGP is suppressed as a power of two (the divisor is the `ln2` symbol).
 *  - NOT DECODED: `half` (`this+0x234`, a per-submodel field, so per-patient rather than a global) and
 *    `I_ref`. `I_ref` is folded into `egp0` here, and `half` is [egpHalfMuPerL], fitted against the
 *    binary's own measured behaviour: 7 mU/L is the lowest value at which no measured point has the real
 *    controller suspending while this one does not. Lower fits better still (3 gives point-weighted 0.205)
 *    but reintroduces 11 such points.
 *  - `F01` is Hovorka's, and `SI` comes from the profile ISF via the bolus integral
 *    `integral(I dt) = D / (vi * ke)`, giving `SI = ISF * vg * vi * ke / 1000`.
 *
 * The insulin and carb subsystems are Hovorka's, untouched: `SubModel1::PredictStep` (7660 bytes) and
 * `SubModel1::Learn` (6884 bytes) are unread, so CamAPS's own 8-state layout is not replicated. This is
 * the CamAPS glucose equation on a Hovorka insulin/carb chassis, not the whole 8-state model.
 */
class CamapsModel(
    p: HovorkaParams,
    private val basalUPerHr: Double,
    isfMmolPerU: Double,
    private val targetMmol: Double,
    /** Half-effect insulin concentration for the EGP term (mU/L). Undecoded; see [derivative]. */
    private val egpHalfMuPerL: Double = 20.0
) : EgpFluxModel(p) {

    /** mmol/min of glucose removed per mU/L of plasma insulin. From ISF via the bolus integral. */
    private val si: Double = isfMmolPerU * p.vg * p.vi * p.ke / 1000.0

    /** Plasma insulin at steady basal infusion: I = u / (vi * ke), u in mU/min. */
    private val insulinAtBasal: Double = (basalUPerHr * 1000.0 / 60.0) / (p.vi * p.ke)

    /**
     * EGP at zero insulin, solved so that basal insulin exactly balances: at I = [insulinAtBasal] and no
     * carbs, dG/dt = 0. With the exponential term that means
     * `EGP0 * 2^(-I_b/half) = F01 + SI*I_b`.
     */
    private val egp0: Double =
        (p.f01 + si * insulinAtBasal) / Math.pow(2.0, -insulinAtBasal / egpHalfMuPerL)

    /**
     * `SubModel1::EndoBalance` (0x58370), structurally:
     * ```
     *   dG = EGP0 * 2^(-(I - I_ref)/half)  +  Ra  -  F01  -  SI * I
     * ```
     * with I_ref folded into EGP0. **No term in G anywhere** — that is the property being replicated.
     *
     * The exponential EGP term is the counter-regulation Hovorka has no equivalent of: as insulin falls
     * below basal, endogenous production RISES, which brakes a fall. Hovorka's `egp0 * (1 - x3)` is
     * linear in an insulin-effect state and cannot exceed `egp0`, and its absence of a brake is the
     * root cause recorded for the eventualBG crater.
     *
     * DECODED: the equation's shape, and that disposal is proportional to insulin with no glucose term.
     * NOT DECODED: `half` (field `this+0x234` per submodel, so per-patient and not a global) and `I_ref`.
     * `half` is therefore a free parameter here, fitted against the binary's own measured behaviour;
     * `F01` is Hovorka's and `SI` comes from the profile ISF via the bolus integral.
     */
    override fun derivative(s: DoubleArray, u: Double): DoubleArray {
        val d = super.derivative(s, u)          // insulin + gut subsystems unchanged; d[0] has the flux
        val ins = s[7]
        val ug = s[9] / p.tMaxG                 // gut appearance, as Hovorka
        val egp = egp0 * Math.pow(2.0, -ins / egpHalfMuPerL)
        d[0] = egp - p.f01 - si * ins + ug + s[DIST]
        d[1] = 0.0                              // Q2 is unused; freeze it
        return d
    }

    /** No glucose equilibrium exists (dG/dt is an integrator), so settle insulin then place glucose. */
    override fun steadyState(u: Double, minutes: Int): DoubleArray {
        val s = super.steadyState(u, minutes)
        s[0] = max(0.0, p.vg * targetMmol)
        s[1] = 0.0
        return s
    }
}

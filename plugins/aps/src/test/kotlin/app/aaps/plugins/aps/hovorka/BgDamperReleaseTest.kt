package app.aaps.plugins.aps.hovorka

import org.junit.jupiter.api.Test

/**
 * CONTRACT for releasing the current-BG damper (HovorkaMpc.bgDamperReleased).
 *
 * The damper scales the ABOVE-nominal portion of the dose by (G - target)/band, so below target it is
 * identically zero and the controller cannot dose above nominal at all. On 2026-09-09 that pinned basal
 * at nominal 0.49 U/hr for 30 minutes while the optimiser asked for a mean 1.24 through a dawn rise from
 * 5.3 to 7.3 — 82% of that morning's shortfall.
 *
 * Releasing it is only safe because the CALLER gates it on "rising AND no low in 4 h"
 * (HovorkaMpcPlugin.damperReleaseAllowed); these tests pin what the release itself may and may not do,
 * so a future change cannot quietly turn it into a general loosening of the dose.
 */
class BgDamperReleaseTest {

    private val target = 7.0
    private val weight = 67.0
    private val nominalUhr = 0.49
    private val nominalMuMin = nominalUhr * 1000.0 / 60.0

    private fun model() = HovorkaModel(
        HovorkaParams.personalize(weight, 2.3 * 18.0, 9.2, nominalUhr, target, tMaxGmin = 90.0))

    /** A state sitting at [gMmol] with [carbsG] still in the gut, so the rollout predicts a rise. */
    private fun risingState(m: HovorkaModel, gMmol: Double, carbsG: Double): DoubleArray {
        val s = m.steadyState(nominalMuMin).copyOf()
        s[0] = gMmol * m.p.vg
        return m.addMeal(s, carbsG)
    }

    private fun rate(m: HovorkaModel, s: DoubleArray, released: Boolean) = HovorkaMpc(
        m, targetMmol = target, horizonMin = 180,
        nominalBasalMuPerMin = nominalMuMin, maxBasalMuPerMin = 2.0 * 1000.0 / 60.0,
        refTauFastMin = 60.0, refTauSlowMin = 180.0, refBreakMmol = 13.0,
        bgDamperBandMmol = 3.0, bgDamperReleased = released, allowFullSuspend = true
    ).decide(s).basalUPerHr

    @Test
    fun `below target the damper pins at nominal and the release lifts it`() {
        val m = model()
        val s = risingState(m, 6.0, 40.0)
        val damped = rate(m, s, released = false)
        val freed = rate(m, s, released = true)
        assert(damped <= nominalUhr + 0.01) {
            "damper should pin at nominal below target, gave $damped"
        }
        assert(freed > damped + 0.1) {
            "release did not lift the rate below target: $damped -> $freed"
        }
    }

    @Test
    fun `the release can only ever raise the rate, never lower it`() {
        // The damper is a one-sided attenuation. If releasing it ever REDUCED a dose, it would be
        // changing something other than the damper, and every safety argument made from the base rates
        // would be void.
        val m = model()
        for (g in listOf(4.5, 5.5, 6.0, 6.5, 7.0, 8.0, 9.0, 10.5, 13.0)) {
            for (carbs in listOf(0.0, 20.0, 60.0)) {
                val s = risingState(m, g, carbs)
                val damped = rate(m, s, released = false)
                val freed = rate(m, s, released = true)
                assert(freed >= damped - 1e-6) { "release LOWERED the rate at G=$g carbs=$carbs: $damped -> $freed" }
            }
        }
    }

    @Test
    fun `the release changes nothing once glucose is past the damper band`() {
        // At target+band the damper is already fully open, so the release must be a no-op there. If it
        // is not, it is doing something beyond removing the damper.
        val m = model()
        for (g in listOf(10.5, 12.0, 15.0)) {
            val s = risingState(m, g, 30.0)
            assert(kotlin.math.abs(rate(m, s, false) - rate(m, s, true)) < 1e-6) {
                "release altered the rate at G=$g, above the damper band"
            }
        }
    }

    @Test
    fun `the release never overrides the hypo suspend`() {
        // The hard suspend is the last line and sits below the damper in decide(). A release must not
        // reach past it, whatever the rollout wants.
        val m = model()
        for (g in listOf(3.5, 3.9)) {
            val s = risingState(m, g, 80.0)
            assert(rate(m, s, released = true) == 0.0) { "release passed insulin at G=$g" }
        }
    }
}

package app.aaps.plugins.aps.hovorka

import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * CONTRACT: the rate the controller treats as NEUTRAL must actually hold target in the model it rolls out on.
 *
 * `HovorkaParams.personalize` solves egp0 so that the basal it is GIVEN holds [target]. HovorkaMpcPlugin
 * hands the resulting model's nominal rate to three places — the EKF's initial steady state, the MPC's
 * effort origin, and the correction floor's centre — all via `operatingBasalUhr`, which TddAdapter can move
 * away from the profile basal.
 *
 * Until 2026-08-23 the model was anchored to the PROFILE basal while nominal came from the ADAPTER, so
 * whenever 2d moved the operating point the two silently disagreed:
 *     profile 0.45 anchored, nominal 0.28  ->  nominal held 11.4 mmol/L
 *     profile 0.55 anchored, nominal 0.30  ->  nominal held 13.8 mmol/L
 * The controller then rolled out a rise that was pure artefact and dosed to stop it, while its effort term
 * penalised the very insulin needed to reach target. Live consequence: the 3 h forecast ran high in
 * proportion to insulin on board, and on 2026-08-23 02:00 it printed `eventual 11.6` — approximately the
 * steady state at nominal — while glucose was falling 2.1 mmol/L/h, and dosed 1.66 U/hr into it.
 *
 * There was no test. This is it.
 */
class HovorkaNominalAnchorTest {

    private val target = 7.0
    private val weight = 67.0

    /** The model must be built with the SAME basal the controller uses as nominal. */
    private fun anchoredAt(operatingBasalUhr: Double, isfMmol: Double, icGPerU: Double): Double {
        val p = HovorkaParams.personalize(
            w = weight, isfMgdlPerU = isfMmol * 18.0, icGPerU = icGPerU,
            basalUPerHr = operatingBasalUhr, targetMmol = target, tMaxGmin = 90.0
        )
        val m = HovorkaModel(p)
        return m.glucoseMmol(m.steadyState(operatingBasalUhr * 1000.0 / 60.0))
    }

    @Test
    fun `nominal basal holds target for every operating point the adapter can choose`() {
        // TddAdapter clamps to +12% / -20% per day off the profile basal, compounding over days; sweep well
        // past that in both directions, across this user's profile blocks.
        for ((isf, ic) in listOf(2.6 to 10.0, 2.4 to 9.2, 2.3 to 9.5)) {
            for (operating in listOf(0.15, 0.20, 0.28, 0.30, 0.35, 0.45, 0.55, 0.70, 0.90)) {
                val held = anchoredAt(operating, isf, ic)
                assert(abs(held - target) < 0.25) {
                    "nominal $operating U/hr (ISF $isf, IC $ic) holds $held mmol/L, target $target — " +
                        "the controller's neutral rate is not neutral in its own model"
                }
            }
        }
    }

    @Test
    fun `anchoring to the profile basal while running a different nominal is what the bug looked like`() {
        // Regression witness, not an aspiration: reproduce the OLD wiring and assert it really was broken,
        // so this test fails loudly if someone reinstates it thinking it is harmless.
        val profileAnchored = HovorkaModel(
            HovorkaParams.personalize(weight, 2.3 * 18.0, 9.2, 0.45, target, tMaxGmin = 90.0)
        )
        val heldAtAdapterNominal = profileAnchored.glucoseMmol(profileAnchored.steadyState(0.28 * 1000.0 / 60.0))
        assert(heldAtAdapterNominal > 10.0) {
            "expected the old mis-anchored wiring to park glucose above 10 mmol/L at nominal 0.28; got $heldAtAdapterNominal"
        }
        // ...and that the fix removes it.
        assert(abs(anchoredAt(0.28, 2.3, 9.2) - target) < 0.25)
    }
}

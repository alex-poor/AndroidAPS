package app.aaps.plugins.aps.camaps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * CONTRACT for the decoded ceiling, `MPC::MaximumPersonalRange` (vaddr 0x46b08).
 *
 * This exists because the value it replaced — a flat `2.55 x profile basal` — was fitted to probes of
 * the real binary that all used FLAT basal profiles. With a flat profile the rule's TDD term, its
 * 24h-mean term and its current-block term all coincide and it collapses to `mult x 0.85 x basal`,
 * which at mult = 3.0 is 2.55. The number reproduced nine measurements exactly and was still the wrong
 * rule: the multiplier is tiered on glucose, the base is keyed to TDD, and the blend uses the current
 * block. A probe-fitted constant that happens to match is the failure mode these tests guard.
 *
 * The nine `reproduces every measured ceiling point` cases are the real controller's own answers, taken
 * from stage3/camaps_reference.csv (kind=ceiling), each carrying the binary's own sanity verdict.
 */
class MaximumPersonalRangeTest {

    /** The pump quantises to 0.05 U/h; the measured values are post-quantisation. */
    private fun q(x: Double) = (x / 0.05).roundToInt() * 0.05

    @Test
    fun `reproduces every measured ceiling point`() {
        // profile basal U/h -> rate the real binary commanded at BG 20 rising hard, flat profile
        val measured = mapOf(
            0.20 to 0.50, 0.30 to 0.75, 0.45 to 1.15, 0.60 to 1.55, 0.85 to 2.15,
            1.20 to 3.05, 1.60 to 4.10, 2.40 to 6.10, 3.20 to 8.15
        )
        for ((basal, expected) in measured) {
            // flat profile: mean48 == basal(now), and TDD is basal-only so the 0.7*mean term wins
            val ceiling = CamapsMpc.maximumPersonalRange(
                cgmMmol = 20.0, tddU = basal * 24.0, meanBasal = basal, basalNow = basal)
            assertEquals(expected, q(ceiling), 1e-9,
                "basal $basal: decoded rule gives $ceiling, binary commanded $expected")
        }
    }

    @Test
    fun `multiplier is tiered on glucose, not constant`() {
        fun at(cgm: Double) = CamapsMpc.maximumPersonalRange(cgm, 24.0, 1.0, 1.0)
        assertEquals(2.0 * 0.85, at(6.0), 1e-9)    // <= 8
        assertEquals(2.5 * 0.85, at(10.0), 1e-9)   // 8 .. 12
        assertEquals(3.0 * 0.85, at(14.0), 1e-9)   // > 12
        assertTrue(at(8.5) > at(7.5), "the 8.0 boundary must raise the ceiling")
        assertTrue(at(12.5) > at(11.5), "the 12.0 boundary must raise the ceiling")
    }

    @Test
    fun `boundaries are exclusive as in the binary`() {
        // fcmp/fcsel with `hi`: strictly greater moves up a tier, equal does not
        assertEquals(2.0 * 0.85, CamapsMpc.maximumPersonalRange(8.0, 24.0, 1.0, 1.0), 1e-9)
        assertEquals(2.5 * 0.85, CamapsMpc.maximumPersonalRange(12.0, 24.0, 1.0, 1.0), 1e-9)
    }

    @Test
    fun `missing CGM falls back to 5_5 and so to the lowest tier`() {
        val absent = CamapsMpc.maximumPersonalRange(0.0, 24.0, 1.0, 1.0)
        val atFallback = CamapsMpc.maximumPersonalRange(CamapsMpc.CGM_FALLBACK_MMOL, 24.0, 1.0, 1.0)
        assertEquals(atFallback, absent, 1e-9)
        assertEquals(2.0 * 0.85, absent, 1e-9)
    }

    @Test
    fun `TDD term dominates once TDD exceeds 35x the mean basal`() {
        // base = max(0.48*tdd/24, 0.7*mean) -> the TDD term wins when tdd/mean > 0.7*24/0.48 = 35
        val mean = 0.85
        val below = CamapsMpc.maximumPersonalRange(14.0, 30.0 * mean, mean, mean)
        val above = CamapsMpc.maximumPersonalRange(14.0, 40.0 * mean, mean, mean)
        // both still blend with b = mult*basalNow, so the expectation is (a + b)/2, not a
        assertEquals(0.5 * (3.0 * 0.7 * mean + 3.0 * mean), below, 1e-9)      // 0.7*mean is the base
        assertTrue(above > below, "a real TDD must raise the ceiling above the basal-only case")
        assertEquals(0.5 * (3.0 * 0.48 * 40.0 * mean / 24.0 + 3.0 * mean), above, 1e-9)
    }

    @Test
    fun `a dawn-varying profile is not 2_55x the current block`() {
        // the artefact this replaced: with mean != basalNow the old constant is simply wrong
        val meanBasal = 0.60
        val basalNow = 1.10                       // dawn block, well above the 24h mean
        val ceiling = CamapsMpc.maximumPersonalRange(14.0, meanBasal * 24.0, meanBasal, basalNow)
        val oldConstant = 2.55 * basalNow
        assertTrue(abs(ceiling - oldConstant) > 0.2,
            "decoded $ceiling vs fitted constant $oldConstant — these must not coincide off a flat profile")
        // a = 3*0.7*0.60 = 1.26 ; b = 3*1.10 = 3.30 ; a < b -> (a+b)/2
        assertEquals(0.5 * (3.0 * 0.7 * meanBasal + 3.0 * basalNow), ceiling, 1e-9)
    }
}

package app.aaps.plugins.aps.camaps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CONTRACT for `MPC::ModifyRateGlucoseRate`'s attenuation (0x46f10) — the real controller's entire
 * trend response, which sits on the optimiser's OUTPUT rather than in its cost function.
 *
 * These are the properties that make it a safety layer, and they are what the fork's six separate
 * containment guards were each approximating a piece of.
 */
class AttenuationTest {

    private fun att(slope: Double, cgm: Double) = CamapsMpc.attenuationPercent(slope, cgm)

    @Test
    fun `nothing is withheld until glucose falls faster than 1_2 per hour`() {
        for (s in listOf(3.0, 1.0, 0.0, -0.6, -1.0, -1.19))
            assertEquals(0.0, att(s, 10.0), 1e-9, "slope $s must not attenuate")
        assertTrue(att(-1.3, 10.0) > 0.0, "just past -1.2 must start attenuating")
    }

    @Test
    fun `low and falling fast is a full suspend`() {
        assertEquals(100.0, att(-2.4, 5.0), 1e-9)
        assertEquals(100.0, att(-3.0, 7.0), 1e-9)
        assertEquals(100.0, att(-5.0, 7.0), 1e-9)
    }

    @Test
    fun `attenuation increases as the fall steepens`() {
        var prev = -1.0
        for (s in listOf(-1.5, -1.8, -2.4, -3.0, -3.6)) {
            val a = att(s, 10.0)
            assertTrue(a > prev, "slope $s gave $a, not more than $prev")
            prev = a
        }
    }

    @Test
    fun `attenuation increases as glucose falls, at a fixed slope`() {
        var prev = -1.0
        for (g in listOf(15.0, 12.0, 10.0, 8.0, 6.0, 5.0)) {
            val a = att(-2.4, g)
            assertTrue(a >= prev, "BG $g gave $a, less than $prev at a higher BG")
            prev = a
        }
    }

    @Test
    fun `above 12 mmol per L only the glucose-independent term acts`() {
        // idx12 = clamp(raw3, 0, 10) is 0 at cgm >= 12, so idx9*idx12 drops out and idx10 alone remains
        assertEquals(att(-3.6, 12.0), att(-3.6, 15.0), 1e-9)
        assertEquals(att(-3.6, 12.0), att(-3.6, 20.0), 1e-9)
    }

    @Test
    fun `result is always a percentage`() {
        for (s in listOf(5.0, 0.0, -1.0, -4.0, -10.0, -50.0))
            for (g in listOf(2.0, 4.5, 8.0, 12.0, 25.0)) {
                val a = att(s, g)
                assertTrue(a in 0.0..100.0, "att($s, $g) = $a is not a percentage")
            }
    }
}

/**
 * CONTRACT for the glucose < 8.0 branch of `MPC::ModifyRateGlucoseRate` (0x472b8..0x474f0): a cap at
 * 0.2 x profile basal that REPLACES the attenuation rather than adding to it.
 *
 * The replacement is the part worth pinning. It is natural to assume a low-glucose rule can only make
 * the controller more cautious, and here it does the opposite: the binary returns straight after
 * capping, so it skips an attenuation that at low glucose falling fast would have reached 100% and
 * suspended outright. A replica that attenuates everywhere is MORE conservative than the real
 * controller in exactly this corner.
 */
class SustainedFallCapTest {

    @org.junit.jupiter.api.Test
    fun `the cap is 20 percent of profile basal and the attenuation would have been harsher`() {
        // at BG 7 falling 3.0 mmol/L/h the attenuation is a full suspend ...
        assertEquals(100.0, CamapsMpc.attenuationPercent(-3.0, 7.0), 1e-9)
        // ... so capping at 0.2 x basal instead leaves MORE insulin, not less
        assertTrue(CamapsMpc.SUSTAINED_FALL_CAP_FRAC > 0.0,
            "the cap must leave insulin running, otherwise it is indistinguishable from a suspend")
        assertEquals(0.2, CamapsMpc.SUSTAINED_FALL_CAP_FRAC, 1e-9)
    }

    @org.junit.jupiter.api.Test
    fun `thresholds match the binary immediates`() {
        assertEquals(8.0, CamapsMpc.SUSTAINED_FALL_GLUCOSE_MMOL, 1e-9)   // fmov s0, #8.0
        assertEquals(-1.2, CamapsMpc.SUSTAINED_FALL_SLOPE, 1e-9)         // 0xBF99999A
    }

    @org.junit.jupiter.api.Test
    fun `the onset slope is the same constant the attenuation turns on at`() {
        // both the sub-8 chain and raw9's zero crossing sit at -1.2 mmol/L/h
        assertEquals(0.0, CamapsMpc.attenuationPercent(CamapsMpc.SUSTAINED_FALL_SLOPE, 10.0), 1e-9)
    }
}

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

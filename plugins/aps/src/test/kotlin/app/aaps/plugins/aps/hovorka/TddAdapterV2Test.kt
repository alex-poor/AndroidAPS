package app.aaps.plugins.aps.hovorka

import org.junit.jupiter.api.Test

/**
 * CONTRACT for the 2d adaptive-gain layer.
 *
 * The predecessor had no test, and it shipped a defect that took two months to surface: its hypo
 * response was a binary daily flag (`tbrFrac > 0.01 || minGlucose < 3.5`) applied as a flat x0.90, and
 * on the user's real data that flag fires on **42 of 58 days**. A fixed multiplicative cut three days
 * in four is a ratchet, not a safety response — the operating point walked from 97% of profile to 54%
 * over three weeks, which removed 63% of the dawn basal and made the 30-min forecast worse.
 *
 * Every test below is a witness to one property that failure violated. They are written against
 * realistic days from that history, not round numbers, because the defect was invisible to round
 * numbers: a day with min 3.3 mmol/L and 3.1% below 3.9 IS this user's median day.
 */
class TddAdapterV2Test {

    private val weight = 67.0
    private val target = 7.0

    /** The user's median day: TDD ~29 U, mean 7.8, a brief dip to 3.3, 3.1% below 3.9. */
    private fun ordinaryDay(tdd: Double = 29.0) = TddAdapterV2.Day(tdd, meanG = 7.8, minG = 3.3, tbrFrac = 0.031)

    private fun gainAfter(days: List<TddAdapterV2.Day>): Double =
        TddAdapterV2(weight, targetMmol = target).also { it.foldTrailing(days) }.gain

    @Test
    fun `a stable patient having ordinary days keeps the titrated profile`() {
        // 28 days of the user's median day. Insulin need is not changing, so the gain must not move:
        // the operating point IS the titration until there is evidence against it.
        // The predecessor scored this same input at 0.90 per day compounding — 0.9^7 = 0.48.
        val gain = gainAfter(List(28) { ordinaryDay() })
        assert(gain in 0.95..1.05) {
            "stable patient, ordinary days -> gain $gain; a gain that moves without evidence is a ratchet"
        }
    }

    @Test
    fun `a brief daily low is not treated as evidence of over-delivery`() {
        // Same as above but with the dip a little deeper and longer - still an ordinary day by the
        // consensus target (<4% below 3.9). The old binary flag fired here; this must not.
        val gain = gainAfter(List(28) { TddAdapterV2.Day(29.0, meanG = 7.6, minG = 3.2, tbrFrac = 0.038) })
        assert(gain > 0.93) { "an ordinary day with a brief low cut the gain to $gain" }
    }

    @Test
    fun `genuinely excessive time below range still cuts the gain`() {
        // The safety arm has to survive making the response proportional - 12% below range is real.
        val gain = gainAfter(List(28) { TddAdapterV2.Day(29.0, meanG = 6.0, minG = 2.8, tbrFrac = 0.12) })
        assert(gain < 0.85) { "12% time-below-range only moved the gain to $gain" }
    }

    @Test
    fun `a sustained rise in total insulin raises the gain`() {
        // Baseline weeks at 25 U/day, the recent window at 35 U/day: a real change in requirement,
        // which is the ONLY thing this layer is supposed to respond to.
        val days = List(21) { ordinaryDay(25.0) } + List(7) { ordinaryDay(35.0) }
        val gain = gainAfter(days)
        assert(gain > 1.08) { "total insulin rose 40% and the gain only reached $gain" }
    }

    @Test
    fun `a sustained fall in total insulin lowers the gain`() {
        val days = List(21) { ordinaryDay(35.0) } + List(7) { ordinaryDay(25.0) }
        val gain = gainAfter(days)
        assert(gain < 0.92) { "total insulin fell 30% and the gain only reached $gain" }
    }

    @Test
    fun `the gain stays inside its bounds under absurd input`() {
        // Adaptation is a correction to a titration, never a replacement for it. Whatever the ledger
        // says, the operating point may not run away from the profile the user actually titrated.
        val collapse = gainAfter(List(21) { ordinaryDay(60.0) } + List(7) { ordinaryDay(1.0) })
        val explode = gainAfter(List(21) { ordinaryDay(1.0) } + List(7) { ordinaryDay(60.0) })
        assert(collapse >= 0.60) { "gain fell to $collapse, below the floor" }
        assert(explode <= 1.60) { "gain rose to $explode, above the cap" }
    }

    @Test
    fun `a thin ledger leaves the profile untouched`() {
        // Fewer scoreable days than the baseline needs: no evidence, no adaptation.
        assert(gainAfter(List(3) { ordinaryDay() }) == 1.0) { "adapted on a ledger too thin to mean anything" }
        assert(gainAfter(emptyList()) == 1.0) { "adapted on an empty ledger" }
    }

    @Test
    fun `the most recent completed day influences the gain`() {
        // foldTrailing used `size - k`, so its last recent window was subList(rf, size-1) and the most
        // recent day fell out of every fold. The gain was a day stale — which defeats the whole point of
        // the recency weights, half of whose mass sits on exactly the day being dropped. It went
        // unnoticed because a stale gain still looks plausible; only appending a day that MUST move it
        // exposes the silence.
        val steady = MutableList<TddAdapterV2.Day?>(28) { ordinaryDay() }
        val before = TddAdapterV2(weight, targetMmol = target).also { it.foldTrailing(steady) }.gain
        steady.add(TddAdapterV2.Day(29.0, meanG = 5.4, minG = 2.6, tbrFrac = 0.12))   // an unmissable day
        val after = TddAdapterV2(weight, targetMmol = target).also { it.foldTrailing(steady) }.gain
        assert(after < before) { "appending a 12%-below-range day left the gain at $before -> $after" }
    }

    @Test
    fun `unscoreable days are skipped rather than guessed`() {
        // Days without enough CGM arrive as null. They must not be invented, and must not stall the walk.
        val withGaps = List(28) { if (it % 5 == 0) null else ordinaryDay() }
        val gain = TddAdapterV2(weight, targetMmol = target).also { it.foldTrailing(withGaps) }.gain
        assert(gain in 0.95..1.05) { "CGM gaps moved the gain to $gain" }
    }
}

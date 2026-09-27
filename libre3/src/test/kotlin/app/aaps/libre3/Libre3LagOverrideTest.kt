package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Lag-override behaviour, driven by synthetic 1-minute streams so the slope/decay/expiry are exact.
 * Default config: window 5, arm ≥ 1 mg/dL/min, decel 0.5, half-life 15 min, max 30 min, τmax 15 min.
 */
class Libre3LagOverrideTest {

    private val MIN = 60_000L

    /** Feed a straight ramp of [n] readings at [ratePerMin] mg/dL/min starting at [start], from t=0. */
    private fun Libre3LagOverride.ramp(start: Int, ratePerMin: Int, n: Int): Long {
        var t = 0L
        for (i in 0 until n) { onReading(t, start + ratePerMin * i); t += MIN }
        return t - MIN                       // timestamp of the last reading fed
    }

    @Test
    fun `arms on a fast rise and applies close to the full gap at entry`() {
        val o = Libre3LagOverride()
        val tLast = o.ramp(start = 100, ratePerMin = 2, n = 5)   // 100..108, slope = 2.0
        val arm = o.armFromManualBg(tLast, manualMgdl = 108 + 20) // gap 20 (≤ 2×15 = 30)
        assertThat(arm).isInstanceOf(Libre3LagOverride.ArmResult.Armed::class.java)
        assertThat((arm as Libre3LagOverride.ArmResult.Armed).gapMgdl).isEqualTo(20.0)
        assertThat(arm.clamped).isFalse()

        // next reading, still rising at 2/min, 1 min later: offset ≈ gap × timeFactor(1min) ≈ 19
        val corrected = o.onReading(tLast + MIN, 110)
        assertThat(corrected).isIn(127..130)                      // 110 + ~19
        assertThat(o.isActive()).isTrue()
    }

    @Test
    fun `offset fades out and the override expires as the rise decelerates - before the CGM peak`() {
        val o = Libre3LagOverride()
        val tLast = o.ramp(start = 100, ratePerMin = 2, n = 5)    // slope 2.0
        o.armFromManualBg(tLast, 108 + 20)

        // rise flattens: readings hold at 108
        val t5 = o.onReading(tLast + 1 * MIN, 108)   // windowed slope 1.6 -> still active, reduced offset
        assertThat(t5).isGreaterThan(108)
        assertThat(o.isActive()).isTrue()

        val t6 = o.onReading(tLast + 2 * MIN, 108)   // windowed slope 1.0 = 0.5×entry -> expire, raw
        assertThat(t6).isEqualTo(108)
        assertThat(o.isActive()).isFalse()           // faded out while the CGM is still at/near its top
    }

    @Test
    fun `a falling trace expires immediately and returns raw`() {
        val o = Libre3LagOverride()
        val tLast = o.ramp(100, 2, 5)
        o.armFromManualBg(tLast, 128)
        // a clear drop pulls the windowed slope negative within a couple of readings
        o.onReading(tLast + MIN, 104)
        val v = o.onReading(tLast + 2 * MIN, 98)
        assertThat(o.isActive()).isFalse()
        assertThat(v).isEqualTo(98)
    }

    @Test
    fun `the hard max-duration cap drops the override even on a sustained rise`() {
        val cfg = Libre3LagOverride.Config(maxDurationMin = 10.0)
        val o = Libre3LagOverride(cfg)
        val tLast = o.ramp(100, 2, 5)
        o.armFromManualBg(tLast, 128)
        // keep rising at 2/min well past 10 min
        var last = 0
        for (i in 1..15) last = o.onReading(tLast + i * MIN, 108 + 2 * i)
        assertThat(o.isActive()).isFalse()
        assertThat(last).isEqualTo(108 + 2 * 15)     // raw, uncorrected, after the cap
    }

    @Test
    fun `a huge finger-prick gap is clamped to the physiological plausibility ceiling`() {
        val o = Libre3LagOverride()
        val tLast = o.ramp(100, 2, 5)                 // slope 2 -> max plausible gap = 2 × 15 = 30
        val arm = o.armFromManualBg(tLast, 108 + 80) as Libre3LagOverride.ArmResult.Armed
        assertThat(arm.gapMgdl).isEqualTo(30.0)
        assertThat(arm.clamped).isTrue()
    }

    @Test
    fun `will not arm when flat or rising too slowly`() {
        val flat = Libre3LagOverride().apply { ramp(120, 0, 5) }
        assertThat(flat.armFromManualBg(4 * MIN, 140)).isInstanceOf(Libre3LagOverride.ArmResult.Rejected::class.java)

        // 0.5 mg/dL/min is below the 1.0 arm threshold — CGM is accurate here, nothing to correct
        val slow = Libre3LagOverride()
        var t = 0L; for (v in listOf(100, 100, 101, 101, 102)) { slow.onReading(t, v); t += MIN }
        assertThat(slow.armFromManualBg(t - MIN, 120)).isInstanceOf(Libre3LagOverride.ArmResult.Rejected::class.java)
    }

    @Test
    fun `will not arm before it has a full window of evidence`() {
        val o = Libre3LagOverride()
        var t = 0L; for (v in listOf(100, 104, 108)) { o.onReading(t, v); t += MIN }   // only 3 ticks
        val arm = o.armFromManualBg(t - MIN, 130)
        assertThat(arm).isInstanceOf(Libre3LagOverride.ArmResult.Rejected::class.java)
        assertThat((arm as Libre3LagOverride.ArmResult.Rejected).reason).contains("5")
    }

    @Test
    fun `a finger-prick at or below the sensor is rejected - this is a rise-lag tool`() {
        val o = Libre3LagOverride()
        val tLast = o.ramp(100, 2, 5)
        assertThat(o.armFromManualBg(tLast, 108)).isInstanceOf(Libre3LagOverride.ArmResult.Rejected::class.java)
        assertThat(o.armFromManualBg(tLast, 100)).isInstanceOf(Libre3LagOverride.ArmResult.Rejected::class.java)
    }

    @Test
    fun `corrected value is clamped into the valid range`() {
        val o = Libre3LagOverride()
        val tLast = o.ramp(480, 2, 5)                 // near the top of the range, slope 2
        o.armFromManualBg(tLast, 488 + 30)            // gap 30
        val v = o.onReading(tLast + MIN, 495)         // 495 + ~30 would exceed 501
        assertThat(v).isEqualTo(501)
    }

    @Test
    fun `reset drops the override and clears the window`() {
        val o = Libre3LagOverride()
        val tLast = o.ramp(100, 2, 5)
        o.armFromManualBg(tLast, 128)
        assertThat(o.isActive()).isTrue()
        o.reset()
        assertThat(o.isActive()).isFalse()
        // window cleared -> cannot arm again until refilled
        assertThat(o.armFromManualBg(tLast + MIN, 130)).isInstanceOf(Libre3LagOverride.ArmResult.Rejected::class.java)
    }
}

package app.aaps.plugins.aps.hovorka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.min

/**
 * Adaptive-TDD (day-to-day gain) tests — opt-in, off by default. Starting from a profile basal that is
 * 30% too low, the adapter must lift last-3-day TIR over the static-gain baseline, converge (no windup /
 * oscillation), stay out of hypo, and — asymmetrically — cut the gain after a hypo day. Analogue of the
 * standalone `validateTddAdaptation`.
 */
class HovorkaMpcAdaptiveTest {

    @Tag("slow")
    @Test fun `adaptation lifts TIR, converges and stays safe`() {
        val rng = Random(2024)
        val days = 10
        val ctrl = HovorkaModel(HovorkaParams.forWeight(70.0))   // population controller (honest mismatch)
        val lastTirOff = ArrayList<Double>()
        val lastTirOn = ArrayList<Double>()
        val tbrOn = ArrayList<Double>()
        var worstMinOn = 99.0
        var stabilized = 0
        for (i in 1..12) {
            val w = 55.0 + rng.nextDouble() * 40.0
            val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
            val basalTrue = patient.basalForSteadyState(6.0)
            val startUhr = basalTrue * 60.0 / 1000.0 * 0.70       // profile 30% too low
            val startMu = startUhr * 1000.0 / 60.0
            val maxMu = basalTrue * 8.0
            val carbErr = 0.85 + rng.nextDouble() * 0.3
            val adapter = TddAdapter(w, startUhr, targetMmol = 6.0, maxBasalUhr = maxMu * 60.0 / 1000.0)
            val off = simulateMultiDayAdaptive(ctrl, patient, startMu, maxMu, STANDARD_MEALS, days = days, seed = 300L + i, carbAnnounceError = carbErr, adapter = null)
            val on = simulateMultiDayAdaptive(ctrl, patient, startMu, maxMu, STANDARD_MEALS, days = days, seed = 300L + i, carbAnnounceError = carbErr, adapter = adapter)
            fun last3Tir(r: MultiDayResult) = r.perDay.takeLast(3).map { it.tirPct }.average()
            lastTirOff += last3Tir(off)
            lastTirOn += last3Tir(on)
            tbrOn += on.perDay.takeLast(3).map { it.tbrPct }.average()
            worstMinOn = min(worstMinOn, on.perDay.minOf { it.minG })
            val last3Basal = on.basalByDayUhr.takeLast(3)
            if ((last3Basal.max() - last3Basal.min()) / last3Basal.average() < 0.12) stabilized++
        }
        val tOff = lastTirOff.average()
        val tOn = lastTirOn.average()

        assertThat(tOn).isGreaterThan(tOff + 2.0)        // lifts last-3-day TIR vs static gain
        assertThat(stabilized).isAtLeast(9)              // converges (no windup) for >= 9/12
        assertThat(worstMinOn).isGreaterThan(3.0)        // stays safe
        assertThat(tbrOn.average()).isLessThan(4.0)
    }

    @Test fun `a hypo day drives the gain down (asymmetric)`() {
        val a = TddAdapter(70.0, 1.0, targetMmol = 6.0, maxBasalUhr = 5.0)
        a.endOfDay(1.0, 6.2, 0.0, 5.0)         // a normal day
        val beforeHypo = a.operatingBasalUhr
        a.endOfDay(1.0, 5.5, 0.10, 2.6)        // a hypo day (10% below-range, min 2.6)
        val afterHypo = a.operatingBasalUhr
        assertThat(afterHypo).isLessThan(beforeHypo)
    }
}

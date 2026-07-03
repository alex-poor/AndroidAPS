package app.aaps.plugins.aps.hovorka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * SMB (microbolus) tests — the opt-in, off-by-default correction bolus. Meals are only 50% pre-bolused so
 * a genuine post-meal excursion appears that basal alone cannot fully correct. SMB must reduce the peak
 * WITHOUT increasing hypo, and must be fully suppressed at a low. Analogue of the standalone `validateSmb`.
 */
class HovorkaMpcSmbTest {

    @Tag("slow")
    @Test fun `SMB lowers the post-meal peak without adding hypo`() {
        val rng = Random(2024)
        val tbrOnly = ArrayList<LoopMetrics>()
        val withSmb = ArrayList<LoopMetrics>()
        val smbCapU = 1.5     // per-tick microbolus cap
        val maxIobU = 8.0     // SMB may not push SC IOB above this
        for (i in 1..20) {
            val w = 55.0 + rng.nextDouble() * 40.0
            val patient = HovorkaModel(HovorkaParams.randomPatient(rng, w))
            val basal = patient.basalForSteadyState(6.0)
            val basalUhr = basal * 60.0 / 1000.0
            val (isfMgdl, icG) = measurePatientIsfIc(patient, basal, rng, noise = 0.15)
            val carbErr = 0.85 + rng.nextDouble() * 0.3
            val seed = 100L + i
            val ctrl = HovorkaModel(HovorkaParams.personalize(w, isfMgdl, icG, basalUhr, 6.0))
            tbrOnly += simulateClosedLoop(
                ctrl, patient, basal, STANDARD_MEALS, seed = seed,
                carbAnnounceError = carbErr, bolusCoverage = 0.5
            )
            withSmb += simulateClosedLoop(
                ctrl, patient, basal, STANDARD_MEALS, seed = seed,
                carbAnnounceError = carbErr, bolusCoverage = 0.5,
                enableSmb = true, smbCapU = smbCapU, maxIobU = maxIobU
            )
        }
        val peakTbr = tbrOnly.map { it.maxG }.average()
        val peakSmb = withSmb.map { it.maxG }.average()
        val tirTbr = tbrOnly.map { it.tirPct }.average()
        val tirSmb = withSmb.map { it.tirPct }.average()
        val worstMinSmb = withSmb.minOf { it.minG }
        val severeSmb = withSmb.count { it.severeHypoPct > 0.0 }

        assertThat(peakSmb).isLessThan(peakTbr - 0.3)   // real peak reduction
        assertThat(tirSmb).isGreaterThan(tirTbr)        // and a TIR win
        assertThat(severeSmb).isEqualTo(0)              // never at the cost of a severe hypo
        assertThat(worstMinSmb).isGreaterThan(3.0)      // worst-case min still bounded
    }

    @Test fun `SMB is suppressed at a hypo`() {
        val m = HovorkaModel(HovorkaParams.forWeight(70.0))
        val basal = m.basalForSteadyState(6.0)
        val mpc = HovorkaMpc(
            model = m, targetMmol = 6.0,
            nominalBasalMuPerMin = basal, maxBasalMuPerMin = basal * 8,
            enableSmb = true, maxSmbU = 2.0
        )
        val lowState = m.steadyState(basal).also { it[0] = 3.4 * m.p.vg }  // est.G ≈ 3.4 (below suspend)
        val d = mpc.decide(lowState)
        assertThat(d.smbU).isEqualTo(0.0)
        assertThat(d.basalMuPerMin).isEqualTo(0.0)
    }
}

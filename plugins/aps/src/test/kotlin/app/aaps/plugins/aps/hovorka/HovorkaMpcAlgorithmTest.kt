package app.aaps.plugins.aps.hovorka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-JVM unit tests for the Hovorka control core (no Android, no mocks) — the model, and the MPC
 * decision law. These mirror the standalone in-silico validation harness; the full virtual-patient
 * cohort run (TIR / no-severe-hypo) is a TODO to port here as a longer-running test.
 *
 * TODO(upstream): port the cohort closed-loop validation (mean TIR, worst-case min, TBR%) from the
 * standalone module so CI exercises the safety envelope, not just the point behaviours below.
 */
class HovorkaMpcAlgorithmTest {

    private fun model() = HovorkaModel(HovorkaParams.forWeight(70.0))

    @Test fun `model steady state is finite and monotone in insulin`() {
        val m = model()
        // Calibration-independent physiology: a fixed basal settles to a finite, positive glucose, and
        // LESS insulin settles HIGHER (insulin lowers glucose). Absolute values depend on personalisation.
        val gLowBasal = m.glucoseMmol(m.steadyState(8.0))    // ~0.48 U/hr
        val gHighBasal = m.glucoseMmol(m.steadyState(15.0))  // ~0.90 U/hr
        assertThat(gLowBasal).isGreaterThan(0.0)
        assertThat(gLowBasal.isFinite()).isTrue()
        assertThat(gHighBasal.isFinite()).isTrue()
        assertThat(gLowBasal).isGreaterThan(gHighBasal)
    }

    @Test fun `MPC demands no more insulin when low than when high`() {
        val m = model()
        val nominalMuPerMin = 15.0
        val mpc = HovorkaMpc(
            model = m,
            targetMmol = 6.0,
            nominalBasalMuPerMin = nominalMuPerMin,
            maxBasalMuPerMin = 60.0
        )
        val ss = m.steadyState(nominalMuPerMin)

        val low = ss.copyOf().also { it[0] = 4.0 * m.p.vg }    // 4.0 mmol/L
        val high = ss.copyOf().also { it[0] = 12.0 * m.p.vg }  // 12.0 mmol/L
        val dLow = mpc.decide(low)
        val dHigh = mpc.decide(high)

        // Monotonic sanity: a high glucose must not be dosed less than a low one.
        assertThat(dHigh.basalMuPerMin).isAtLeast(dLow.basalMuPerMin)
        // The decision reports the glucose it acted on.
        assertThat(dLow.glucoseMmol).isWithin(0.3).of(4.0)
        assertThat(dHigh.glucoseMmol).isWithin(0.3).of(12.0)
    }

    @Test fun `MPC forces zero basal at a hypo`() {
        val m = model()
        val nominalMuPerMin = 15.0
        val mpc = HovorkaMpc(
            model = m,
            targetMmol = 6.0,
            nominalBasalMuPerMin = nominalMuPerMin,
            maxBasalMuPerMin = 60.0
        )
        val ss = m.steadyState(nominalMuPerMin)
        val hypo = ss.copyOf().also { it[0] = 3.5 * m.p.vg }   // below hypoSuspendMmol (3.9)
        val d = mpc.decide(hypo)
        assertThat(d.basalMuPerMin).isEqualTo(0.0)
        assertThat(d.smbU).isEqualTo(0.0)
    }
}

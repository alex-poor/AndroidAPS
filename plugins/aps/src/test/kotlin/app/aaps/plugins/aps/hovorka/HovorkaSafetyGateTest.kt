package app.aaps.plugins.aps.hovorka

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HovorkaSafetyGateTest {

    @Test
    fun `normal closed loop passes through untouched`() {
        val r = HovorkaSafetyGate.apply(rateUhr = 1.8, smbU = 0.4, maxIobU = 8.0, neutralUhr = 0.5)
        assertThat(r.rateUhr).isEqualTo(1.8)
        assertThat(r.smbU).isEqualTo(0.4)
        assertThat(r.note).isEmpty()
    }

    @Test
    fun `maxIOB zero (LGS) caps a high temp at neutral and drops the SMB`() {
        val r = HovorkaSafetyGate.apply(rateUhr = 2.4, smbU = 0.3, maxIobU = 0.0, neutralUhr = 0.5)
        assertThat(r.rateUhr).isEqualTo(0.5)
        assertThat(r.smbU).isEqualTo(0.0)
        assertThat(r.note).contains("MAXIOB-0")
    }

    @Test
    fun `maxIOB zero still allows a LOW temp or suspend`() {
        val r = HovorkaSafetyGate.apply(rateUhr = 0.0, smbU = 0.0, maxIobU = 0.0, neutralUhr = 0.5)
        assertThat(r.rateUhr).isEqualTo(0.0)
        assertThat(r.note).isEmpty()
    }

    @Test
    fun `NaN or infinite output becomes neutral basal with no SMB`() {
        listOf(Double.NaN to 0.0, Double.POSITIVE_INFINITY to 0.0, 1.0 to Double.NaN).forEach { (rate, smb) ->
            val r = HovorkaSafetyGate.apply(rate, smb, maxIobU = 8.0, neutralUhr = 0.45)
            assertThat(r.rateUhr).isEqualTo(0.45)
            assertThat(r.smbU).isEqualTo(0.0)
            assertThat(r.note).contains("NON-FINITE")
        }
    }

    @Test
    fun `neutral basal is the lower of profile and operating`() {
        assertThat(HovorkaSafetyGate.neutralBasal(0.55, 0.30)).isEqualTo(0.30)
        assertThat(HovorkaSafetyGate.neutralBasal(0.40, 0.60)).isEqualTo(0.40)
        assertThat(HovorkaSafetyGate.neutralBasal(0.40, Double.NaN)).isEqualTo(0.40)
    }
}

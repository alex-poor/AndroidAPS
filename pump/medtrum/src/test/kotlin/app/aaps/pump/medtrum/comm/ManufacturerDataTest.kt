package app.aaps.pump.medtrum.comm

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ManufacturerDataTest {

    @Test
    fun `a record too short for serial, type and version is rejected, not thrown`() {
        // Any nearby device can advertise Medtrum's manufacturer ID; this used to crash the scan callback.
        (0 until ManufacturerData.MIN_SIZE).forEach { n ->
            assertThat(ManufacturerData.parse(ByteArray(n))).isNull()
        }
    }

    @Test
    fun `a full record parses`() {
        val md = ManufacturerData.parse(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06))
        assertThat(md).isNotNull()
        assertThat(md!!.getDeviceType()).isEqualTo(5)
        assertThat(md.getVersion()).isEqualTo(6)
    }
}

package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.YpsoCrc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YpsoCrcTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Pins the CRC16 algorithm to a fixed vector. (The implementation was developed and verified
     * against real YpsoPump traffic — 234 captured frames CRC-validated — but the committed vector
     * is synthetic so no real pump/therapy data is published.)
     */
    @Test
    fun `crc16 matches a known vector`() {
        val payload = hex("000102030405060708090a0b0c0d0e0f10")
        assertArrayEquals(hex("1e8f"), YpsoCrc.crc16(payload))
        assertTrue(YpsoCrc.isValid(payload + hex("1e8f")))
    }

    @Test
    fun `appendCrc then isValid round-trips`() {
        assertTrue(YpsoCrc.isValid(YpsoCrc.appendCrc(hex("0102030405"))))
    }

    @Test
    fun `isValid rejects a corrupted trailer`() {
        val p = YpsoCrc.appendCrc(hex("aabbccdd"))
        p[p.size - 1] = (p[p.size - 1] + 1).toByte()
        assertFalse(YpsoCrc.isValid(p))
    }
}

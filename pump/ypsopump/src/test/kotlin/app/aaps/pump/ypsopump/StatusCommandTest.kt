package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusCommandTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Parses the 18-byte SYSTEM_STATUS body (CRC already stripped). Layout verified against a real
     * pump; this vector is synthetic (offsets aren't sensitive):
     *   @0=00 | @1..4 insulin u32 LE | @5 mode | @6 battery | @7..9 0 | @10..13 tbr% u32 LE | @14..17 0
     */
    @Test
    fun `decodes 18-byte status (insulin@1, battery@6, tbr@10)`() {
        // mode=1 (Basal), insulin=0x0226=550 -> 5.50U, battery=0x55=85, tbr=0x64=100
        val body = hex("00" + "26020000" + "01" + "55" + "000000" + "64000000" + "00000000")
        assertEquals(18, body.size)
        val cmd = StatusCommand().apply { decode(body) }
        assertTrue(cmd.success)
        assertEquals(5.5, cmd.reservoirUnits, 0.0001)
        assertEquals(85, cmd.batteryPercent)
        assertEquals(100, cmd.activeTbrPercent)
        assertEquals(1, cmd.deliveryMode)
        assertEquals("Basal", cmd.deliveryModeName)
        assertFalse(cmd.isSuspended)
    }

    @Test
    fun `stopped mode is suspended`() {
        // mode@5 = 0 (Stopped), battery@6 = 0x64 = 100
        val body = hex("00" + "00000000" + "00" + "64" + "000000" + "64000000" + "00000000")
        val cmd = StatusCommand().apply { decode(body) }
        assertTrue(cmd.success)
        assertTrue(cmd.isSuspended)
        assertEquals("Stopped", cmd.deliveryModeName)
        assertEquals(100, cmd.batteryPercent)
    }

    @Test
    fun `short body is an error`() {
        val cmd = StatusCommand().apply { decode(byteArrayOf(0x88.toByte())) }
        assertFalse(cmd.success)
    }
}

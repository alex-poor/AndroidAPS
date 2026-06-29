package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.comm.YpsoFraming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class YpsoFramingTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Golden vector: a real 6-frame status read captured from the pump reassembles exactly. */
    @Test
    fun `reassembles a real 6-frame pump read`() {
        val frames = listOf(
            "16cc44aa9908e5f3ed057e1b95d0694b762524eb",
            "266df12e6d701a24ba89a15e059a5b46575754f4",
            "3604bc131b7400f802478307eaa6c57b099a9f80",
            "469afb5b245a76d1c1f00f4c007a1ce870bc6b5b",
            "56a3ee812c2a1888b9a437f724905769e374e0f1",
            "66fc"
        ).map { hex(it) }

        assertEquals(6, YpsoFraming.getTotalFrames(frames[0][0]))
        val reassembled = YpsoFraming.parseMultiFrameRead(frames)
        assertEquals(96, reassembled.size)
        assertArrayEquals(
            hex(
                "cc44aa9908e5f3ed057e1b95d0694b762524eb6df12e6d701a24ba89a15e059a5b4" +
                "6575754f404bc131b7400f802478307eaa6c57b099a9f809afb5b245a76d1c1f00f4" +
                "c007a1ce870bc6b5ba3ee812c2a1888b9a437f724905769e374e0f1fc"
            ),
            reassembled
        )
    }

    @Test
    fun `chunk then parse round-trips with correct headers`() {
        val data = ByteArray(50) { it.toByte() }   // 50 bytes -> 19 + 19 + 12
        val frames = YpsoFraming.chunkPayload(data)
        assertEquals(3, frames.size)
        assertEquals(0x13, frames[0][0].toInt() and 0xFF) // (1 shl 4) or 3
        assertEquals(0x23, frames[1][0].toInt() and 0xFF) // (2 shl 4) or 3
        assertEquals(0x33, frames[2][0].toInt() and 0xFF) // (3 shl 4) or 3
        assertArrayEquals(data, YpsoFraming.parseMultiFrameRead(frames))
    }
}

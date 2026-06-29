package app.aaps.pump.ypsopump.comm

/**
 * YpsoPump BLE multi-frame protocol.
 *
 * Each BLE write/read carries at most 20 bytes: 1 header byte + up to 19 payload bytes. A logical
 * message (e.g. an encrypted command/response, which is 40+ bytes) is therefore split across
 * several frames. The header byte is:
 *
 *     header = ((frameIndex + 1) shl 4 and 0xF0) or (totalFrames and 0x0F)
 *
 * i.e. the high nibble is the 1-based frame number and the low nibble is the total frame count.
 *
 * Verified against real YpsoPump BLE traffic — a captured 6-frame status read reassembles exactly
 * (see YpsoFramingTest and report/aaps-driver-audit.md C3).
 */
object YpsoFraming {

    private const val MAX_PAYLOAD_PER_FRAME = 19

    /** Split [data] into BLE frames, each prefixed with its header byte. */
    fun chunkPayload(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return listOf(byteArrayOf(0x10))
        val totalFrames = maxOf(1, (data.size + MAX_PAYLOAD_PER_FRAME - 1) / MAX_PAYLOAD_PER_FRAME)
        val frames = ArrayList<ByteArray>(totalFrames)
        for (idx in 0 until totalFrames) {
            val start = idx * MAX_PAYLOAD_PER_FRAME
            val end = minOf(start + MAX_PAYLOAD_PER_FRAME, data.size)
            val header = (((idx + 1) shl 4) and 0xF0) or (totalFrames and 0x0F)
            frames.add(byteArrayOf(header.toByte()) + data.copyOfRange(start, end))
        }
        return frames
    }

    /** Reassemble received [frames] into the original payload (drops the per-frame header byte). */
    fun parseMultiFrameRead(frames: List<ByteArray>): ByteArray {
        val merged = ArrayList<Byte>()
        for (frame in frames) if (frame.size > 1) merged.addAll(frame.drop(1))
        return merged.toByteArray()
    }

    /** Total frame count encoded in the low nibble of the first frame's header byte (min 1). */
    fun getTotalFrames(firstByte: Byte): Int =
        (firstByte.toInt() and 0x0F).let { if (it == 0) 1 else it }
}

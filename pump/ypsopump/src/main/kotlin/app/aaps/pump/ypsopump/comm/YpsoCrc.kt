package app.aaps.pump.ypsopump.comm

/**
 * CRC16 for the YpsoPump protocol.
 *
 * Algorithm: CRC-32 with polynomial 0x04C11DB7 computed over the input reordered into
 * 4-byte-reversed blocks ("bitstuffing"); the low 16 bits are returned as 2 little-endian bytes.
 * The pump appends this CRC to a payload before encryption and includes it on responses, so a
 * command must be CRC-wrapped before [SessionCrypto.encrypt] and a decrypted response must be
 * CRC-checked (and the 2 trailing bytes stripped) before parsing.
 *
 * Verified against real YpsoPump BLE traffic — a captured history-entry payload CRCs to the exact
 * 2 bytes the pump sent (see YpsoCrcTest and report/aaps-driver-audit.md C2).
 */
object YpsoCrc {

    private const val CRC_POLY = 0x04C11DB7L

    private val TABLE = LongArray(256).also { table ->
        for (idx in 0 until 256) {
            var v = idx.toLong() shl 24
            repeat(8) {
                v = if (v and 0x80000000L != 0L) ((v shl 1) and 0xFFFFFFFFL) xor CRC_POLY
                else (v shl 1) and 0xFFFFFFFFL
            }
            table[idx] = v
        }
    }

    private fun bitstuff(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val blocks = (data.size + 3) / 4
        val stuffed = ByteArray(blocks * 4)
        for (block in 0 until blocks) {
            val base = block * 4
            for (i in 0 until 4) {
                val src = base + i
                stuffed[base + 3 - i] = if (src < data.size) data[src] else 0
            }
        }
        return stuffed
    }

    /** CRC16 of [payload] as 2 little-endian bytes. */
    fun crc16(payload: ByteArray): ByteArray {
        var crc = 0xFFFFFFFFL
        for (byte in bitstuff(payload)) {
            val tableIdx = ((crc shr 24) xor (byte.toLong() and 0xFF)) and 0xFF
            crc = ((crc shl 8) and 0xFFFFFFFFL) xor TABLE[tableIdx.toInt()]
        }
        val result = (crc and 0xFFFFL).toInt()
        return byteArrayOf((result and 0xFF).toByte(), ((result shr 8) and 0xFF).toByte())
    }

    /** True if the last 2 bytes of [payload] are a valid CRC16 of the preceding bytes. */
    fun isValid(payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        val data = payload.copyOfRange(0, payload.size - 2)
        val crc = payload.copyOfRange(payload.size - 2, payload.size)
        return crc16(data).contentEquals(crc)
    }

    /** [payload] with its CRC16 appended. */
    fun appendCrc(payload: ByteArray): ByteArray = payload + crc16(payload)
}

package app.aaps.libre3

/**
 * The SECOND NFC activation response — Juggluco's `struct nfc2`.
 *
 * This is the piece a Libre 3 activation produces that AAPS then needs to run the sensor over BLE:
 * the peripheral MAC, the 4-byte BLE PIN, and the activation (start) time. Until this parser existed
 * a sensor could only be *started* with Juggluco (which owns this logic) and the credentials imported
 * — it was the last research blocker to native sensor-start (report/libre3-native-plan.md §4b).
 *
 * Layout transcribed from Juggluco's `struct nfc2` (`Common/src/main/cpp/libre3/nfc.cpp`, GPL-3.0),
 * 19 bytes packed:
 * ```
 *  0  uint8    zero              always 0x00
 *  1  uint8[2] response          recognition marker
 *  3  uint8[6] deviceAddress     BLE MAC, stored REVERSED (mkdeviceaddressstr prints [5]..[0])
 *  9  uint32   pin               the BLE PIN, little-endian
 * 13  uint32   activationTime    sensor start, unix seconds, little-endian; 0 => "not set, use now"
 * 17  uint8[2] crc16
 * ```
 * The error variant `struct nfc2error` is a different, 4-byte struct: `zero(1) recogn(2) error(1)`,
 * with `recogn == 0x01A5`. Juggluco dispatches purely on length (19 = nfc2, 4 = nfc2error).
 *
 * VALIDATED against a real capture (2026-09-22, sensor E07A-XX0TM9UWUJX): the MAC
 * (`A0:7E:16:2F:9C:53`) and `activationTime` (`1790057703`, the sensor's true 18:15 start) parse
 * byte-exact. That capture was from a *rejected* scan, so its `pin` field is provisional — but the
 * two fields that have a ground truth confirm the struct alignment, so `pin` (same struct, next
 * slot, read identically) yields the real BLE PIN on a successful activation.
 *
 * The wire order of the pin bytes equals the order Juggluco persists (and the handshake consumes):
 * `nfc->pin` is a native `uint32` read little-endian from the wire and stored back little-endian, so
 * [pin] is returned as the raw 4 wire bytes and matches the `blePin` in an imported credentials file.
 */
object Libre3Nfc2 {

    const val SIZE = 19
    const val ERROR_SIZE = 4

    /** `recogn` value (little-endian) that marks the error variant. */
    const val ERROR_RECOGN = 0x01A5

    /**
     * The benign error: the sensor is mid-activation and wants a retry. Juggluco maps `error == 0xb1`
     * to return code 1 ("try again") rather than a hard failure — this is what a too-early scan hits.
     */
    const val ERROR_RETRY = 0xB1

    data class Activation(
        /** Peripheral BLE address, e.g. "A0:7E:16:2F:9C:53". */
        val mac: String,
        /** 4-byte BLE PIN in wire/stored order (feeds the security handshake's plain36 tail). */
        val blePin: ByteArray,
        /** Sensor start in unix seconds; 0 on the wire means the sensor left it unset. */
        val activationTimeSec: Long
    ) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Activation && mac == other.mac &&
                blePin.contentEquals(other.blePin) && activationTimeSec == other.activationTimeSec)

        override fun hashCode(): Int =
            (mac.hashCode() * 31 + blePin.contentHashCode()) * 31 + activationTimeSec.hashCode()
    }

    sealed interface Result {
        data class Ok(val activation: Activation) : Result
        /** `nfc2error` variant. [code] is the `error` byte; [ERROR_RETRY] means "scan again". */
        data class Error(val code: Int) : Result
        data object Malformed : Result
    }

    fun parse(nfcout: ByteArray): Result {
        if (nfcout.size == ERROR_SIZE) {
            return if (u16le(nfcout, 1) == ERROR_RECOGN) Result.Error(nfcout[3].toInt() and 0xFF)
            else Result.Malformed
        }
        if (nfcout.size != SIZE) return Result.Malformed
        // deviceAddress is reversed on the wire: mkdeviceaddressstr prints inbuf[5]..inbuf[0].
        val mac = (0 until 6).joinToString(":") { "%02X".format(nfcout[8 - it].toInt() and 0xFF) }
        val blePin = nfcout.copyOfRange(9, 13)
        val activationTimeSec = u32le(nfcout, 13)
        return Result.Ok(Activation(mac, blePin, activationTimeSec))
    }

    private fun u16le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32le(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((b[o + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}

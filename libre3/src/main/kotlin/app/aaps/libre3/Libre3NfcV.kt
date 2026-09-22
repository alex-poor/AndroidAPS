package app.aaps.libre3

import android.nfc.Tag
import android.nfc.tech.NfcV

/**
 * Android `NfcV` I/O for Libre 3 — builds the transceiver [Libre3NfcActivation] needs from a scanned
 * [Tag]. Mirrors Juggluco's `AlgNfcV.wholenfccmd` (GPL-3.0): connect, transceive, retry up to
 * [RETRIES] on failure or a "bad" response, closing the tag each attempt. A response is good when it
 * is non-empty and its first byte's low bit is clear (`AlgNfcV.goodnfc`).
 *
 * Kept deliberately thin and free of activation logic: the command bytes and response handling live
 * in [Libre3NfcActivation], which is why that class — not this one — carries the tests.
 */
object Libre3NfcV {

    const val RETRIES = 10

    /** The transceiver [Libre3NfcActivation] runs its exchange over, bound to a live tag. */
    fun transceiver(tag: Tag): (ByteArray) -> ByteArray? = { cmd -> transceive(tag, cmd) }

    private fun goodNfc(d: ByteArray?): Boolean =
        d != null && d.isNotEmpty() && (d[0].toInt() and 1) == 0

    private fun transceive(tag: Tag, cmd: ByteArray): ByteArray? {
        repeat(RETRIES) {
            val nfcv = NfcV.get(tag) ?: return null
            try {
                nfcv.connect()
                val r = runCatching { nfcv.transceive(cmd) }.getOrNull()
                if (goodNfc(r)) return r
            } catch (_: Exception) {
                // connect/transceive failed this attempt; a fresh NfcV.get on the next loop retries.
            } finally {
                runCatching { nfcv.close() }
            }
        }
        return null
    }
}

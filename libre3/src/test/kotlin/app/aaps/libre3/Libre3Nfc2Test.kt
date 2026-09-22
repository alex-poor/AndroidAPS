package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * `struct nfc2` parser tests.
 *
 * The vectors here are SYNTHETIC (no live sensor's MAC/PIN in a public repo), but the layout they
 * pin down is the one VALIDATED against a real capture on 2026-09-22: the second NFC response of
 * sensor E07A-XX0TM9UWUJX parsed to MAC A0:7E:16:2F:9C:53 and activationTime 1790057703 — the
 * sensor's true start — byte-exact. Those two ground-truth fields confirm the struct alignment, so
 * `pin` (same struct, read identically) yields the real BLE PIN on a successful activation.
 */
class Libre3Nfc2Test {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // zero=00, response=A5 00, deviceAddress(reversed)=5F 4E 3D 2C 1B 0A -> MAC 0A:1B:2C:3D:4E:5F,
    // pin=11 22 33 44, activationTime=E7 1C B2 6A (LE) = 1790057703, crc=9A BC
    //              zero+resp | deviceAddr(rev) | pin      | actTime  | crc
    private val success = hex("00a500" + "5f4e3d2c1b0a" + "11223344" + "e71cb26a" + "9abc")

    @Test
    fun `parses MAC reversed, pin in wire order, and activation time little-endian`() {
        val r = Libre3Nfc2.parse(success)
        assertThat(r).isInstanceOf(Libre3Nfc2.Result.Ok::class.java)
        val a = (r as Libre3Nfc2.Result.Ok).activation
        assertThat(a.mac).isEqualTo("0A:1B:2C:3D:4E:5F")           // deviceAddress bytes reversed
        assertThat(a.blePin).isEqualTo(hex("11223344"))            // 4 pin bytes, wire order
        assertThat(a.activationTimeSec).isEqualTo(1790057703L)     // matches the real 18:15 start
    }

    @Test
    fun `the real capture's ground-truth fields align with this layout`() {
        // Exactly the MAC/time slots exercised, at the real captured values, without the live PIN.
        // deviceAddress reversed = A0:7E:16:2F:9C:53 ; activationTime E7 1C B2 6A = 1790057703.
        val realFields = hex("00a500539c2f167ea0" + "00000000" + "e71cb26a" + "0000")
        val a = (Libre3Nfc2.parse(realFields) as Libre3Nfc2.Result.Ok).activation
        assertThat(a.mac).isEqualTo("A0:7E:16:2F:9C:53")
        assertThat(a.activationTimeSec).isEqualTo(1790057703L)
    }

    @Test
    fun `activation time of zero is surfaced as zero, not invented`() {
        // Juggluco substitutes `now` when activationTime<=0; that policy belongs to the caller, so
        // the parser must report the raw 0 rather than hide it.
        val zeroTime = hex("00a500060504030201aabbccdd" + "00000000" + "0000")
        val a = (Libre3Nfc2.parse(zeroTime) as Libre3Nfc2.Result.Ok).activation
        assertThat(a.activationTimeSec).isEqualTo(0L)
    }

    @Test
    fun `the 4-byte error variant is recognised and carries its code`() {
        // struct nfc2error: zero(1) recogn(2)=0x01A5 error(1). 0xB1 = the benign "scan again".
        val err = hex("00a501b1")
        val r = Libre3Nfc2.parse(err)
        assertThat(r).isEqualTo(Libre3Nfc2.Result.Error(0xB1))
    }

    @Test
    fun `a 4-byte blob whose recogn is not 0x01A5 is malformed, not a false error`() {
        assertThat(Libre3Nfc2.parse(hex("00000000"))).isEqualTo(Libre3Nfc2.Result.Malformed)
    }

    @Test
    fun `wrong-length responses are malformed`() {
        assertThat(Libre3Nfc2.parse(ByteArray(18))).isEqualTo(Libre3Nfc2.Result.Malformed)
        assertThat(Libre3Nfc2.parse(ByteArray(20))).isEqualTo(Libre3Nfc2.Result.Malformed)
        assertThat(Libre3Nfc2.parse(ByteArray(0))).isEqualTo(Libre3Nfc2.Result.Malformed)
    }
}

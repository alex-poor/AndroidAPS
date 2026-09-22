package app.aaps.libre3

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Activation-flow tests with a fake transceiver — the command sequence and response mapping are
 * exercised end to end without an NFC radio. Vectors are synthetic (no live sensor identifiers).
 *
 * The synthetic nfc1 mirrors the real captured one: `00 A5 00` header, then a firstnfc with
 * puckGeneration=1 (Plus), warmup=0x0C (60 min) and state=1 (fresh -> ACTIVATE) — the same fields
 * that decoded correctly from the 2026-09-22 capture.
 */
class Libre3NfcActivationTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val readInfoCmd = hex("02a17a")

    //                 hdr    secV loc  gen  wear fw       pt wu st serial(TESTSENSR)     crc
    private val nfc1 = hex("00a500" + "0100" + "0400" + "0100" + "6054" + "1e020401" + "04" + "0c" + "01" +
        "5445535453454e5352" + "0000")

    // nfc2 success: MAC 0A:1B:2C:3D:4E:5F (reversed), pin 11223344, activationTime 1790057703, crc
    private val nfc2Ok = hex("00a500" + "5f4e3d2c1b0a" + "11223344" + "e71cb26a" + "9abc")

    private val nowSec = 1790000000L
    private val accountId = 12345L

    /** Records the activation command the flow emits, and answers the two exchanges. */
    private class Fake(val nfc1: ByteArray, val activationReply: ByteArray?) : (ByteArray) -> ByteArray? {
        var activationCmd: ByteArray? = null
        override fun invoke(cmd: ByteArray): ByteArray? = when {
            cmd.contentEquals(byteArrayOf(0x02, 0xA1.toByte(), 0x7A)) -> nfc1
            cmd.size == 13 && cmd[0] == 0x02.toByte() && cmd[1] == 0xA0.toByte() && cmd[2] == 0x7A.toByte() -> {
                activationCmd = cmd; activationReply
            }
            else -> null
        }
    }

    @Test
    fun `readInfo reports a fresh Plus that will be activated, without writing`() {
        val fake = Fake(nfc1, activationReply = null)
        val si = Libre3NfcActivation(fake).readInfo()!!
        assertThat(si.willActivate).isTrue()               // state==1 -> A0
        assertThat(si.info.isPlus).isTrue()                // puckGeneration==1
        assertThat(si.info.warmupMinutes).isEqualTo(60)
        assertThat(si.info.serialNumber).isEqualTo("TESTSENSR")
        assertThat(fake.activationCmd).isNull()            // readInfo must NEVER emit the write
    }

    @Test
    fun `activate emits the A0 command and returns the sensor's MAC, PIN and start`() {
        val fake = Fake(nfc1, nfc2Ok)
        val r = Libre3NfcActivation(fake).activate(nowSec, accountId)
        assertThat(r).isInstanceOf(Libre3NfcActivation.Result.Activated::class.java)
        val a = r as Libre3NfcActivation.Result.Activated
        assertThat(a.mac).isEqualTo("0A:1B:2C:3D:4E:5F")
        assertThat(a.blePin).isEqualTo(hex("11223344"))
        assertThat(a.activationTimeSec).isEqualTo(1790057703L)
        assertThat(a.isPlus).isTrue()
        assertThat(a.warmupMinutes).isEqualTo(60)
        // the command must be exactly 02 A0 7A followed by the 10-byte activation payload
        assertThat(fake.activationCmd!!.size).isEqualTo(13)
        assertThat(fake.activationCmd!!.copyOfRange(0, 3)).isEqualTo(hex("02a07a"))
        assertThat(fake.activationCmd!!.copyOfRange(3, 13))
            .isEqualTo(Libre3Nfc.activationData(nowSec, accountId))
    }

    @Test
    fun `a 0xb1 nfc2error is surfaced as Retry, not a hard failure`() {
        val r = Libre3NfcActivation(Fake(nfc1, hex("00a501b1"))).activate(nowSec, accountId)
        assertThat(r).isEqualTo(Libre3NfcActivation.Result.Retry)
    }

    @Test
    fun `a sensor rejection with another error code fails clearly`() {
        // 4-byte error, code 0x33 (not the retry code)
        val r = Libre3NfcActivation(Fake(nfc1, hex("00a50133"))).activate(nowSec, accountId)
        assertThat(r).isInstanceOf(Libre3NfcActivation.Result.Failed::class.java)
    }

    @Test
    fun `an unreadable tag fails without emitting an activation write`() {
        val fake = Fake(nfc1 = ByteArray(0).let { byteArrayOf() }, activationReply = null)
        // transceiver returns null for the read command -> flow stops before the write
        val stopped = Libre3NfcActivation { null }
        assertThat(stopped.readInfo()).isNull()
        assertThat(stopped.activate(nowSec, accountId))
            .isInstanceOf(Libre3NfcActivation.Result.Failed::class.java)
        assertThat(fake.activationCmd).isNull()
    }
}

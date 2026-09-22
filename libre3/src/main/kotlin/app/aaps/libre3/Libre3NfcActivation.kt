package app.aaps.libre3

/**
 * The Libre 3 NFC activation exchange — the missing transport that turns a scanned sensor into the
 * credentials AAPS needs to then run it over BLE. Sequence transcribed from Juggluco (GPL-3.0,
 * `Libre3.java` / `libre3/NFC.java` / `AlgNfcV.java`):
 *
 * ```
 *   read info :  transceive(02 A1 7A)                    -> nfc1 (firstnfc)     HARMLESS (a read)
 *   activate  :  transceive(02 A0|A8 7A ‖ metcrc(10))    -> nfc2                A0 = IRREVERSIBLE
 * ```
 *
 * This class is the pure orchestration: which command, in what order, and how the responses map to
 * a result. The actual radio I/O is an injected [transceive] (`(command) -> response?`), so the flow
 * is unit-tested with canned responses and the Android `NfcV` lives only in [Libre3NfcV]. `metcrc`
 * and the A0/A8 choice come from [Libre3Nfc]; the responses are parsed by [Libre3PatchInfo] and
 * [Libre3Nfc2].
 *
 * [readInfo] and [activate] are deliberately separate: [readInfo] only reads and is safe to run to
 * show the user what they are about to do; [activate] sends the one irreversible command and must
 * only be called once the user has committed (the `Libre3ScanFlow` gate).
 */
class Libre3NfcActivation(private val transceive: (ByteArray) -> ByteArray?) {

    /** `02 A1 7A` — read the patch info (`Libre3.firstnfc`). A read; safe to repeat. */
    private val readInfoCommand = byteArrayOf(0x02, 0xA1.toByte(), 0x7A)

    data class SensorInfo(
        val info: Libre3PatchInfo,
        /** true when this scan would ACTIVATE a fresh sensor (0xA0) rather than TAKEOVER (0xA8). */
        val willActivate: Boolean
    )

    sealed interface Result {
        /** Everything AAPS needs to build a credentials file and connect. */
        data class Activated(
            val serialNumber: String,
            val mac: String,
            val blePin: ByteArray,
            val isPlus: Boolean,
            val warmupMinutes: Int,
            val wearDurationMinutes: Int,
            val activationTimeSec: Long
        ) : Result

        /** The sensor asked us to scan again (`nfc2error` 0xb1) — e.g. a too-early scan. Not fatal. */
        data object Retry : Result

        data class Failed(val reason: String) : Result
    }

    /** Read-only: fetch and parse the patch info. Returns null if the tag could not be read. */
    fun readInfo(): SensorInfo? {
        val nfc1 = transceive(readInfoCommand) ?: return null
        val info = Libre3PatchInfo.parse(nfc1) ?: return null
        val op = Libre3Nfc.operationFor(nfc1) ?: return null
        return SensorInfo(info, willActivate = op == Libre3Nfc.OP_ACTIVATE)
    }

    /**
     * Run the activation. **Sends the irreversible `02 A0 7A` command for a fresh sensor.**
     *
     * @param nowSec activation timestamp (Juggluco's `nowsec`); the sensor records `nowSec - 1`.
     * @param accountId the Libre account id baked into the activation payload. Must be the non-zero
     *   value used consistently — 0 is a known-bad path (Juggluco reports it as 0xF8).
     */
    fun activate(nowSec: Long, accountId: Long): Result {
        val nfc1 = transceive(readInfoCommand) ?: return Result.Failed("could not read patch info")
        val info = Libre3PatchInfo.parse(nfc1) ?: return Result.Failed("could not parse patch info")
        val op = Libre3Nfc.operationFor(nfc1) ?: return Result.Failed("unknown patch state ${info.state}")

        val command = Libre3Nfc.command(op, nowSec, accountId)
        val nfc2 = transceive(command) ?: return Result.Failed("no activation response")

        return when (val r = Libre3Nfc2.parse(nfc2)) {
            is Libre3Nfc2.Result.Ok -> {
                val a = r.activation
                Result.Activated(
                    serialNumber = info.serialNumber,
                    mac = a.mac,
                    blePin = a.blePin,
                    isPlus = info.isPlus,
                    warmupMinutes = info.warmupMinutes,
                    wearDurationMinutes = info.wearDurationMinutes,
                    // the sensor sets its start on the FIRST accepted command; fall back to now if unset.
                    activationTimeSec = if (a.activationTimeSec > 0) a.activationTimeSec else nowSec
                )
            }

            is Libre3Nfc2.Result.Error ->
                if (r.code == Libre3Nfc2.ERROR_RETRY) Result.Retry
                else Result.Failed("sensor rejected activation (error 0x${r.code.toString(16)})")

            Libre3Nfc2.Result.Malformed -> Result.Failed("malformed activation response (${nfc2.size} bytes)")
        }
    }
}

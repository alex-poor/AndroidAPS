package app.aaps.plugins.source

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-sensor credentials for the native Libre 3 source.
 *
 * Produced by `dynamic/l3-extract-credentials.py` from a captured handshake and pushed to
 * [FILE_NAME] in the app's private files directory. Deliberately a plain file rather than a
 * preference: it is per-sensor, replaced wholesale at each sensor change, and must never end
 * up in a settings export that gets shared for troubleshooting.
 *
 * Contents are sensor secrets — the BLE PIN and the kAuth together are what let a client take
 * the sensor over.
 */
@Singleton
class Libre3CredentialStore @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger
) {

    data class Credentials(
        val serial: String,
        val mac: String,
        val blePin: ByteArray,
        val kAuth: ByteArray?,
        val startedEpochMs: Long,
        val warmupMinutes: Int,
        val lifeDays: Int
    )

    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun load(): Credentials? {
        if (!file.exists()) return null
        return runCatching {
            val j = JSONObject(file.readText())
            val pin = j.getString("blePin").hex()
            require(pin.size == PIN_LEN) { "blePin must be $PIN_LEN bytes, got ${pin.size}" }
            val kAuth = j.optString("kAuth").takeIf { it.isNotEmpty() }?.hex()
            require(kAuth == null || kAuth.size == KAUTH_LEN) {
                "kAuth must be $KAUTH_LEN bytes, got ${kAuth?.size}"
            }
            Credentials(
                serial = j.getString("serial"),
                mac = j.getString("mac"),
                blePin = pin,
                kAuth = kAuth,
                startedEpochMs = j.getLong("startedEpochMs"),
                warmupMinutes = j.optInt("warmupMinutes", 60),
                lifeDays = j.optInt("lifeDays", 15)
            )
        }.onFailure {
            // Do NOT fall back to defaults here. Malformed credentials must fail loudly and
            // leave the source disabled rather than half-connect with a wrong PIN.
            aapsLogger.error(LTag.BGSOURCE, "Libre3: credentials unreadable", it)
        }.getOrNull()
    }

    /**
     * Write a fresh credential set — used straight after an NFC activation. [Credentials.kAuth] is
     * normally null here: the sensor has no saved authorization yet, so the first BLE connection does
     * a full pairing (`Libre3BleClient` falls back to `startPairing()` when `storedKAuth == null`) and
     * [updateKAuth] persists the minted kAuth. Replaces any existing file wholesale — one sensor at a
     * time.
     */
    fun save(c: Credentials) {
        runCatching {
            val j = JSONObject()
            j.put("serial", c.serial)
            j.put("mac", c.mac)
            j.put("blePin", c.blePin.hex())
            c.kAuth?.let { j.put("kAuth", it.hex()) }
            j.put("startedEpochMs", c.startedEpochMs)
            j.put("warmupMinutes", c.warmupMinutes)
            j.put("lifeDays", c.lifeDays)
            file.writeText(j.toString(2))
        }.onFailure { aapsLogger.error(LTag.BGSOURCE, "Libre3: could not save credentials", it) }
    }

    /** Rewrite only the kAuth, preserving everything else. Called after each authorisation. */
    fun updateKAuth(kAuth: ByteArray) {
        runCatching {
            val j = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            j.put("kAuth", kAuth.hex())
            file.writeText(j.toString(2))
        }.onFailure { aapsLogger.error(LTag.BGSOURCE, "Libre3: could not persist kAuth", it) }
    }

    /** Remove the stored credentials entirely. */
    fun clear() {
        runCatching { if (file.exists()) file.delete() }
            .onFailure { aapsLogger.error(LTag.BGSOURCE, "Libre3: could not clear credentials", it) }
    }

    private fun String.hex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val FILE_NAME = "libre3-credentials.json"
        private const val PIN_LEN = 4
        private const val KAUTH_LEN = 149
    }
}

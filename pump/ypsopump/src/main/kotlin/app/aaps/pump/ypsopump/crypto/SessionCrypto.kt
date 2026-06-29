package app.aaps.pump.ypsopump.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * XChaCha20-Poly1305 encryption/decryption for YpsoPump BLE communication.
 *
 * Message format (from decompiled write.java):
 *   Encrypt output:  ciphertext || nonce[24]     (nonce APPENDED)
 *   Decrypt input:   ciphertext || nonce[24]      (nonce at END)
 *   Plaintext:       commandData || rebootCounter[4] || writeCounter[8]
 */
@Singleton
class SessionCrypto @Inject constructor() {

    private val lazySodium = LazySodiumAndroid(SodiumAndroid())

    var sharedKey: ByteArray? = null
    var writeCounter: Long = 0L
    var readCounter: Long = 0L
    var rebootCounter: Int = 0

    val isInitialized: Boolean
        get() = sharedKey != null

    /**
     * Encrypt command data for writing to the pump.
     * @return BLE payload: ciphertext+tag || nonce
     */
    fun encrypt(commandData: ByteArray): ByteArray {
        val key = sharedKey ?: throw IllegalStateException("No shared key set")

        // PRE-increment the writeCounter: the pump requires the EXACT next counter (= last accepted
        // + 1), so the first write after seeding N must use N+1. This matches the proven ypso-reader
        // flow; the earlier POST-increment (first write used the seed verbatim) made the pump reject
        // every write with counter-mismatch (err 138). Rejected writes do NOT advance the pump, so
        // the caller auto-syncs by retrying with the next value on 138.
        writeCounter++

        // Build plaintext: command + rebootCounter(4B LE) + writeCounter(8B LE).
        // Endianness is LITTLE — confirmed against real pump traffic (rebootCounter=8 is sane in LE
        // but garbage in BE; the pump's read counter is monotonic only when read as LE). The earlier
        // BIG-endian assumption made the pump reject every command (counter mismatch, err 138/139).
        val counterData = ByteBuffer.allocate(COUNTER_DATA_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(rebootCounter)
            .putLong(writeCounter)
            .array()
        val plaintext = commandData + counterData

        // Random 24-byte nonce (raw libsodium binding lives on the Sodium object)
        val nonce = ByteArray(NONCE_SIZE)
        lazySodium.sodium.randombytes_buf(nonce, NONCE_SIZE)

        // Encrypt: output = ciphertext + 16-byte tag
        val ciphertext = ByteArray(plaintext.size + TAG_SIZE)
        val ciphertextLen = longArrayOf(0)
        val result = lazySodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext, ciphertextLen,
            plaintext, plaintext.size.toLong(),
            null, 0, null,
            nonce, key
        )
        if (!result) throw SecurityException("Encryption failed")

        // Return ciphertext || nonce (nonce APPENDED, not prepended!)
        return ciphertext + nonce
    }

    /**
     * Decrypt data received from pump.
     * @param blePayload BLE payload: ciphertext+tag || nonce
     * @return decrypted command data (without counters)
     */
    fun decrypt(blePayload: ByteArray): ByteArray {
        val key = sharedKey ?: throw IllegalStateException("No shared key set")

        if (blePayload.size < NONCE_SIZE + TAG_SIZE) {
            throw IllegalArgumentException("Payload too short: ${blePayload.size} bytes")
        }

        // Nonce is the LAST 24 bytes
        val nonce = blePayload.sliceArray(blePayload.size - NONCE_SIZE until blePayload.size)
        val ciphertext = blePayload.sliceArray(0 until blePayload.size - NONCE_SIZE)

        // Decrypt
        val plaintext = ByteArray(ciphertext.size - TAG_SIZE)
        val plaintextLen = longArrayOf(0)
        val result = lazySodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext, plaintextLen, null,
            ciphertext, ciphertext.size.toLong(),
            null, 0, nonce, key
        )
        if (!result) throw SecurityException("Decryption failed — invalid key or tampered data")

        // Parse counters from end of plaintext
        if (plaintext.size >= COUNTER_DATA_SIZE) {
            val buf = ByteBuffer.wrap(plaintext, plaintext.size - COUNTER_DATA_SIZE, COUNTER_DATA_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
            val pumpRebootCounter = buf.getInt()
            val pumpCounter = buf.getLong()

            // Handle reboot detection
            if (pumpRebootCounter > rebootCounter) {
                rebootCounter = pumpRebootCounter
                writeCounter = 0L
            } else if (pumpRebootCounter < 0) {
                throw IllegalArgumentException("Invalid reboot counter: $pumpRebootCounter")
            }

            // Validate read counter (must be monotonically increasing)
            if (readCounter > 0 && pumpCounter <= readCounter) {
                throw SecurityException("Read counter not increasing: $pumpCounter <= $readCounter")
            }
            readCounter = pumpCounter

            return plaintext.sliceArray(0 until plaintext.size - COUNTER_DATA_SIZE)
        }

        return plaintext
    }

    fun reset() {
        sharedKey = null
        writeCounter = 0L
        readCounter = 0L
        rebootCounter = 0
    }

    companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 24
        const val TAG_SIZE = 16
        const val COUNTER_DATA_SIZE = 12 // rebootCounter(4) + writeCounter(8)
    }
}

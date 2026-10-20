package app.aaps.libre3

import java.security.SecureRandom

/**
 * The Libre 3 security handshake, independent of BLE transport.
 *
 * Feed it the bytes that arrive on the security characteristics and it returns the bytes to
 * write back. Keeping the protocol out of the GATT callback makes it testable off-device —
 * the same reason the conformance harness (`src/test/l3_replay.py`) exists.
 *
 * PROVEN: replaying a real captured handshake through the native core reproduces Juggluco's
 * output byte-for-byte (Stage 2 gate, 2026-09-07).
 *
 * Two entry paths:
 *  - [startResume]  — a stored kAuth exists. Skips the certificate exchange entirely; this is
 *                     the path the Stage 3 credential lift uses, and the only one that is
 *                     fully deterministic.
 *  - [startPairing] — no kAuth. Full certificate + ephemeral-key exchange.
 *
 * NOTE ON NONCES: both challenge nonces are supplied BY THE SENSOR (bytes 16..23 of the
 * request, and the trailing 7 bytes of the response). We never generate them. The observed
 * monotonic counter in their first four bytes is therefore the sensor's replay guard, not
 * state a client has to maintain.
 */
class Libre3SecuritySession(
    /** 4-byte BLE PIN from the sensor record (Juggluco: `Natives.getpin`). */
    private val blePin: ByteArray,
    private val random: SecureRandom = SecureRandom(),
    /**
     * The native crypto, behind an interface purely so this state machine can be exercised on
     * the JVM against a recorded handshake. On device this is always [RealCrypto].
     */
    private val crypto: Crypto = RealCrypto
) {

    /** Exactly the calls this class makes into [Libre3Native]. */
    interface Crypto {
        fun beginHandshake(context: Long): Long
        fun selectAppKey(context: Long, version: Int, savedAuthorization: ByteArray?): Int
        fun acceptPatchCertificate(context: Long, cert: ByteArray): Int
        fun createEphemeralPublicKey(context: Long): ByteArray?
        fun deriveAuthorizationRoot(context: Long, patchEphemeralPublic: ByteArray): Int
        fun encryptChallengeReply(context: Long, nonce: ByteArray, plaintext: ByteArray): ByteArray?
        fun decryptChallengeResponse(context: Long, nonce: ByteArray, ciphertext: ByteArray): ByteArray?
        fun exportSavedAuthorization(context: Long): ByteArray?
        fun initSessionCipher(previous: Long, key: ByteArray, iv: ByteArray): Long
        fun sessionDecrypt(handle: Long, kind: Int, encrypted: ByteArray): ByteArray?
    }

    object RealCrypto : Crypto {
        override fun beginHandshake(context: Long): Long {
            Libre3Native.ensureLoaded()
            return Libre3Native.beginSecurityHandshake(context)
        }
        override fun selectAppKey(context: Long, version: Int, savedAuthorization: ByteArray?) =
            Libre3Native.selectAppKeyAndSavedAuthorization(context, version, savedAuthorization)
        override fun acceptPatchCertificate(context: Long, cert: ByteArray) =
            Libre3Native.acceptPatchCertificate(context, cert)
        override fun createEphemeralPublicKey(context: Long) =
            Libre3Native.createEphemeralPublicKey(context)
        override fun deriveAuthorizationRoot(context: Long, patchEphemeralPublic: ByteArray) =
            Libre3Native.deriveAuthorizationRoot(context, patchEphemeralPublic)
        override fun encryptChallengeReply(context: Long, nonce: ByteArray, plaintext: ByteArray) =
            Libre3Native.encryptChallengeReply(context, nonce, plaintext)
        override fun decryptChallengeResponse(context: Long, nonce: ByteArray, ciphertext: ByteArray) =
            Libre3Native.decryptChallengeResponse(context, nonce, ciphertext)
        override fun exportSavedAuthorization(context: Long) =
            Libre3Native.exportSavedAuthorization(context)
        override fun initSessionCipher(previous: Long, key: ByteArray, iv: ByteArray) =
            Libre3Native.initSessionCipher(previous, key, iv)
        override fun sessionDecrypt(handle: Long, kind: Int, encrypted: ByteArray) =
            Libre3Native.sessionDecrypt(handle, kind, encrypted)
    }

    init {
        require(blePin.size == Libre3Gatt.PIN_LEN) { "BLE PIN must be ${Libre3Gatt.PIN_LEN} bytes" }
    }

    sealed interface Step {
        /** Write these bytes to the given characteristic, then send [thenCommand] if non-null. */
        data class Write(val characteristic: java.util.UUID, val data: ByteArray, val thenCommand: Int?) : Step
        /** Write a single security command opcode. */
        data class Command(val opcode: Int) : Step
        /** Handshake complete; the session cipher is live. */
        data class Authorized(val kAuth: ByteArray, val kEnc: ByteArray, val ivEnc: ByteArray) : Step
        /** Abort and disconnect. [reason] is for the log, never for the user. */
        data class Failed(val reason: String) : Step
    }

    var contextHandle: Long = 0L
        private set

    /** Session cipher handle, valid only after [Step.Authorized]. */
    var cipherHandle: Long = 0L
        private set

    private var r1 = ByteArray(0)
    private var r2 = ByteArray(0)

    /** True between [startPairing] and authorisation; gates the extra pairing sends. */
    private var pairing = false
    /** Security version selected at [startPairing]; picks the matching app certificate. */
    private var pairingVersion = 1

    private fun begin(): Boolean {
        contextHandle = crypto.beginHandshake(contextHandle)
        return contextHandle != 0L
    }

    /**
     * Resume with a previously exported kAuth (149 bytes). securityVersion 1 matches what
     * Juggluco uses for the pre-authorised branch.
     */
    fun startResume(kAuth: ByteArray, securityVersion: Int = 1): Step {
        if (kAuth.size != Libre3Native.LEN_SAVED_AUTHORIZATION)
            return Step.Failed("kAuth must be ${Libre3Native.LEN_SAVED_AUTHORIZATION} bytes, got ${kAuth.size}")
        pairing = false
        if (!begin()) return Step.Failed("beginSecurityHandshake failed")
        val rc = crypto.selectAppKey(contextHandle, securityVersion, kAuth)
        if (rc != Libre3Native.OK) return Step.Failed("selectAppKeyAndSavedAuthorization rc=$rc")
        return Step.Command(Libre3Gatt.SecurityCommand.BEGIN_RESUME)
    }

    /** Fresh pairing: no stored authorisation. */
    fun startPairing(securityVersion: Int = 1): Step {
        pairing = true
        pairingVersion = securityVersion
        if (!begin()) return Step.Failed("beginSecurityHandshake failed")
        val rc = crypto.selectAppKey(contextHandle, securityVersion, null)
        if (rc != Libre3Native.OK) return Step.Failed("selectAppKeyAndSavedAuthorization rc=$rc")
        return Step.Command(Libre3Gatt.SecurityCommand.BEGIN_PAIRING)
    }

    /**
     * The sends that a fresh pairing must make right after a security command's WRITE is
     * acknowledged — the part AAPS was missing, which left the sensor silent after BEGIN_PAIRING.
     * Returns null when nothing follows the command (resume, or a command whose reply is a
     * notification we wait for). Mirrors Juggluco's `Libre3GattCallback` state machine (R0):
     *
     *   BEGIN_PAIRING(1)  →  REQUEST_CERT(2)
     *   REQUEST_CERT(2)   →  write our 162-byte app certificate to CERT_DATA, then await the
     *                        sensor's patch certificate
     *   EPHEMERAL_EXCHANGE(13) → write our own ephemeral public key (0x04 ‖ 64 B) to CERT_DATA,
     *                        then await the sensor's patch ephemeral key
     *
     * Everything else (BEGIN_RESUME, CHALLENGE_SENT, and the whole resume path) returns null and
     * is driven by the sensor's notifications exactly as before.
     */
    fun onCommandWritten(opcode: Int): Step? {
        if (!pairing) return null
        return when (opcode) {
            Libre3Gatt.SecurityCommand.BEGIN_PAIRING ->
                Step.Command(Libre3Gatt.SecurityCommand.REQUEST_CERT)

            Libre3Gatt.SecurityCommand.REQUEST_CERT -> {
                val cert = Libre3AppCertificate.forVersion(pairingVersion)
                    ?: return Step.Failed("no app certificate for securityVersion $pairingVersion")
                Step.Write(Libre3Gatt.SEC_CHAR_CERT_DATA, cert, thenCommand = null)
            }

            Libre3Gatt.SecurityCommand.EPHEMERAL_EXCHANGE -> {
                val pub = crypto.createEphemeralPublicKey(contextHandle)
                    ?: return Step.Failed("createEphemeralPublicKey returned null")
                // Prepend the 0x04 uncompressed-EC-point tag, as Juggluco does.
                val framed = ByteArray(pub.size + 1)
                framed[0] = 0x04
                pub.copyInto(framed, 1)
                Step.Write(Libre3Gatt.SEC_CHAR_CERT_DATA, framed, thenCommand = null)
            }

            else -> null
        }
    }

    /** Patch certificate (140 B) received on SEC_CHAR_CERT_DATA during a fresh pairing. */
    fun onPatchCertificate(cert: ByteArray): Step {
        val rc = crypto.acceptPatchCertificate(contextHandle, cert)
        if (rc != Libre3Native.OK) return Step.Failed("acceptPatchCertificate rc=$rc")
        return Step.Command(Libre3Gatt.SecurityCommand.EPHEMERAL_EXCHANGE)
    }

    /** Patch ephemeral public key (65 B) received; derive the authorization root. */
    fun onPatchEphemeralKey(patchEphemeralPublic: ByteArray): Step {
        val rc = crypto.deriveAuthorizationRoot(contextHandle, patchEphemeralPublic)
        if (rc != Libre3Native.OK) return Step.Failed("deriveAuthorizationRoot rc=$rc")
        return Step.Command(Libre3Gatt.SecurityCommand.BEGIN_RESUME)
    }

    /**
     * 23-byte challenge from the sensor: r1(16) ‖ nonce(7).
     *
     * Replies with AES-CCM(nonce, r1 ‖ r2 ‖ pin) where r2 is ours and fresh. Echoing r1 back
     * proves we hold the key; r2 is what the sensor must echo to prove the same to us.
     */
    fun onChallengeRequest(request: ByteArray): Step {
        if (request.size != Libre3Gatt.CHALLENGE_REQUEST_LEN)
            return Step.Failed("challenge request must be ${Libre3Gatt.CHALLENGE_REQUEST_LEN} bytes, got ${request.size}")

        r1 = request.copyOfRange(0, Libre3Gatt.R_LEN)
        val nonce = request.copyOfRange(Libre3Gatt.R_LEN, Libre3Gatt.CHALLENGE_REQUEST_LEN)
        r2 = ByteArray(Libre3Gatt.R_LEN).also { random.nextBytes(it) }

        val plain = ByteArray(Libre3Native.LEN_CHALLENGE_REPLY_PLAIN)   // 36 = 16 + 16 + 4
        r1.copyInto(plain, 0)
        r2.copyInto(plain, Libre3Gatt.R_LEN)
        blePin.copyInto(plain, Libre3Gatt.R_LEN * 2)

        val encrypted = crypto.encryptChallengeReply(contextHandle, nonce, plain)
            ?: return Step.Failed("encryptChallengeReply returned null")

        return Step.Write(Libre3Gatt.SEC_CHAR_CHALLENGE_DATA, encrypted,
                          Libre3Gatt.SecurityCommand.CHALLENGE_SENT)
    }

    /**
     * 67-byte response: ciphertext(60) ‖ nonce(7). Decrypts to
     * r2(16) ‖ r1(16) ‖ kEnc(16) ‖ ivEnc(8) — note r2 comes FIRST.
     *
     * Both echoes are checked. This is the step that authenticates the SENSOR to us; skipping
     * either check would accept any peer that can produce a well-formed frame.
     */
    fun onChallengeResponse(response: ByteArray): Step {
        if (response.size != Libre3Gatt.CHALLENGE_RESPONSE_LEN)
            return Step.Failed("challenge response must be ${Libre3Gatt.CHALLENGE_RESPONSE_LEN} bytes, got ${response.size}")

        val cipher = response.copyOfRange(0, Libre3Native.LEN_CHALLENGE_RESPONSE_CRYPT)
        val nonce = response.copyOfRange(Libre3Native.LEN_CHALLENGE_RESPONSE_CRYPT, Libre3Gatt.CHALLENGE_RESPONSE_LEN)

        val plain = crypto.decryptChallengeResponse(contextHandle, nonce, cipher)
            ?: return Step.Failed("decryptChallengeResponse returned null (wrong key, or not our sensor)")

        if (!plain.copyOfRange(0, 16).contentEquals(r2)) return Step.Failed("challenge echo mismatch: r2")
        if (!plain.copyOfRange(16, 32).contentEquals(r1)) return Step.Failed("challenge echo mismatch: r1")

        val kEnc = plain.copyOfRange(32, 48)
        val ivEnc = plain.copyOfRange(48, 56)

        val kAuth = crypto.exportSavedAuthorization(contextHandle)
            ?: return Step.Failed("exportSavedAuthorization returned null")

        cipherHandle = crypto.initSessionCipher(cipherHandle, kEnc, ivEnc)
        if (cipherHandle == 0L) return Step.Failed("initSessionCipher failed")

        pairing = false
        return Step.Authorized(kAuth, kEnc, ivEnc)
    }

    /** Decrypt a data-characteristic frame. [channel] is a [Libre3Gatt.Channel] value. */
    fun decrypt(channel: Int, frame: ByteArray): ByteArray? =
        if (cipherHandle == 0L) null else crypto.sessionDecrypt(cipherHandle, channel, frame)
}

package app.aaps.libre3

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BLE transport for a Libre 3 sensor.
 *
 * Owns the GATT lifecycle and nothing else: framing lives in [Libre3Framing] and the protocol
 * in [Libre3SecuritySession], both free of Android types and unit-tested. This class is the
 * part that can only be exercised on a device, so it is deliberately kept thin.
 *
 * ⚠️ Android allows exactly ONE outstanding GATT operation per connection. Issuing a second
 * before the previous callback lands makes it fail silently — the classic cause of handshakes
 * that stall halfway with no error. Every write and descriptor write therefore goes through
 * [opQueue] and the next is only started from the completion callback.
 */
@SuppressLint("MissingPermission")
class Libre3BleClient(
    private val context: Context,
    private val session: Libre3SecuritySession,
    private val listener: Listener
) : BluetoothGattCallback() {

    interface Listener {
        /** Decrypted 1-minute glucose record (29 bytes). */
        fun onGlucoseRecord(plain: ByteArray)
        /** Retained 5-minute readings recovered from the sensor — gap backfill. */
        fun onHistory(entries: List<Libre3History.Entry>)
        /** Sensor lifecycle state (warm-up, expiry, failure). */
        fun onPatchStatus(status: Libre3PatchStatus)
        /** Handshake finished; [kAuth] should be persisted for the next reconnect. */
        fun onAuthorized(kAuth: ByteArray)
        fun onDisconnected(status: Int)
        /** Signal strength, refreshed after each glucose packet. */
        fun onRssi(dbm: Int)
        fun onError(reason: String)
    }

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()

    /** Set by [disconnect] so a deliberate teardown is not treated as a dropout to recover from. */
    private var stopping = false
    private var reconnectAttempt = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val opQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val opInFlight = AtomicBoolean(false)

    /** Pending app→sensor transfer, split into 20-byte frames by [Libre3Framing]. */
    private var writeFrames: List<ByteArray> = emptyList()
    private var writeIndex = 0
    private var writeTarget: UUID? = null
    private var writeThenCommand: Int? = null

    /** In-progress sensor→app transfer. */
    private var reassembler: Libre3Framing.Reassembler? = null
    private var pendingSignal: Int = -1

    /**
     * The 1-minute glucose characteristic arrives in several notifications that must be
     * concatenated to 35 bytes before decryption. Unlike the security channel these carry no
     * sequence byte — the boundary is purely the expected length.
     */
    private val glucoseFrame = ByteArray(GLUCOSE_FRAME_SIZE)
    private var glucoseFilled = 0

    fun connect(device: BluetoothDevice) {
        this.device = device
        stopping = false
        reconnectAttempt = 0
        openGatt()
    }

    private fun openGatt() {
        val d = device ?: return
        // autoConnect=false: the direct connection is far quicker, and we drive our own retry
        // schedule rather than leaving it to the platform's opaque background attempts.
        gatt = d.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        armWatchdog()
    }

    /**
     * Retry independently of the GATT callbacks.
     *
     * Re-arming only from `onConnectionStateChange(DISCONNECTED)` is not enough: a connect
     * attempt can die WITHOUT ever producing a state change — notably when another app still
     * holds the sensor ("attempt from other app in progress"), which is precisely the case when
     * taking a sensor over from Juggluco. The callback never comes, so a callback-driven retry
     * chain stops silently and the CGM feed ends with no error anywhere.
     *
     * Observed doing exactly that on 2026-09-07 20:03: one retry, then 86 s of nothing.
     */
    private fun armWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, CONNECT_WATCHDOG_MS)
    }

    private val watchdog = Runnable {
        if (stopping) return@Runnable
        if (!authorized) {
            // Still not up after the grace period — tear the attempt down and try again rather
            // than waiting for a callback that may never arrive.
            scheduleReconnect()
        }
    }

    /** Set once the handshake completes; cleared on disconnect. Drives the watchdog. */
    private var authorized = false

    fun disconnect() {
        stopping = true
        authorized = false
        handler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    /**
     * Reconnect with capped exponential backoff.
     *
     * A Libre 3 drops and re-establishes on its own throughout normal wear, and transient
     * `GATT_CONNECTION_TIMEOUT(147)` is routine rather than a fault. Retrying flat-out would
     * hammer the radio; giving up would silently end the CGM feed. Backoff caps at
     * [RECONNECT_MAX_DELAY_MS] and never stops, because the sensor coming back into range is a
     * normal event that may be hours away.
     */
    private fun scheduleReconnect() {
        if (stopping) return
        handler.removeCallbacks(watchdog)
        authorized = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        chars.clear()
        val delay = minOf(
            RECONNECT_BASE_DELAY_MS shl minOf(reconnectAttempt, 5),
            RECONNECT_MAX_DELAY_MS
        )
        reconnectAttempt++
        handler.postDelayed({ if (!stopping) openGatt() }, delay)
    }

    // --- operation queue ----------------------------------------------------

    private fun enqueue(op: () -> Unit) {
        opQueue.add(op)
        pump()
    }

    private fun pump() {
        if (opInFlight.get()) return
        val op = opQueue.poll() ?: return
        if (opInFlight.compareAndSet(false, true)) op() else opQueue.add(op)
    }

    private fun opDone() {
        opInFlight.set(false)
        pump()
    }

    // --- lifecycle ----------------------------------------------------------

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED    -> {
                reconnectAttempt = 0
                g.discoverServices()
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                opQueue.clear()
                opInFlight.set(false)
                glucoseFilled = 0          // a part-assembled frame cannot span a connection
                reassembler = null
                authorized = false
                listener.onDisconnected(status)
                scheduleReconnect()
            }
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            listener.onError("service discovery failed: $status"); return
        }
        val data = g.getService(Libre3Gatt.DATA_SERVICE)
        val security = g.getService(Libre3Gatt.SECURITY_SERVICE)
        if (data == null || security == null) {
            listener.onError("expected services missing — not a Libre 3?"); return
        }
        listOf(
            Libre3Gatt.CHAR_PATCH_CONTROL, Libre3Gatt.CHAR_PATCH_STATUS, Libre3Gatt.CHAR_EVENT_LOG,
            Libre3Gatt.CHAR_GLUCOSE_DATA, Libre3Gatt.CHAR_HISTORIC_DATA,
            Libre3Gatt.CHAR_CLINICAL_DATA, Libre3Gatt.CHAR_FACTORY_DATA
        ).forEach { u -> data.getCharacteristic(u)?.let { chars[u] = it } }
        listOf(
            Libre3Gatt.SEC_CHAR_COMMAND_RESPONSE, Libre3Gatt.SEC_CHAR_CHALLENGE_DATA,
            Libre3Gatt.SEC_CHAR_CERT_DATA
        ).forEach { u -> security.getCharacteristic(u)?.let { chars[u] = it } }

        // The security channel must be listening before any command is sent, otherwise the
        // sensor's reply is dropped and the handshake stalls with no error anywhere.
        //
        // ORDER IS LOAD-BEARING. Abbott's firmware gates the certificate exchange on the three
        // security CCCDs being enabled in exactly this order — COMMAND_RESPONSE, then CERT_DATA,
        // then CHALLENGE_DATA — and only then accepting the BEGIN_PAIRING command. With CERT and
        // CHALLENGE swapped (as this was until 2026-10-07) the sensor ACKs BEGIN_PAIRING but never
        // sends its certificate, so fresh pairing silently never completes. Matches Juggluco's
        // getservices()/handleonDescriptorWrite chain (Libre3GattCallback.java): CommandResponse ->
        // CertificateData -> ChallengeData -> handleMSLibre3SecurityNotificationsEnabledEvent().
        handshakeStarted = false
        enableNotifications(Libre3Gatt.SEC_CHAR_COMMAND_RESPONSE)
        enableNotifications(Libre3Gatt.SEC_CHAR_CERT_DATA)
        enableNotifications(Libre3Gatt.SEC_CHAR_CHALLENGE_DATA)
        // Start the handshake from a TERMINAL queued op, not from recognising the last descriptor's
        // write callback. The old trigger (`if uuid == SEC_CHAR_CERT_DATA in onDescriptorWrite`) was
        // skipped whenever that final writeDescriptor returned false synchronously — enableNotifications
        // then calls opDone() but never beginHandshake(), so pairing silently never started and the
        // sensor dropped the untrusted link after ~10 s, forever. A queued op runs once all three CCCD
        // writes have drained regardless of how each completed, so the handshake always begins.
        enqueue {
            if (!handshakeStarted) { handshakeStarted = true; beginHandshake() }
            opDone()
        }
    }

    /** Guards the one-shot handshake start enqueued at the end of [onServicesDiscovered]. */
    private var handshakeStarted = false

    private fun enableNotifications(uuid: UUID) {
        val ch = chars[uuid] ?: run { Log.w(TAG, "enableNotifications: char $uuid missing"); return }
        enqueue {
            val g = gatt
            if (g == null) { opDone(); return@enqueue }
            g.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(Libre3Gatt.CCC_DESCRIPTOR)
            if (cccd == null) { Log.w(TAG, "enableNotifications: no CCCD on $uuid"); opDone(); return@enqueue }
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            val ok = g.writeDescriptor(cccd)
            Log.d(TAG, "writeDescriptor CCCD $uuid -> $ok")
            // writeDescriptor returning false means the write was never issued, so no onDescriptorWrite
            // will arrive — advance the queue now, otherwise it stalls here forever.
            if (!ok) opDone()
        }
    }

    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
        Log.d(TAG, "onDescriptorWrite ${d.characteristic.uuid} status=$status")
        opDone()
    }

    /** Caller decides resume vs pairing by whether it has a stored kAuth. */
    var storedKAuth: ByteArray? = null

    private fun beginHandshake() {
        val kAuth = storedKAuth
        Log.d(TAG, "beginHandshake: ${if (kAuth != null) "resume" else "pairing"}")
        val step = if (kAuth != null) session.startResume(kAuth) else session.startPairing()
        Log.d(TAG, "beginHandshake step=${step.javaClass.simpleName}")
        apply(step)
    }

    // --- protocol driving ---------------------------------------------------

    private fun apply(step: Libre3SecuritySession.Step) {
        when (step) {
            is Libre3SecuritySession.Step.Command    -> sendCommand(step.opcode)
            is Libre3SecuritySession.Step.Write      -> startWrite(step.characteristic, step.data, step.thenCommand)
            is Libre3SecuritySession.Step.Authorized -> {
                // ALL SEVEN data characteristics must have their CCCD configured, in this order,
                // before the sensor will accept a control write. Enabling only the ones we read
                // from gets the write rejected with ATT 0xFD "CCCD Improperly Configured" —
                // observed repeatedly on hardware 2026-09-07 while backfill was failing.
                // Order and completeness are Juggluco's (`handleonDescriptorWrite`).
                enableNotifications(Libre3Gatt.CHAR_PATCH_CONTROL)
                enableNotifications(Libre3Gatt.CHAR_EVENT_LOG)
                enableNotifications(Libre3Gatt.CHAR_HISTORIC_DATA)
                enableNotifications(Libre3Gatt.CHAR_CLINICAL_DATA)
                enableNotifications(Libre3Gatt.CHAR_FACTORY_DATA)
                enableNotifications(Libre3Gatt.CHAR_GLUCOSE_DATA)
                enableNotifications(Libre3Gatt.CHAR_PATCH_STATUS)
                authorized = true
                handler.removeCallbacks(watchdog)
                listener.onAuthorized(step.kAuth)
            }
            is Libre3SecuritySession.Step.Failed     -> {
                listener.onError(step.reason)
                disconnect()
            }
        }
    }

    private fun sendCommand(opcode: Int) {
        val ch = chars[Libre3Gatt.SEC_CHAR_COMMAND_RESPONSE]
            ?: run { Log.w(TAG, "sendCommand 0x${opcode.toString(16)}: COMMAND_RESPONSE char missing"); return }
        enqueue {
            val g = gatt
            if (g == null) { opDone(); return@enqueue }
            @Suppress("DEPRECATION")
            ch.value = byteArrayOf(opcode.toByte())
            @Suppress("DEPRECATION")
            val ok = g.writeCharacteristic(ch)
            Log.d(TAG, "sendCommand 0x${opcode.toString(16)} props=0x${ch.properties.toString(16)} wt=${ch.writeType} -> $ok")
            if (!ok) opDone()
        }
    }

    private fun startWrite(uuid: UUID, payload: ByteArray, thenCommand: Int?) {
        writeFrames = Libre3Framing.splitForWrite(payload)
        writeIndex = 0
        writeTarget = uuid
        writeThenCommand = thenCommand
        writeNextFrame()
    }

    private fun writeNextFrame() {
        val uuid = writeTarget ?: return
        if (writeIndex >= writeFrames.size) {
            writeTarget = null
            writeThenCommand?.let { sendCommand(it) }
            writeThenCommand = null
            return
        }
        val ch = chars[uuid] ?: return
        val frame = writeFrames[writeIndex++]
        enqueue {
            val g = gatt
            if (g == null) { opDone(); return@enqueue }
            @Suppress("DEPRECATION")
            ch.value = frame
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(ch)) opDone()
        }
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
        Log.d(TAG, "onCharacteristicWrite ${ch.uuid} status=$status")
        opDone()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            listener.onError("write failed on ${ch.uuid}: $status")
            // NEVER call disconnect() here. disconnect() means "the user asked us to stop" — it
            // sets `stopping`, which permanently disables reconnection. A failed write killed the
            // CGM source outright on 2026-09-07 20:07 and it never came back.
            //
            // A failed OPTIONAL write (a history request) must not disturb the glucose stream at
            // all; a failed write in the HANDSHAKE leaves the session half-built, so retry the
            // connection rather than sitting on it.
            if (ch.uuid == Libre3Gatt.CHAR_PATCH_CONTROL) {
                writeTarget = null
                writeFrames = emptyList()
                writeThenCommand = null
            } else {
                scheduleReconnect()
            }
            return
        }
        if (writeTarget == ch.uuid) writeNextFrame()
    }

    // --- notifications ------------------------------------------------------

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        onNotify(ch.uuid, ch.value ?: return)
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
        onNotify(ch.uuid, value)
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        Log.d(TAG, "onNotify $uuid len=${value.size}")
        when (uuid) {
            Libre3Gatt.SEC_CHAR_COMMAND_RESPONSE -> onAnnouncement(value)
            Libre3Gatt.SEC_CHAR_CHALLENGE_DATA,
            Libre3Gatt.SEC_CHAR_CERT_DATA        -> onSecurityFrame(value)
            Libre3Gatt.CHAR_GLUCOSE_DATA         -> onGlucoseFrame(value)
            Libre3Gatt.CHAR_HISTORIC_DATA        -> onHistoricFrame(value)
            Libre3Gatt.CHAR_PATCH_STATUS         -> onPatchStatusFrame(value)
            else                                 -> Unit
        }
    }

    private fun onAnnouncement(value: ByteArray) {
        val a = Libre3Framing.parseAnnouncement(value) ?: return
        if (a.length == 0) {
            if (a.signal == Libre3Framing.Signal.CERT_ACCEPTED)
                sendCommand(Libre3Gatt.SecurityCommand.UNKNOWN_9)
            return
        }
        pendingSignal = a.signal
        reassembler = Libre3Framing.Reassembler(a.length)
    }

    private fun onSecurityFrame(value: ByteArray) {
        val r = reassembler ?: return
        when (val result = r.offer(value)) {
            is Libre3Framing.Reassembler.Result.NeedMore -> Unit
            is Libre3Framing.Reassembler.Result.Error    -> {
                listener.onError("reassembly: ${result.reason}"); disconnect()
            }
            is Libre3Framing.Reassembler.Result.Complete -> {
                reassembler = null
                val signal = pendingSignal
                pendingSignal = -1
                apply(
                    when (signal) {
                        Libre3Framing.Signal.CERTIFICATE_READY -> session.onPatchCertificate(result.data)
                        Libre3Framing.Signal.EPHEMERAL_READY   -> session.onPatchEphemeralKey(result.data)
                        Libre3Framing.Signal.CHALLENGE_READY   -> onChallenge(result.data)
                        else -> Libre3SecuritySession.Step.Failed("unexpected signal $signal")
                    }
                )
            }
        }
    }

    /** The challenge characteristic carries both directions; length distinguishes them. */
    private fun onChallenge(data: ByteArray): Libre3SecuritySession.Step = when (data.size) {
        Libre3Gatt.CHALLENGE_REQUEST_LEN  -> session.onChallengeRequest(data)
        Libre3Gatt.CHALLENGE_RESPONSE_LEN -> session.onChallengeResponse(data)
        else -> Libre3SecuritySession.Step.Failed("unexpected challenge length ${data.size}")
    }

    private fun onGlucoseFrame(value: ByteArray) {
        if (glucoseFilled + value.size > GLUCOSE_FRAME_SIZE) glucoseFilled = 0   // resync
        value.copyInto(glucoseFrame, glucoseFilled)
        glucoseFilled += value.size
        if (glucoseFilled < GLUCOSE_FRAME_SIZE) return
        glucoseFilled = 0
        val plain = session.decrypt(Libre3Gatt.Channel.GLUCOSE, glucoseFrame)
        if (plain == null) {
            listener.onError("glucose frame failed authentication")
            return
        }
        listener.onGlucoseRecord(plain)
        // Cheap, and only meaningful right after a packet — piggyback as Juggluco does.
        gatt?.readRemoteRssi()
    }

    /**
     * Ask the sensor to resend retained history from [fromLifeCount] onward.
     *
     * Pass the last lifeCount already stored so only the actual gap comes back. Called after
     * authorisation, and after any reconnect that followed a period out of range — which is
     * exactly when readings were missed.
     */
    fun requestHistory(fromLifeCount: Int) {
        val ch = chars[Libre3Gatt.CHAR_PATCH_CONTROL] ?: return
        val cipher = session.cipherHandle
        if (cipher == 0L) { listener.onError("requestHistory before authorisation"); return }
        val command = Libre3History.controlHistoryCommand(from = fromLifeCount)
        val encrypted = Libre3Native.sessionEncrypt(cipher, Libre3Gatt.Channel.CONTROL_OUT, command)
        if (encrypted == null) { listener.onError("could not encrypt history request"); return }
        enqueue {
            val g = gatt
            if (g == null) { opDone(); return@enqueue }
            @Suppress("DEPRECATION")
            ch.value = encrypted
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(ch)) opDone()
        }
    }

    private fun onHistoricFrame(value: ByteArray) {
        val plain = session.decrypt(Libre3Gatt.Channel.HISTORIC, value)
        if (plain == null) { listener.onError("history frame failed authentication"); return }
        val entries = Libre3History.parseHistory(plain)
        if (entries.isNotEmpty()) listener.onHistory(entries)
    }

    private fun onPatchStatusFrame(value: ByteArray) {
        val plain = session.decrypt(Libre3Gatt.Channel.PATCH_STATUS, value)
        if (plain == null) { listener.onError("patch status frame failed authentication"); return }
        Libre3PatchStatus.parse(plain)?.let(listener::onPatchStatus)
    }

    override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) listener.onRssi(rssi)
    }

    companion object {
        private const val TAG = "Libre3Ble"
        /** How long a connect+handshake may take before we give up and retry. */
        private const val CONNECT_WATCHDOG_MS = 45_000L
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L

        /**
         * Wire size of one 1-minute glucose record: 29 bytes of plaintext plus the session
         * cipher's 4-byte tag and 2-byte sequence trailer.
         */
        const val GLUCOSE_FRAME_SIZE = 35
        const val GLUCOSE_PLAIN_SIZE = 29
    }
}

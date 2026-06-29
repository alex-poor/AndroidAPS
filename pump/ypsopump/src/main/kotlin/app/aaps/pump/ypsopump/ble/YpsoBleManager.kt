package app.aaps.pump.ypsopump.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.commands.StatusCommand
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE manager for the YpsoPump — read-only flow, validated against a real pump in the `ypso-reader`
 * reference app: connect over the existing OS bond -> MD5(mac+salt) auth -> multi-frame read of
 * SYSTEM_STATUS -> XChaCha20-Poly1305 decrypt -> parse -> update [YpsoPumpState].
 *
 * Set the captured session key with [setSharedKey] before connecting. No write/dosing path here yet.
 */
@Singleton
class YpsoBleManager @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val sessionCrypto: SessionCrypto,
    private val pumpState: YpsoPumpState
) {

    enum class ConnectionState { DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, READY, CONNECTED }

    private var bluetoothGatt: BluetoothGatt? = null
    val isConnected: Boolean get() = pumpState.connectionState == ConnectionState.CONNECTED

    companion object {
        private val CHAR_AUTH: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbeb2147bc5")
        private val CHAR_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee48b7bc5")
        private val CHAR_EXTREAD: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcff000000ff")
        // History (events) — used for the zero-therapy write-transport validation.
        private val CHAR_EVENT_COUNT: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecb3b7bc5")
        private val CHAR_EVENT_INDEX: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecc3b7bc5")
        private val CHAR_EVENT_VALUE: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbecd3b7bc5")
        // Control (dosing). Verified against mylife / firmware V05.02.03 (vicktor + SandraK82 agree),
        // not yet on OUR pump — gated behind capture-verify before any live use.
        private val CHAR_BOLUS_START_STOP: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee18b7bc5")
        private val CHAR_BOLUS_STATUS: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee28b7bc5")
        private val CHAR_TBR_START_STOP: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee38b7bc5")
        private val AUTH_SALT = byteArrayOf(
            0x4F, 0xC2.toByte(), 0x45, 0x4D, 0x9B.toByte(), 0x81.toByte(), 0x59, 0xA4.toByte(), 0x93.toByte(), 0xBB.toByte()
        )
        private const val ERR_COUNTER_MISMATCH = 138
        private const val COUNTER_SYNC_TRIES = 40
    }

    /** Seed the captured session key (hex) into the cryptor before connecting. */
    fun setSharedKey(hex: String) {
        sessionCrypto.sharedKey = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Seed the counters before any encrypted WRITE. [writeCounter] = the genuine app's CURRENT value
     * (mylife's numericWriteAppCounter, captured via frida); the first write uses seed+1 (the cryptor
     * pre-increments). [rebootCounter] must match the pump's (mylife's stored value, = 8 currently) or
     * the cryptor resets writeCounter to 0 on the first decrypt. Reads need none of this.
     */
    fun setCounters(writeCounter: Long, rebootCounter: Int) {
        sessionCrypto.writeCounter = writeCounter
        sessionCrypto.rebootCounter = rebootCounter
        aapsLogger.info(LTag.PUMP, "YpsoPump counters seeded: writeCounter=$writeCounter rebootCounter=$rebootCounter")
    }

    val writeCounter: Long get() = sessionCrypto.writeCounter

    /**
     * Open the GATT link and authenticate, then STAY connected. Returns immediately; the connection
     * proceeds asynchronously (CONNECTING -> DISCOVERING -> CONNECTED once MD5 auth succeeds). The
     * AAPS command queue drives reads via [readStatus] while connected and calls [disconnect] when
     * idle — so we must not auto-disconnect here (that caused a 1s reconnect storm).
     */
    @SuppressLint("MissingPermission")
    fun connect(macAddress: String) {
        if (isConnected || pumpState.connectionState == ConnectionState.CONNECTING) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { aapsLogger.error(LTag.PUMP, "YpsoPump: Bluetooth off"); return }
        val device = adapter.getRemoteDevice(macAddress)
        pumpState.pumpAddress = macAddress
        pumpState.connectionState = ConnectionState.CONNECTING
        aapsLogger.info(LTag.PUMP, "YpsoPump connecting to $macAddress (bonded=${device.bondState == BluetoothDevice.BOND_BONDED})")
        synchronized(opLock) { queue.clear(); current = null }
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Read SYSTEM_STATUS over the already-open connection and update [YpsoPumpState]. No disconnect. */
    fun readStatus() {
        if (!isConnected || bluetoothGatt == null) { aapsLogger.warn(LTag.PUMP, "YpsoPump readStatus: not connected"); return }
        readStatusInternal()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        runCatching { bluetoothGatt?.disconnect(); bluetoothGatt?.close() }
        bluetoothGatt = null
        pumpState.connectionState = ConnectionState.DISCONNECTED
    }

    // ---- serial GATT op queue ----
    // Ops are enqueued from the AAPS queue-worker thread ([readStatus]) and completed from the BLE
    // binder/callback thread, so the queue state is guarded by [opLock].
    private class Op(val action: (BluetoothGatt) -> Unit, val onResult: (ByteArray?, Int) -> Unit)
    private val opLock = Any()
    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    private fun enqueue(op: Op) { synchronized(opLock) { queue.addLast(op) }; pumpOps() }
    private fun pumpOps() {
        val op = synchronized(opLock) {
            if (current != null) return
            queue.removeFirstOrNull()?.also { current = it }
        } ?: return
        bluetoothGatt?.let(op.action) ?: complete(null, -1)
    }
    private fun complete(value: ByteArray?, status: Int) {
        val op = synchronized(opLock) { current.also { current = null } }
        op?.onResult(value, status)
        pumpOps()
    }

    @SuppressLint("MissingPermission")
    private fun readOp(uuid: UUID, onResult: (ByteArray?, Int) -> Unit) =
        enqueue(Op({ g -> findChar(g, uuid)?.let { g.readCharacteristic(it) } ?: complete(null, -1) }, onResult))

    private fun readMultiframe(uuid: UUID, done: (ByteArray) -> Unit) {
        val frames = ArrayList<ByteArray>()
        fun step(now: UUID): Unit = readOp(now) { v, s ->
            if (s != BluetoothGatt.GATT_SUCCESS || v == null) { fail("read $now failed (status=$s)"); return@readOp }
            frames.add(v)
            val total = (frames[0][0].toInt() and 0x0F).let { if (it == 0) 1 else it }
            if (frames.size < total) step(CHAR_EXTREAD) else done(reassemble(frames))
        }
        step(uuid)
    }

    private fun reassemble(frames: List<ByteArray>): ByteArray {
        val out = ArrayList<Byte>()
        for (f in frames) if (f.size > 1) for (i in 1 until f.size) out.add(f[i])
        return out.toByteArray()
    }

    // ---- write transport (proven on real hardware via the history-index write) ----
    @SuppressLint("MissingPermission")
    private fun writeOp(uuid: UUID, value: ByteArray, onResult: (ByteArray?, Int) -> Unit) =
        enqueue(Op({ g ->
            findChar(g, uuid)?.let { g.writeCharacteristic(it, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) } ?: complete(null, -1)
        }, onResult))

    /** Write every frame of [payload]; report the LAST frame's status (0 = ok, 138 = counter mismatch). */
    private fun writeFrames(uuid: UUID, payload: ByteArray, onComplete: (Int) -> Unit) {
        val frames = YpsoFraming.chunkPayload(payload)
        fun step(i: Int) {
            if (i >= frames.size) { onComplete(0); return }
            writeOp(uuid, frames[i]) { _, s -> if (s != BluetoothGatt.GATT_SUCCESS) onComplete(s) else step(i + 1) }
        }
        step(0)
    }

    /**
     * Encrypt [command] (already CRC-wrapped or complement-protected by the caller) and write it,
     * auto-syncing the writeCounter: the pump rejects a wrong counter with [ERR_COUNTER_MISMATCH] and
     * does NOT advance, so on 138 we re-encrypt (cryptor pre-increments) and retry, scanning up to
     * [COUNTER_SYNC_TRIES] times until accepted. [onResult] gets true on accept, false on give-up.
     */
    private fun writeEncrypted(uuid: UUID, command: ByteArray, triesLeft: Int = COUNTER_SYNC_TRIES, onResult: (Boolean) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { aapsLogger.warn(LTag.PUMP, "YpsoPump writeEncrypted: not connected"); onResult(false); return }
        val frame = runCatching { sessionCrypto.encrypt(command) }
            .getOrElse { aapsLogger.error(LTag.PUMP, "YpsoPump encrypt error: ${it.message}"); onResult(false); return }
        writeFrames(uuid, frame) { status ->
            when {
                status == BluetoothGatt.GATT_SUCCESS         -> { aapsLogger.info(LTag.PUMP, "YpsoPump write accepted at wc=${sessionCrypto.writeCounter}"); onResult(true) }
                status == ERR_COUNTER_MISMATCH && triesLeft > 0 -> writeEncrypted(uuid, command, triesLeft - 1, onResult)
                else                                         -> { aapsLogger.error(LTag.PUMP, "YpsoPump write failed (status=$status, wc=${sessionCrypto.writeCounter})"); onResult(false) }
            }
        }
    }

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        for (s in g.services) s.getCharacteristic(uuid)?.let { return it }
        return null
    }

    private fun authPassword(mac: String): ByteArray {
        val macBytes = mac.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.getInstance("MD5").digest(macBytes + AUTH_SALT)
    }

    private fun fail(msg: String) { aapsLogger.error(LTag.PUMP, "YpsoPump: $msg"); disconnect() }

    private fun readStatusInternal() {
        readMultiframe(CHAR_STATUS) { frame ->
            runCatching {
                val body = sessionCrypto.decrypt(frame)                       // strips 12-byte LE counter tail
                val payload = if (YpsoCrc.isValid(body)) body.copyOfRange(0, body.size - 2) else body
                val status = StatusCommand().apply { decode(payload) }
                if (status.success) {
                    pumpState.reservoirUnits = status.reservoirUnits
                    pumpState.batteryPercent = status.batteryPercent
                    pumpState.isSuspended = status.isSuspended
                    pumpState.activeTbrPercent = status.activeTbrPercent
                    pumpState.lastStatusTime = System.currentTimeMillis()
                    pumpState.lastConnectionTime = System.currentTimeMillis()
                    aapsLogger.info(LTag.PUMP, "YpsoPump status: reservoir=${status.reservoirUnits}U battery=${status.batteryPercent}%")
                } else {
                    aapsLogger.error(LTag.PUMP, "YpsoPump status decode failed (${payload.size}B)")
                }
            }.onFailure { aapsLogger.error(LTag.PUMP, "YpsoPump status decrypt error: ${it.message}") }
            // Stay connected — the AAPS command queue disconnects when idle.
        }
    }

    // ---- GLB safe variable: value(u32 LE) || ~value(u32 LE) ----
    private fun glbEncode(value: Int): ByteArray {
        val b = ByteArray(8)
        le32(value, b, 0); le32(value.inv(), b, 4); return b
    }

    private fun glbFind(data: ByteArray): Int? {
        if (data.size < 8) return null
        for (s in 0..data.size - 8) {
            val v = u32le(data, s); val c = u32le(data, s + 4)
            if (v == c.inv()) return v
        }
        return null
    }

    private fun le32(v: Int, b: ByteArray, o: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }
    private fun u32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    /**
     * ZERO-THERAPY validation of the encrypted WRITE path against the real pump: read the event
     * history COUNT, write the event INDEX of the newest entry (a read-selection write that does NOT
     * change therapy), then read that entry back. Proves encrypt + multiframe-write + counter
     * auto-sync work end-to-end before any dosing command is built. Requires counters seeded.
     */
    fun validateWriteTransport(onResult: (String) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult("not connected"); return }
        readMultiframe(CHAR_EVENT_COUNT) { fc ->
            val count = runCatching { glbFind(sessionCrypto.decrypt(fc)) }.getOrNull()
            aapsLogger.info(LTag.PUMP, "YpsoPump event history count = $count")
            if (count == null || count <= 0) { onResult("count read failed ($count)"); return@readMultiframe }
            val idx = count - 1
            aapsLogger.info(LTag.PUMP, "YpsoPump writing event index $idx (counter auto-sync from wc=${sessionCrypto.writeCounter})")
            writeEncrypted(CHAR_EVENT_INDEX, YpsoCrc.appendCrc(glbEncode(idx))) { ok ->
                if (!ok) { onResult("index write rejected"); return@writeEncrypted }
                readMultiframe(CHAR_EVENT_VALUE) { fv ->
                    val entry = runCatching {
                        val body = sessionCrypto.decrypt(fv)
                        val p = if (YpsoCrc.isValid(body)) body.copyOfRange(0, body.size - 2) else body
                        "entry(${p.size}B): " + p.joinToString("") { "%02x".format(it) }
                    }.getOrElse { "entry decrypt error: ${it.message}" }
                    pumpState.lastConnectionTime = System.currentTimeMillis()
                    onResult("OK count=$count newest=$entry")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> { pumpState.connectionState = ConnectionState.DISCOVERING; g.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> { pumpState.connectionState = ConnectionState.DISCONNECTED; bluetoothGatt = null }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("service discovery failed ($status)"); return }
            val auth = findChar(g, CHAR_AUTH) ?: run { fail("AUTH characteristic not found"); return }
            pumpState.connectionState = ConnectionState.DISCOVERING
            aapsLogger.info(LTag.PUMP, "YpsoPump connected; writing MD5 auth")
            g.writeCharacteristic(auth, authPassword(pumpState.pumpAddress), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (ch.uuid == CHAR_AUTH) {
                aapsLogger.debug(LTag.PUMP, "auth write status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("auth write failed ($status)"); return }
                // Authenticated: now report CONNECTED so the queue can drive reads. Stay connected.
                pumpState.connectionState = ConnectionState.CONNECTED
                aapsLogger.info(LTag.PUMP, "YpsoPump authenticated; ready")
            } else complete(null, status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            complete(value, status)
    }
}

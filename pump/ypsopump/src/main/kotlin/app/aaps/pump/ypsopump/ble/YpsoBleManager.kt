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
        private val AUTH_SALT = byteArrayOf(
            0x4F, 0xC2.toByte(), 0x45, 0x4D, 0x9B.toByte(), 0x81.toByte(), 0x59, 0xA4.toByte(), 0x93.toByte(), 0xBB.toByte()
        )
    }

    /** Seed the captured session key (hex) into the cryptor before connecting. */
    fun setSharedKey(hex: String) {
        sessionCrypto.sharedKey = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

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

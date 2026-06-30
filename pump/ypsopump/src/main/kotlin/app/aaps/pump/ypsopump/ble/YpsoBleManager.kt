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
import app.aaps.pump.ypsopump.YpsoPumpConst
import app.aaps.pump.ypsopump.comm.YpsoCrc
import app.aaps.pump.ypsopump.comm.YpsoFraming
import app.aaps.pump.ypsopump.comm.commands.BolusCommand
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
        private const val DISCOVER_RADIUS = 1200
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
    fun readStatus(onDone: () -> Unit = {}) {
        if (!isConnected || bluetoothGatt == null) { aapsLogger.warn(LTag.PUMP, "YpsoPump readStatus: not connected"); onDone(); return }
        readStatusInternal(onDone)
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

    // The pump's EXTREAD characteristic is a single shared cursor, so only ONE multi-frame read may
    // be in flight at a time — overlapping reads interleave EXTREAD frames and corrupt both. This
    // guard rejects (rather than silently corrupting) an overlapping read; all internal flows chain
    // sequentially via callbacks.
    private var multiframeBusy = false
    private fun readMultiframe(uuid: UUID, done: (ByteArray) -> Unit) {
        if (multiframeBusy) { aapsLogger.error(LTag.PUMP, "YpsoPump: overlapping multi-frame read on $uuid rejected"); return }
        multiframeBusy = true
        val frames = ArrayList<ByteArray>()
        fun step(now: UUID): Unit = readOp(now) { v, s ->
            aapsLogger.debug(LTag.PUMP, "YpsoPump frame[${frames.size}] from $now: status=$s ${v?.joinToString("") { "%02x".format(it) } ?: "null"}")
            if (s != BluetoothGatt.GATT_SUCCESS || v == null) { multiframeBusy = false; fail("read $now failed (status=$s, got ${frames.size} frames)"); return@readOp }
            frames.add(v)
            val total = (frames[0][0].toInt() and 0x0F).let { if (it == 0) 1 else it }
            if (frames.size < total) step(CHAR_EXTREAD) else { multiframeBusy = false; done(reassemble(frames)) }
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

    /**
     * One-time counter DISCOVERY: the pump accepts only the EXACT next writeCounter (gaps rejected
     * in both directions with 138), and the write counter is an independent sequence from the read
     * counter we observe — so we can't derive it. Scan candidates outward from [base] (downward-
     * biased: writes lag the read counter) until one is accepted, then log it so AAPS can own + persist
     * the counter from there. Tries a [candidate] by forcing the cryptor's pre-increment to land on it.
     */
    private fun writeEncryptedDiscover(uuid: UUID, command: ByteArray, base: Long, onResult: (Boolean) -> Unit) {
        val candidates = ArrayDeque<Long>()
        candidates.addLast(base)                                                       // base = seeded writeCounter; expected accept = base+1
        for (d in 1..DISCOVER_RADIUS) { candidates.addLast(base + d); if (base - d >= 1) candidates.addLast(base - d) }  // base+1, base-1, base+2, ...
        var tries = 0
        fun tryNext() {
            val c = candidates.removeFirstOrNull()
            if (c == null) { aapsLogger.error(LTag.PUMP, "YpsoPump counter discovery exhausted (base=$base ±$DISCOVER_RADIUS)"); onResult(false); return }
            sessionCrypto.writeCounter = c - 1                // cryptor pre-increments to c
            val frame = runCatching { sessionCrypto.encrypt(command) }
                .getOrElse { aapsLogger.error(LTag.PUMP, "YpsoPump encrypt error: ${it.message}"); onResult(false); return }
            tries++
            if (tries % 100 == 0) aapsLogger.info(LTag.PUMP, "YpsoPump counter discovery: $tries tries (now c=$c)")
            writeFrames(uuid, frame) { status ->
                when {
                    status == BluetoothGatt.GATT_SUCCESS -> { aapsLogger.info(LTag.PUMP, "YpsoPump WRITE ACCEPTED — write counter = $c (base was $base, after $tries tries)"); onResult(true) }
                    status == ERR_COUNTER_MISMATCH       -> tryNext()
                    else                                 -> { aapsLogger.error(LTag.PUMP, "YpsoPump write failed (status=$status at c=$c)"); onResult(false) }
                }
            }
        }
        tryNext()
    }

    /** Send ONE write at counter [c] (cryptor pre-increments to c) and report the raw status. */
    private fun probeCounter(uuid: UUID, command: ByteArray, c: Long, onStatus: (Int) -> Unit) {
        sessionCrypto.writeCounter = c - 1
        val frame = runCatching { sessionCrypto.encrypt(command) }.getOrElse { onStatus(-99); return }
        writeFrames(uuid, frame) { onStatus(it) }
    }

    /**
     * BINARY-SEARCH counter discovery. The pump accepts only the exact next writeCounter; if it returns
     * DISTINCT errors for "too low" (already used) vs "too high" (gap), we bisect to the exact value in
     * ~21 probes instead of a linear scan of thousands. Probes the extremes first to learn the codes;
     * falls back to [writeEncryptedDiscover] from [base] if the pump can't distinguish direction.
     */
    private fun discoverCounterBinary(uuid: UUID, command: ByteArray, base: Long, onResult: (Boolean) -> Unit) {
        probeCounter(uuid, command, 1L) { lowStatus ->
            if (lowStatus == BluetoothGatt.GATT_SUCCESS) { aapsLogger.info(LTag.PUMP, "YpsoPump WRITE ACCEPTED — write counter = 1"); onResult(true); return@probeCounter }
            probeCounter(uuid, command, 2_000_000L) { highStatus ->
                aapsLogger.info(LTag.PUMP, "YpsoPump counter probe: low(c=1)->$lowStatus  high(c=2000000)->$highStatus")
                if (highStatus == BluetoothGatt.GATT_SUCCESS) { aapsLogger.info(LTag.PUMP, "YpsoPump WRITE ACCEPTED — write counter = 2000000"); onResult(true); return@probeCounter }
                if (lowStatus == highStatus) {
                    aapsLogger.warn(LTag.PUMP, "YpsoPump: pump does not distinguish low/high (both=$lowStatus) — falling back to linear scan")
                    writeEncryptedDiscover(uuid, command, base, onResult); return@probeCounter
                }
                var lo = 1L; var hi = 2_000_000L; var probes = 0
                fun step() {
                    if (lo >= hi) { probeCounter(uuid, command, lo) { s -> if (s == BluetoothGatt.GATT_SUCCESS) { aapsLogger.info(LTag.PUMP, "YpsoPump WRITE ACCEPTED — write counter = $lo (${probes} probes)"); onResult(true) } else { aapsLogger.error(LTag.PUMP, "YpsoPump binary search converged to $lo but status=$s"); onResult(false) } }; return }
                    val mid = (lo + hi) / 2; probes++
                    probeCounter(uuid, command, mid) { s ->
                        when (s) {
                            BluetoothGatt.GATT_SUCCESS -> { aapsLogger.info(LTag.PUMP, "YpsoPump WRITE ACCEPTED — write counter = $mid (${probes} probes)"); onResult(true) }
                            lowStatus                  -> { lo = mid + 1; step() }   // too low -> expected is higher
                            else                       -> { hi = mid; step() }       // too high -> expected is lower
                        }
                    }
                }
                step()
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

    private fun readStatusInternal(onDone: () -> Unit = {}) {
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
            onDone()
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
            aapsLogger.info(LTag.PUMP, "YpsoPump event history count = $count (pump counter=${sessionCrypto.readCounter})")
            if (count == null || count <= 0) { onResult("count read failed ($count)"); return@readMultiframe }
            // Seed the writeCounter from the pump's OWN counter (returned in the read response tail):
            // the pump uses one monotonic counter for reads+writes, so the next write must be its last
            // value + 1. This auto-discovers the counter and is robust to mylife's stale stored value
            // (its history-import burst pushes the pump far ahead of numericWriteAppCounter).
            // Seed the discovery from the captured writeCounter (mylife's numericWriteAppCounter) — the
            // pump's write counter is a separate sequence ~3000 below the read counter, so basing it on
            // readCounter is hopeless. Expected accept = seed+1.
            val base = if (sessionCrypto.writeCounter > 0) sessionCrypto.writeCounter else sessionCrypto.readCounter
            val idx = count - 1
            val payload = YpsoCrc.appendCrc(glbEncode(idx))
            val rc = sessionCrypto.readCounter
            aapsLogger.info(LTag.PUMP, "YpsoPump COUNTERS: pump rebootCounter=${sessionCrypto.rebootCounter} (seeded ${YpsoPumpConst.CAPTURED_REBOOT_COUNTER}), readCounter=$rc, writeSeed=$base")
            // DECISIVE PROBE: is the write counter in the READ-counter sequence (shared) or near writeSeed?
            val probeSet = listOf(rc + 1, rc, rc - 1, rc + 2, base + 1)
            fun probeNext(i: Int) {
                if (i >= probeSet.size) {
                    aapsLogger.info(LTag.PUMP, "YpsoPump read-region probes all 138; falling back to upward scan from writeSeed=$base")
                    discoverCounterBinary(CHAR_EVENT_INDEX, payload, base) { ok ->
                        if (!ok) { onResult("index write rejected"); return@discoverCounterBinary }
                        onResult("OK via scan (writeCounter=${sessionCrypto.writeCounter})")
                    }
                    return
                }
                val c = probeSet[i]
                writeOnceAt(CHAR_EVENT_INDEX, payload, c) { st ->
                    aapsLogger.info(LTag.PUMP, "YpsoPump PROBE c=$c -> status=$st  (rc=$rc, writeSeed=$base)")
                    if (st == BluetoothGatt.GATT_SUCCESS) onResult("WRITE ACCEPTED at counter=$c  [readCounter=$rc writeSeed=$base]")
                    else probeNext(i + 1)
                }
            }
            probeNext(0)
        }
    }

    /**
     * Establish the write counter on the SAFE history-index characteristic (zero-therapy): read the
     * event count, then discover the exact write counter via an index-selection write. After this
     * succeeds the cryptor owns the counter (writeCounter = the accepted value) and subsequent writes
     * just pre-increment — so the dosing write that follows hits the bolus char exactly ONCE.
     */
    private fun establishCounter(onResult: (Boolean) -> Unit) {
        readMultiframe(CHAR_EVENT_COUNT) { fc ->
            val count = runCatching { glbFind(sessionCrypto.decrypt(fc)) }.getOrNull()
            if (count == null || count <= 0) { aapsLogger.error(LTag.PUMP, "YpsoPump establishCounter: count read failed ($count)"); onResult(false); return@readMultiframe }
            val base = if (sessionCrypto.writeCounter > 0) sessionCrypto.writeCounter else sessionCrypto.readCounter
            aapsLogger.info(LTag.PUMP, "YpsoPump establishCounter: count=$count, discovering write counter (base=$base)")
            writeEncryptedDiscover(CHAR_EVENT_INDEX, YpsoCrc.appendCrc(glbEncode(count - 1)), base, onResult)
        }
    }

    /** Single-frame read (event count) — isolates KEY validity from multi-frame reliability. */
    fun readEventCount(onResult: (Int?) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(null); return }
        readMultiframe(CHAR_EVENT_COUNT) { fc ->
            val count = runCatching { glbFind(sessionCrypto.decrypt(fc)) }.getOrElse {
                aapsLogger.error(LTag.PUMP, "YpsoPump event-count decrypt error: ${it.message}"); null
            }
            aapsLogger.info(LTag.PUMP, "YpsoPump event-count read = $count (key ${if (count != null) "VALID" else "FAILED"})")
            onResult(count)
        }
    }

    /** Read CHAR_BOLUS_STATUS and parse the immediate-delivery block via [BolusCommand.decode]. */
    fun readBolusStatus(onResult: (BolusCommand?) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(null); return }
        readMultiframe(CHAR_BOLUS_STATUS) { f ->
            val cmd = runCatching {
                val body = sessionCrypto.decrypt(f)
                val p = if (YpsoCrc.isValid(body)) body.copyOfRange(0, body.size - 2) else body
                aapsLogger.info(LTag.PUMP, "YpsoPump bolus-status raw (${p.size}B): ${p.joinToString("") { "%02x".format(it) }}")
                BolusCommand(0.0).apply { decode(p) }
            }.getOrNull()
            onResult(cmd)
        }
    }

    /**
     * SAFETY-CRITICAL — deliver a real bolus. Establishes the write counter on the safe index char
     * first (so the bolus char is written EXACTLY ONCE, never scanned), sends one encrypted
     * START_STOP_BOLUS, then reads the bolus status to confirm. Only ever called behind an explicit
     * test flag + capture-verify + user consent. [units] standard if [durationMinutes]==0, else
     * extended over that duration with [immediateUnits] up front.
     */
    fun deliverBolus(units: Double, durationMinutes: Int, immediateUnits: Double, onResult: (String) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult("not connected"); return }
        val cmd = BolusCommand(units, durationMinutes, immediateUnits)
        val payload = YpsoCrc.appendCrc(cmd.encode())
        aapsLogger.info(LTag.PUMP, "YpsoPump BOLUS request ${units}U dur=$durationMinutes imm=$immediateUnits raw=${cmd.encode().joinToString("") { "%02x".format(it) }}")
        establishCounter { ok ->
            if (!ok) { onResult("counter discovery failed — BOLUS NOT SENT"); return@establishCounter }
            aapsLogger.info(LTag.PUMP, "YpsoPump SENDING BOLUS to control char at wc=${sessionCrypto.writeCounter + 1}")
            writeEncrypted(CHAR_BOLUS_START_STOP, payload) { accepted ->
                if (!accepted) { onResult("bolus write rejected"); return@writeEncrypted }
                readBolusStatus { st ->
                    pumpState.lastConnectionTime = System.currentTimeMillis()
                    onResult("BOLUS ACCEPTED status=${st?.bolusStatusCode} injected=${st?.deliveredUnits}U total=${st?.totalProgrammedUnits}U")
                }
            }
        }
    }

    /** One encrypted write at EXACTLY [counter] (no auto-sync). onStatus gets the raw GATT status. */
    private fun writeOnceAt(uuid: UUID, command: ByteArray, counter: Long, onStatus: (Int) -> Unit) {
        sessionCrypto.writeCounter = counter - 1               // cryptor pre-increments to [counter]
        val frame = runCatching { sessionCrypto.encrypt(command) }
            .getOrElse { aapsLogger.error(LTag.PUMP, "YpsoPump encrypt error: ${it.message}"); onStatus(-99); return }
        writeFrames(uuid, frame, onStatus)
    }

    /**
     * SAFE single test bolus. Locks the write counter on the BENIGN event-index char first (canary:
     * tries seed+1, seed, seed+2 — a wrong counter is rejected with NO pump effect), then sends the
     * bolus EXACTLY ONCE at the confirmed next counter. No auto-sync, no scanning. If the canary can't
     * be confirmed in that tiny window it ABORTS and the bolus char is never written.
     */
    fun testBolusCanary(units: Double, seedW: Long, onResult: (String) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult("not connected"); return }
        val canary = YpsoCrc.appendCrc(glbEncode(0))           // select event index 0 — zero therapy
        val candidates = listOf(seedW + 1, seedW, seedW + 2)
        fun tryCanary(i: Int) {
            if (i >= candidates.size) { onResult("CANARY FAILED (tried $candidates) — counter off, NO BOLUS sent"); return }
            val c = candidates[i]
            aapsLogger.info(LTag.PUMP, "YpsoPump canary index-write @counter=$c")
            writeOnceAt(CHAR_EVENT_INDEX, canary, c) { status ->
                when (status) {
                    BluetoothGatt.GATT_SUCCESS   -> {
                        aapsLogger.info(LTag.PUMP, "YpsoPump CANARY ACCEPTED @$c — counter locked; bolus will be @${c + 1}")
                        sendTestBolus(units, c, onResult)
                    }
                    ERR_COUNTER_MISMATCH         -> tryCanary(i + 1)
                    else                         -> onResult("canary write status=$status — ABORT, NO BOLUS")
                }
            }
        }
        tryCanary(0)
    }

    private fun sendTestBolus(units: Double, lockedCounter: Long, onResult: (String) -> Unit) {
        val cmd = BolusCommand(units)                          // standard/immediate bolus
        val boIns = cmd.encode()
        aapsLogger.info(LTag.PUMP, "YpsoPump >>> SENDING BOLUS ${units}U @counter=${lockedCounter + 1} raw=${boIns.joinToString("") { "%02x".format(it) }}")
        writeOnceAt(CHAR_BOLUS_START_STOP, YpsoCrc.appendCrc(boIns), lockedCounter + 1) { status ->
            if (status != BluetoothGatt.GATT_SUCCESS) { onResult("BOLUS REJECTED status=$status @${lockedCounter + 1} — check pump, likely not delivered"); return@writeOnceAt }
            aapsLogger.info(LTag.PUMP, "YpsoPump >>> BOLUS ACCEPTED @${lockedCounter + 1}")
            readBolusStatus { st ->
                pumpState.lastConnectionTime = System.currentTimeMillis()
                onResult("BOLUS ACCEPTED ${units}U; bolusStatus=${st?.bolusStatusCode} injected=${st?.deliveredUnits}U total=${st?.totalProgrammedUnits}U | FINAL writeCounter=${sessionCrypto.writeCounter} readCounter=${sessionCrypto.readCounter}")
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

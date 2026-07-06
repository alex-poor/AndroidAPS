package app.aaps.pump.ypsopump.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
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
import app.aaps.pump.ypsopump.comm.commands.TbrCommand
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
        // Control-notification characteristic (handle 0x006f). mylife enables NOTIFY (CCCD 0x0001) on
        // this as its FIRST op after connect, before any write; the pump GATES control writes on this
        // subscription and rejects writes with ATT app-error 0x8A (138) if it is absent. Confirmed by
        // diffing mylife vs AAPS btsnoop: mylife 0 ERROR_RSP / AAPS 3423× err=0x8A on event-index
        // writes, the ONLY difference being this subscription. This is the write-handshake precondition.
        private val CHAR_CTRL_NOTIFY: UUID = UUID.fromString("669a0c20-0008-969e-e211-fcbee58b7bc5")
        private val AUTH_SALT = byteArrayOf(
            0x4F, 0xC2.toByte(), 0x45, 0x4D, 0x9B.toByte(), 0x81.toByte(), 0x59, 0xA4.toByte(), 0x93.toByte(), 0xBB.toByte()
        )
        // ATT application-error 0x8A the pump returns on a rejected WRITE — a GENERIC "command invalid".
        // On-device testing disproved both earlier theories: it is NOT a write-counter mismatch (every
        // counter probed gave 0x8A) and NOT a missing notification subscription (writes still got 0x8A
        // with CHAR_CTRL_NOTIFY enabled). The actual cause was a MALFORMED command — a CRC wrongly
        // appended to the 8-byte GLB index command (fixed: index writes now send bare glbEncode). Whether
        // a wrong write-counter also surfaces as 0x8A vs a distinct code is still TBD on the pump.
        private const val ERR_WRITE_REJECTED = 138
        // App-error 0x8B (139) = the write counter is BEHIND the pump's (the pump advanced on a write whose
        // BLE ack we never saw — a dropped Write-Response or a transient disconnect — so our persisted
        // counter is off by >=1 and the pump requires strictly-greater). Recover by scanning the counter
        // FORWARD (benign zero-therapy canary) until accepted; see [COUNTER_RESYNC_SCAN].
        private const val ERR_COUNTER_BEHIND = 139
        // How far to scan the write counter forward when it is behind (each step = one benign canary write).
        // Desync is normally +1/+2; a wide-ish bound covers multiple lost acks without unbounded runaway.
        private const val COUNTER_RESYNC_SCAN = 32
        // App-error 0x86 (134) on a TBR write = the pump already has an ACTIVE temp basal and refuses to
        // START a new one until the current one is STOPped (CHAR_TBR_START_STOP is a start/stop char; a
        // 0%/suspend TBR triggers this too). Recover by cancelling (100%/0) then re-sending. [confirmed
        // on-device 2026-07-02: 333% and 0% both rejected 0x86 while a prior 0% TBR was active]
        private const val ERR_TBR_ACTIVE = 134

        // Event-history entry types (tech-doc §10.6). Fast-bolus events carry the units in v1 (hundredths U);
        // a CANCELLED event additionally carries the requested amount in v2. Used by [readLastFastBolusEvent]
        // to reconcile a delivered dose against the pump's OWN record when the live confirm-by-read is lost.
        const val EVT_FAST_BOLUS_STARTED = 1
        const val EVT_FAST_BOLUS_COMPLETED = 2
        const val EVT_FAST_BOLUS_CANCELLED = 3
    }

    /** Seed the captured session key (hex) into the cryptor before connecting. */
    fun setSharedKey(hex: String) {
        sessionCrypto.sharedKey = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    // AAPS OWNS the write counter once mylife is off (sole controller): persist it across reconnects so a
    // stale const-seed can't push the pump's forward-gap-tolerant counter ahead and desync anything. The
    // pump reboot resets its write counter to 0, but forward-gap tolerance means our higher persisted
    // value is still accepted, so no reboot handling is needed (rebootCounter itself must still match —
    // re-seed if the pump battery is changed).
    private val ypsoPrefs by lazy { context.getSharedPreferences("ypso_ble_state", Context.MODE_PRIVATE) }
    private fun persistWriteCounter() { ypsoPrefs.edit().putLong("writeCounter", sessionCrypto.writeCounter).apply() }

    /**
     * Resolve the session key at runtime: a value persisted in prefs (ypso_ble_state / [YpsoPumpConst.PREF_SHARED_KEY])
     * WINS over the build-time [fallbackHex]. This lets a re-captured key be dropped into prefs (adb/frida) WITHOUT
     * rebuilding, and keeps the key out of the APK. Returns "" if neither is set. (Model-1 onboarding: the key is
     * always established by the genuine app + captured — see memory/model3-keyexchange-backend.md.)
     */
    fun resolveSharedKey(fallbackHex: String): String {
        val fromPrefs = ypsoPrefs.getString(YpsoPumpConst.PREF_SHARED_KEY, null)?.trim().orEmpty()
        return if (fromPrefs.isNotEmpty()) fromPrefs else fallbackHex
    }

    /** rebootCounter from prefs ([YpsoPumpConst.PREF_REBOOT_COUNTER]) if set, else [fallback]. Changes only on a pump battery pull. */
    fun resolveRebootCounter(fallback: Int): Int = ypsoPrefs.getInt(YpsoPumpConst.PREF_REBOOT_COUNTER, fallback)

    /**
     * Resolve the pump BLE MAC at runtime: prefs ([YpsoPumpConst.PREF_PUMP_MAC]) WIN over the build-time
     * [fallbackMac]. Keeps the user's pump address out of the APK/source (it's per-user, like the key).
     */
    fun resolvePumpMac(fallbackMac: String): String {
        val fromPrefs = ypsoPrefs.getString(YpsoPumpConst.PREF_PUMP_MAC, null)?.trim().orEmpty()
        return if (fromPrefs.isNotEmpty()) fromPrefs else fallbackMac
    }

    /** Persist a freshly-captured key into prefs so it survives rebuilds/reconnects (call after a re-capture). */
    fun saveSharedKey(hex: String) { ypsoPrefs.edit().putString(YpsoPumpConst.PREF_SHARED_KEY, hex.trim()).apply() }

    /**
     * Seed the counters before any encrypted WRITE. [writeCounter] = the genuine app's CURRENT value
     * (mylife's numericWriteAppCounter, captured via frida) for the FIRST ever run; thereafter the
     * PERSISTED, AAPS-owned value wins (we use the higher of the two). The first write uses value+1 (the
     * cryptor pre-increments). [rebootCounter] must match the pump's or the cryptor resets on decrypt.
     */
    fun setCounters(writeCounter: Long, rebootCounter: Int) {
        val persisted = ypsoPrefs.getLong("writeCounter", -1L)
        sessionCrypto.writeCounter = if (persisted > writeCounter) persisted else writeCounter
        sessionCrypto.rebootCounter = rebootCounter
        aapsLogger.info(LTag.PUMP, "YpsoPump counters seeded: writeCounter=${sessionCrypto.writeCounter} (seed=$writeCounter persisted=$persisted) rebootCounter=$rebootCounter")
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
        // ROOT CAUSE of large boluses being delivered but NOT recorded: AAPS's confirmActivePump() treats a
        // pump with an EMPTY serial as "unregistered" and falls back to a 1-minute freshness gate on EVERY
        // synced record (`timestamp > now - 1min`). The Ypso serial was never populated, so a bolus that
        // takes >1 min to deliver (a big meal bolus) reached syncBolus with a start-timestamp already older
        // than that gate -> the sync was silently rejected and the delivered insulin never hit the DB/IOB
        // (carbs use a non-gated path, hence "carbs recorded, bolus missing"). Seed a STABLE non-empty serial
        // from the pump MAC so AAPS registers the pump ONCE and thereafter gates on registration time, not
        // freshness. (Also stops the every-5-min re-register churn and the spurious WRONG_PUMP_DATA path.)
        if (pumpState.serialNumber.isEmpty()) pumpState.serialNumber = macAddress.replace(":", "")
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
     * Encrypt [command] (already CRC-wrapped or complement-protected by the caller) and write it ONCE at
     * the cryptor's current (pre-incremented) writeCounter. The pump's write check is forward-gap tolerant,
     * so we must NOT retry/scan on rejection — a higher counter would be accepted but corrupt the shared
     * counter space and break mylife. The caller is responsible for having locked the exact write counter
     * first (e.g. via [establishCounter]). [onResult] true on accept, false on reject.
     */
    private fun writeEncrypted(uuid: UUID, command: ByteArray, onResult: (Boolean) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { aapsLogger.warn(LTag.PUMP, "YpsoPump writeEncrypted: not connected"); onResult(false); return }
        val frame = runCatching { sessionCrypto.encrypt(command) }
            .getOrElse { aapsLogger.error(LTag.PUMP, "YpsoPump encrypt error: ${it.message}"); onResult(false); return }
        writeFrames(uuid, frame) { status ->
            if (status == BluetoothGatt.GATT_SUCCESS) { aapsLogger.info(LTag.PUMP, "YpsoPump write accepted at wc=${sessionCrypto.writeCounter}"); onResult(true) }
            else { aapsLogger.error(LTag.PUMP, "YpsoPump write rejected (status=$status, wc=${sessionCrypto.writeCounter}) — NOT scanning (would corrupt the counter)"); onResult(false) }
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
                    // DIAG: log the decoded delivery mode + isSuspended + raw payload so a pump-side Stop can be
                    // seen (validate DeliveryMode.STOPPED/PAUSED against real firmware; raw shows which byte moves).
                    aapsLogger.info(LTag.PUMP, "YpsoPump status: reservoir=${status.reservoirUnits}U battery=${status.batteryPercent}% deliveryMode=${status.deliveryMode}(${status.deliveryModeName}) suspended=${status.isSuspended} raw=${payload.joinToString("") { "%02x".format(it) }}")
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
    private fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

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
            aapsLogger.info(LTag.PUMP, "YpsoPump event history count = $count (pump readCounter=${sessionCrypto.readCounter})")
            if (count == null || count <= 0) { onResult("count read failed ($count)"); return@readMultiframe }
            if (sessionCrypto.writeCounter <= 0) {
                onResult("no write counter seeded — set CAPTURED_WRITE_COUNTER to the pump's CURRENT write counter")
                return@readMultiframe
            }
            // SAFE single write at the WRITE counter + 1. The pump's write-counter check is FORWARD-GAP
            // TOLERANT (accepts ANY counter > last_write, not just exactly-next), so probing read-counter
            // values or scanning UPWARD is DANGEROUS: a too-high value is accepted and jumps the pump's
            // write counter far ahead of where mylife sits, making mylife's subsequent writes read as
            // replays (APPERR_COUNTER_ERROR — it breaks). So write EXACTLY ONCE at writeCounter+1 from the
            // (separate) write sequence; NEVER use a read-counter value and NEVER scan. On reject the seed
            // is stale → abort and reseed (do NOT search upward). [confirmed on pump 2026-06-30]
            val base = sessionCrypto.writeCounter
            // Index/selection commands are complement-protected (value||~value) and carry NO CRC — verified
            // against mylife's accepted event-index writes (8-byte `00000000ffffffff`). A CRC here → 0x8A.
            val payload = glbEncode(count - 1)
            aapsLogger.info(LTag.PUMP, "YpsoPump write-validate: single index write at writeCounter=${base + 1} (readCounter=${sessionCrypto.readCounter} is NOT used for writes)")
            writeOnceAt(CHAR_EVENT_INDEX, payload, base + 1) { st ->
                when (st) {
                    BluetoothGatt.GATT_SUCCESS -> onResult("WRITE ACCEPTED at writeCounter=${base + 1}")
                    ERR_WRITE_REJECTED         -> onResult("write rejected (138) at writeCounter=${base + 1} — seed stale; reseed CAPTURED_WRITE_COUNTER from the pump's current write counter (NOT scanning, would corrupt the counter)")
                    else                       -> onResult("write failed status=$st at writeCounter=${base + 1}")
                }
            }
        }
    }

    /**
     * Lock the write counter on the SAFE history-index characteristic (zero-therapy) with ONE write at
     * writeCounter+1, so the dosing write that follows pre-increments to exactly +2 and hits the bolus
     * char once. The write counter MUST be seeded accurately (the pump's current write counter): the
     * forward-gap-tolerant check means a too-high value would be accepted but corrupt the shared counter
     * space and break mylife — so on reject we ABORT rather than scan. [see validateWriteTransport]
     */
    private fun establishCounter(onResult: (Boolean) -> Unit) {
        readMultiframe(CHAR_EVENT_COUNT) { fc ->
            val count = runCatching { glbFind(sessionCrypto.decrypt(fc)) }.getOrNull()
            if (count == null || count <= 0) { aapsLogger.error(LTag.PUMP, "YpsoPump establishCounter: count read failed ($count)"); onResult(false); return@readMultiframe }
            if (sessionCrypto.writeCounter <= 0) { aapsLogger.error(LTag.PUMP, "YpsoPump establishCounter: no write counter seeded"); onResult(false); return@readMultiframe }
            val base = sessionCrypto.writeCounter
            aapsLogger.info(LTag.PUMP, "YpsoPump establishCounter: single index write at writeCounter=${base + 1}")
            writeOnceAt(CHAR_EVENT_INDEX, glbEncode(count - 1), base + 1) { st ->   // GLB command: no CRC
                if (st == BluetoothGatt.GATT_SUCCESS) onResult(true)                // cryptor.writeCounter now = base+1
                else { aapsLogger.error(LTag.PUMP, "YpsoPump establishCounter rejected (status=$st) — reseed write counter; NOT scanning"); onResult(false) }
            }
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
     * RECONCILIATION — the pump's OWN record of the most recent FAST bolus (history event types
     * 1=started / 2=completed / 3=cancelled; delivered units in v1, hundredths U). This is ground
     * truth for recovering a bolus whose live confirm-by-read was lost (dropped ack / BLE blip):
     * the pump advanced and delivered, but AAPS never saw the confirmation.
     *
     * Reads the event COUNT, then scans at most [maxScan] NEWEST entries (each entry = one BENIGN
     * event-index write, forward-only at writeCounter+1 exactly like the dosing canary, + one value
     * read), returning the newest COMPLETED/CANCELLED fast-bolus entry. Stops at the first match.
     *
     * SAFETY: purely ADDITIVE and read-only w.r.t. therapy — it only ever *reads* the pump's history.
     * On ANY failure (not connected, no seeded counter, count read fails, a rejected index write, a
     * decrypt/parse error, or no fast-bolus entry in range) it returns null and the caller keeps its
     * existing behaviour. It NEVER scans the write counter (a rejected index write aborts), so it can
     * neither double-dose nor corrupt the shared counter.
     */
    fun readLastFastBolusEvent(maxScan: Int = 4, onResult: (YpsoHistoryEntry?) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(null); return }
        if (sessionCrypto.writeCounter <= 0) { aapsLogger.warn(LTag.PUMP, "YpsoPump reconcile: no write counter seeded"); onResult(null); return }
        readMultiframe(CHAR_EVENT_COUNT) { fc ->
            val count = runCatching { glbFind(sessionCrypto.decrypt(fc)) }.getOrNull()
            if (count == null || count <= 0) { aapsLogger.warn(LTag.PUMP, "YpsoPump reconcile: event count read failed ($count)"); onResult(null); return@readMultiframe }
            val newest = count - 1
            val floor = maxOf(0, count - maxScan)
            fun scan(idx: Int) {
                if (idx < floor) { aapsLogger.info(LTag.PUMP, "YpsoPump reconcile: no fast-bolus event in newest ${count - floor} entries"); onResult(null); return }
                // Select entry [idx] with ONE benign forward-only index write at writeCounter+1 (the pump's
                // check is forward-gap tolerant; persist on accept so AAPS stays in sync). Reject → abort.
                val c = sessionCrypto.writeCounter + 1
                writeOnceAt(CHAR_EVENT_INDEX, glbEncode(idx), c) { st ->
                    if (st != BluetoothGatt.GATT_SUCCESS) { aapsLogger.warn(LTag.PUMP, "YpsoPump reconcile: index write @$c rejected (status=$st) — abort"); onResult(null); return@writeOnceAt }
                    persistWriteCounter()
                    readMultiframe(CHAR_EVENT_VALUE) { vf ->
                        val entry = runCatching {
                            val body = sessionCrypto.decrypt(vf)
                            val p = if (YpsoCrc.isValid(body)) body.copyOfRange(0, body.size - 2) else body
                            parseHistoryEntry(p)
                        }.getOrNull()
                        aapsLogger.info(LTag.PUMP, "YpsoPump reconcile: entry idx=$idx type=${entry?.eventType} v1=${entry?.v1} v2=${entry?.v2} ts=${entry?.timestamp}")
                        if (entry != null && (entry.eventType == EVT_FAST_BOLUS_COMPLETED || entry.eventType == EVT_FAST_BOLUS_CANCELLED)) onResult(entry)
                        else scan(idx - 1)
                    }
                }
            }
            scan(newest)
        }
    }

    /** Parse a 17-byte history entry (tech-doc §10.6). Returns null if too short. */
    private fun parseHistoryEntry(p: ByteArray): YpsoHistoryEntry? {
        if (p.size < 17) return null
        return YpsoHistoryEntry(
            timestamp = u32le(p, 0).toLong() and 0xFFFFFFFFL,
            eventType = p[4].toInt() and 0xFF,
            v1 = u16le(p, 5), v2 = u16le(p, 7), v3 = u16le(p, 9),
            sequence = u32le(p, 11).toLong() and 0xFFFFFFFFL,
            index = u16le(p, 15)
        )
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
     * SAFE bolus (production + test). Locks the write counter on the BENIGN event-index char first
     * (canary: tries seed+1, seed, seed+2 — a wrong counter is rejected with NO pump effect), then sends
     * the bolus EXACTLY ONCE at the confirmed next counter. No auto-sync, no scanning. If the canary
     * can't be confirmed it ABORTS and the bolus char is never written. The write counter is persisted
     * after each accepted write so AAPS owns it across reconnects. [onResult] = (accepted, message).
     */
    fun testBolusCanary(units: Double, seedW: Long, onResult: (Boolean, String) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(false, "not connected"); return }
        val canary = glbEncode(0)                              // select event index 0 — zero therapy (GLB, no CRC)
        // SAFETY: unlike the TBR path, the BOLUS canary does NOT scan the counter forward. A dropped ack on a
        // bolus write can mean the pump ALREADY DELIVERED; self-healing the counter would let a retry double
        // dose. So on a counter error we FAIL CLOSED (abort). Proper fix (TODO): on ambiguous counter, read
        // CHAR_BOLUS_STATUS to confirm whether the prior bolus landed before allowing another.
        val candidates = listOf(seedW + 1, seedW, seedW + 2)
        fun tryCanary(i: Int) {
            if (i >= candidates.size) { onResult(false, "canary failed (tried $candidates) — counter off, NO BOLUS sent (fail-closed; reseed after confirming no bolus was delivered)"); return }
            val c = candidates[i]
            aapsLogger.info(LTag.PUMP, "YpsoPump canary index-write @counter=$c")
            writeOnceAt(CHAR_EVENT_INDEX, canary, c) { status ->
                when (status) {
                    BluetoothGatt.GATT_SUCCESS   -> {
                        persistWriteCounter()
                        aapsLogger.info(LTag.PUMP, "YpsoPump CANARY ACCEPTED @$c — counter locked; bolus will be @${c + 1}")
                        sendTestBolus(units, c, onResult)
                    }
                    ERR_WRITE_REJECTED         -> tryCanary(i + 1)
                    else                         -> onResult(false, "canary write status=$status — ABORT, NO BOLUS")
                }
            }
        }
        tryCanary(0)
    }

    private fun sendTestBolus(units: Double, lockedCounter: Long, onResult: (Boolean, String) -> Unit) {
        val cmd = BolusCommand(units)                          // standard/immediate bolus
        val boIns = cmd.encode()
        aapsLogger.info(LTag.PUMP, "YpsoPump >>> SENDING BOLUS ${units}U @counter=${lockedCounter + 1} raw=${boIns.joinToString("") { "%02x".format(it) }}")
        writeOnceAt(CHAR_BOLUS_START_STOP, YpsoCrc.appendCrc(boIns), lockedCounter + 1) { status ->
            if (status != BluetoothGatt.GATT_SUCCESS) { onResult(false, "bolus REJECTED status=$status @${lockedCounter + 1} — check pump, likely not delivered"); return@writeOnceAt }
            persistWriteCounter()
            aapsLogger.info(LTag.PUMP, "YpsoPump >>> BOLUS ACCEPTED @${lockedCounter + 1}")
            readBolusStatus { st ->
                pumpState.lastConnectionTime = System.currentTimeMillis()
                onResult(true, "bolus accepted ${units}U; bolusStatus=${st?.bolusStatusCode} injected=${st?.deliveredUnits}U total=${st?.totalProgrammedUnits}U")
            }
        }
    }

    /**
     * Cancel a running bolus: canary-lock the write counter (same benign event-index gate as the bolus),
     * then write the all-zero START_STOP payload EXACTLY ONCE. Idempotent-ish (cancelling an already-finished
     * bolus is harmless), so — unlike delivery — a dropped ack here is not dangerous. [onResult]=(sent, msg).
     */
    fun cancelBolus(seedW: Long, extended: Boolean, onResult: (Boolean, String) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(false, "not connected"); return }
        val canary = glbEncode(0)
        val candidates = listOf(seedW + 1, seedW, seedW + 2)
        fun tryCanary(i: Int) {
            if (i >= candidates.size) { onResult(false, "cancel canary failed (tried $candidates)"); return }
            val c = candidates[i]
            writeOnceAt(CHAR_EVENT_INDEX, canary, c) { status ->
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        persistWriteCounter()
                        writeOnceAt(CHAR_BOLUS_START_STOP, YpsoCrc.appendCrc(BolusCommand.cancelPayload(extended)), c + 1) { st ->
                            if (st == BluetoothGatt.GATT_SUCCESS) { persistWriteCounter(); onResult(true, "bolus cancel sent @${c + 1}") }
                            else onResult(false, "cancel write status=$st @${c + 1}")
                        }
                    }
                    ERR_WRITE_REJECTED         -> tryCanary(i + 1)
                    else                       -> onResult(false, "cancel canary status=$status")
                }
            }
        }
        tryCanary(0)
    }

    /**
     * SAFE TBR (production + test) via the same canary as the bolus: lock the write counter on the BENIGN
     * event-index char, then write the TBR command EXACTLY ONCE at the confirmed next counter. Aborts
     * with no TBR write if the canary can't be confirmed. [percent] 0=suspend, 100=normal; [durationMinutes]
     * MUST be a 15-min step (15/30/…). Counter persisted after each accepted write. [onResult]=(accepted,msg).
     */
    fun testTbrCanary(percent: Int, durationMinutes: Int, seedW: Long, onResult: (Boolean, String) -> Unit) {
        if (!isConnected || bluetoothGatt == null) { onResult(false, "not connected"); return }
        val canary = glbEncode(0)                              // select event index 0 — zero therapy (GLB, no CRC)
        // Scan the counter FORWARD from seedW+1 to self-heal a dropped-ack desync (see testBolusCanary /
        // ERR_COUNTER_BEHIND). Benign zero-therapy writes; the TBR is still sent ONCE at the confirmed counter.
        val candidates = (1..COUNTER_RESYNC_SCAN).map { seedW + it }
        fun tryCanary(i: Int) {
            if (i >= candidates.size) { onResult(false, "canary failed (scanned +1..+$COUNTER_RESYNC_SCAN from $seedW) — NO TBR sent"); return }
            val c = candidates[i]
            aapsLogger.info(LTag.PUMP, "YpsoPump TBR canary index-write @counter=$c")
            writeOnceAt(CHAR_EVENT_INDEX, canary, c) { status ->
                when (status) {
                    BluetoothGatt.GATT_SUCCESS -> {
                        persistWriteCounter()
                        if (i > 0) aapsLogger.warn(LTag.PUMP, "YpsoPump counter was BEHIND by $i (dropped ack) — resynced to $c")
                        aapsLogger.info(LTag.PUMP, "YpsoPump TBR CANARY ACCEPTED @$c — counter locked; TBR will be @${c + 1}")
                        sendTestTbr(percent, durationMinutes, c, onResult)
                    }
                    ERR_WRITE_REJECTED, ERR_COUNTER_BEHIND -> tryCanary(i + 1)   // 0x8A/0x8B: counter off → scan forward
                    else                       -> onResult(false, "TBR canary write status=$status — ABORT, NO TBR")
                }
            }
        }
        // Read the event count once before the canary so the TBR path is read-before-write CONSISTENT with
        // the bolus path (deliverBolus -> establishCounter reads EVENT_COUNT first). Ypsomed doc 18 advises a
        // pump read before writing after (re)connect; this makes the behaviour uniform and is a harmless
        // benign read (no therapy, does not touch the write counter). NOTE: this is defensive, not the cure
        // for app-error 0x8B (139) seen 2026-07-01 — that was the write counter being BEHIND the pump (the
        // check is forward-gap tolerant, so 0x8B = counter too low). Recovery for 0x8B is to re-seed the
        // persisted writeCounter WELL ABOVE the pump's current value (a forward jump), not a code change.
        readMultiframe(CHAR_EVENT_COUNT) { fc ->
            val count = runCatching { glbFind(sessionCrypto.decrypt(fc)) }.getOrNull()
            aapsLogger.info(LTag.PUMP, "YpsoPump TBR prime-read event count=$count (readCounter=${sessionCrypto.readCounter}) — session primed, sending canary")
            tryCanary(0)
        }
    }

    private fun sendTestTbr(percent: Int, durationMinutes: Int, lockedCounter: Long, onResult: (Boolean, String) -> Unit) {
        fun writeTbrAt(pct: Int, dur: Int, counter: Long, label: String, cb: (Int) -> Unit) {
            val payload = TbrCommand(pct, dur).encode()          // complement-protected fields — NO CRC (unlike bolus)
            aapsLogger.info(LTag.PUMP, "YpsoPump >>> $label TBR ${pct}% for ${dur}min @counter=$counter raw=${payload.joinToString("") { "%02x".format(it) }}")
            writeOnceAt(CHAR_TBR_START_STOP, payload, counter, cb)
        }
        fun finish(c: Long, note: String) {
            persistWriteCounter()
            aapsLogger.info(LTag.PUMP, "YpsoPump >>> TBR ACCEPTED @$c$note")
            readStatus {
                pumpState.lastConnectionTime = System.currentTimeMillis()
                onResult(true, "TBR accepted ${percent}% ${durationMinutes}min$note; pump activeTbrPercent=${pumpState.activeTbrPercent}")
            }
        }
        // Try to START the requested TBR directly.
        writeTbrAt(percent, durationMinutes, lockedCounter + 1, "SENDING") { status ->
            when {
                status == BluetoothGatt.GATT_SUCCESS -> finish(lockedCounter + 1, "")
                // 0x86 = a TBR is already active; the pump won't START a new one until the current is STOPped.
                // Cancel it (100%/0 — the STOP; can never itself be blocked) then re-send. The rejected write
                // consumes one counter on the pump, so cancel/re-send go at +2/+3 (all forward-gap safe).
                status == ERR_TBR_ACTIVE && percent != 100 -> {
                    aapsLogger.warn(LTag.PUMP, "YpsoPump TBR rejected 0x86 (active TBR blocks a new one) — cancelling prior TBR then re-sending")
                    writeTbrAt(100, 0, lockedCounter + 2, "CANCEL-PRIOR") { cancelStatus ->
                        if (cancelStatus != BluetoothGatt.GATT_SUCCESS) { onResult(false, "TBR pre-cancel rejected status=$cancelStatus @${lockedCounter + 2} — NO TBR"); return@writeTbrAt }
                        persistWriteCounter()
                        writeTbrAt(percent, durationMinutes, lockedCounter + 3, "RE-SENDING") { retry ->
                            if (retry != BluetoothGatt.GATT_SUCCESS) { onResult(false, "TBR re-send rejected status=$retry @${lockedCounter + 3} — NO TBR"); return@writeTbrAt }
                            finish(lockedCounter + 3, " (after cancelling prior TBR)")
                        }
                    }
                }
                else -> onResult(false, "TBR REJECTED status=$status @${lockedCounter + 1}")
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
                // Authenticated. Before reporting CONNECTED, subscribe to the control-notification char:
                // the pump GATES control writes on this subscription (see CHAR_CTRL_NOTIFY). CONNECTED is
                // set once the CCCD write completes (onDescriptorWrite).
                aapsLogger.info(LTag.PUMP, "YpsoPump authenticated; enabling control notifications")
                enableCtrlNotify(g)
            } else complete(null, status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.characteristic?.uuid == CHAR_CTRL_NOTIFY) {
                if (status != BluetoothGatt.GATT_SUCCESS)
                    aapsLogger.warn(LTag.PUMP, "YpsoPump CTRL_NOTIFY CCCD write failed ($status) — proceeding, writes may be rejected")
                else
                    aapsLogger.info(LTag.PUMP, "YpsoPump CTRL_NOTIFY subscription active")
                markConnected()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            aapsLogger.debug(LTag.PUMP, "YpsoPump notify ${ch.uuid}: ${value.joinToString("") { "%02x".format(it) }}")
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            complete(value, status)
    }

    /**
     * Enable the control-notification subscription (write CCCD 0x0001 to [CHAR_CTRL_NOTIFY]) — the
     * pump's write-handshake precondition. Defensive: if the char/CCCD is somehow absent we still go
     * CONNECTED so the (notification-independent) read path keeps working.
     */
    @SuppressLint("MissingPermission")
    private fun enableCtrlNotify(g: BluetoothGatt) {
        val ch = findChar(g, CHAR_CTRL_NOTIFY)
        val cccd = ch?.getDescriptor(YpsoPumpConst.CCCD_UUID)
        if (ch == null || cccd == null) {
            aapsLogger.warn(LTag.PUMP, "YpsoPump CTRL_NOTIFY char/CCCD not found — proceeding without it (writes may be rejected)")
            markConnected(); return
        }
        g.setCharacteristicNotification(ch, true)
        val rc = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        aapsLogger.info(LTag.PUMP, "YpsoPump CTRL_NOTIFY CCCD write dispatched (rc=$rc)")
        // CONNECTED is set in onDescriptorWrite once the write completes.
    }

    private fun markConnected() {
        pumpState.connectionState = ConnectionState.CONNECTED
        aapsLogger.info(LTag.PUMP, "YpsoPump ready (authenticated, control notifications enabled)")
    }
}

/**
 * A parsed pump event-history entry (17 bytes, tech-doc §10.6). For fast-bolus events (types 1/2/3)
 * [v1] is the units in hundredths (delivered for completed/cancelled; requested for started) and, for
 * a cancelled bolus, [v2] is the requested amount. [timestamp] is pump-clock Unix seconds.
 */
data class YpsoHistoryEntry(
    val timestamp: Long,
    val eventType: Int,
    val v1: Int,
    val v2: Int,
    val v3: Int,
    val sequence: Long,
    val index: Int
) {
    /** Units in v1, converted from hundredths. */
    val v1Units: Double get() = v1 / 100.0
    /** Units in v2 (requested, for a cancelled bolus), converted from hundredths. */
    val v2Units: Double get() = v2 / 100.0
}

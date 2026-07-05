package app.aaps.pump.ypsopump

import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * AndroidAPS pump plugin for the Ypsomed YpsoPump.
 *
 * Read-only milestone: exposes connection state, reservoir level and battery from [YpsoPumpState]
 * (populated by the BLE layer). All dosing operations return "not implemented" — deliberately
 * stubbed until the write/dosing path is finished and safety-validated.
 */
@Singleton
class YpsoPumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    preferences: Preferences,
    commandQueue: CommandQueue,
    private val pumpState: YpsoPumpState,
    private val bleManager: YpsoBleManager,
    private val pumpSync: PumpSync,
    private val dateUtil: DateUtil,
    private val rxBus: RxBus,
    private val profileFunction: ProfileFunction,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .pluginIcon(app.aaps.core.ui.R.drawable.ic_generic_icon)
        .pluginName(R.string.ypsopump_name)
        .shortName(R.string.ypsopump_name_short)
        .preferencesId(PluginDescription.PREFERENCE_NONE)
        .description(R.string.ypsopump_description),
    ownPreferences = emptyList(),
    aapsLogger, rh, preferences, commandQueue
), Pump {

    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.YPSOPUMP)

    private fun notImplemented(): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment("YpsoPump: not implemented yet")

    // ---- state (read-only) ----
    override fun isInitialized(): Boolean = pumpState.lastConnectionTime > 0L
    override fun isSuspended(): Boolean = pumpState.isSuspended
    override fun isBusy(): Boolean = false
    override fun isConnected(): Boolean = pumpState.isConnected
    override fun isConnecting(): Boolean = pumpState.connectionState == ConnectionState.CONNECTING
    override fun isHandshakeInProgress(): Boolean = false

    private var writeValidationDone = false
    private var testBolusDone = false
    private var testTbrDone = false
    private var bolusStatusReadDone = false

    /** Session key / pump MAC resolved at runtime: persisted prefs win over the build const (see YpsoBleManager). */
    private fun resolvedKey(): String = bleManager.resolveSharedKey(YpsoPumpConst.CAPTURED_KEY_HEX)
    private fun resolvedMac(): String = bleManager.resolvePumpMac(YpsoPumpConst.PUMP_MAC)
    /** Both the session key and the pump MAC are per-user; without either we can't connect. */
    private fun configured(): Boolean = resolvedKey().isNotEmpty() && resolvedMac().isNotEmpty()

    private fun seedAndConnect() {
        bleManager.setSharedKey(resolvedKey())
        // Always seed counters so the AAPS-owned PERSISTED write counter is loaded even when no build-time seed
        // is set (setCounters keeps the higher of persisted/seed). Reboot counter from prefs if present.
        bleManager.setCounters(YpsoPumpConst.CAPTURED_WRITE_COUNTER, bleManager.resolveRebootCounter(YpsoPumpConst.CAPTURED_REBOOT_COUNTER))
        bleManager.connect(resolvedMac())
    }

    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "connect: $reason")
        if (!configured()) {
            aapsLogger.info(LTag.PUMP, "YpsoPump: session key and/or pump MAC not set (prefs ypso_shared_key / ypso_pump_mac or build consts) — skipping connect")
            return
        }
        seedAndConnect()
    }

    override fun disconnect(reason: String) { aapsLogger.debug(LTag.PUMP, "disconnect: $reason"); bleManager.disconnect() }
    override fun stopConnecting() { bleManager.disconnect() }

    override fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "getPumpStatus: $reason")
        if (!configured()) {
            aapsLogger.info(LTag.PUMP, "YpsoPump: session key and/or pump MAC not set — skipping read")
            return
        }
        if (!bleManager.isConnected) { seedAndConnect(); return }
        // These test ops BLOCK the queue-worker thread until done — otherwise AAPS sees the command as
        // finished and disconnects (5s idle) mid-write. The GATT callbacks run on the BLE binder
        // thread, so blocking here is safe. Real dosing (deliverTreatment) must block the same way.
        when {
            // SAFETY-CRITICAL: deliver one real bolus via the canary-gated safe path (no scan, no
            // auto-sync; aborts before the bolus char if the seeded write counter is wrong).
            YpsoPumpConst.RUN_TEST_BOLUS && !testBolusDone -> {
                testBolusDone = true
                val latch = java.util.concurrent.CountDownLatch(1)
                bleManager.testBolusCanary(YpsoPumpConst.TEST_BOLUS_UNITS, YpsoPumpConst.CAPTURED_WRITE_COUNTER) { _, r ->
                    aapsLogger.info(LTag.PUMP, "YpsoPump TEST-BOLUS: $r"); latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
            }
            // Set ONE TBR via the canary-gated safe path (0% = suspend basal, reduces insulin).
            YpsoPumpConst.RUN_TEST_TBR && !testTbrDone -> {
                testTbrDone = true
                val latch = java.util.concurrent.CountDownLatch(1)
                bleManager.testTbrCanary(YpsoPumpConst.TEST_TBR_PERCENT, YpsoPumpConst.TEST_TBR_DURATION_MIN, YpsoPumpConst.CAPTURED_WRITE_COUNTER) { _, r ->
                    aapsLogger.info(LTag.PUMP, "YpsoPump TEST-TBR: $r"); latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.MINUTES)
            }
            // READ-ONLY diagnostic: event-count (single-frame key check) -> system status -> bolus
            // status. No writes — safe mid-bolus. Per-frame logging shows exactly what the pump returns.
            YpsoPumpConst.RUN_READ_BOLUS_STATUS && !bolusStatusReadDone -> {
                bolusStatusReadDone = true
                val latch = java.util.concurrent.CountDownLatch(1)
                // STRICTLY chained — the pump's EXTREAD cursor is shared, so multi-frame reads must
                // never overlap (concurrent reads interleave EXTREAD frames and corrupt both).
                bleManager.readEventCount {
                    bleManager.readStatus {
                        bleManager.readBolusStatus { st ->
                            aapsLogger.info(
                                LTag.PUMP,
                                "YpsoPump BOLUS-STATUS: state=${st?.bolusStatusCode} injected=${st?.deliveredUnits}U total=${st?.totalProgrammedUnits}U"
                            )
                            latch.countDown()
                        }
                    }
                }
                latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
            }
            // ZERO-THERAPY write-transport validation (history index write + entry read).
            YpsoPumpConst.RUN_WRITE_VALIDATION && YpsoPumpConst.CAPTURED_WRITE_COUNTER >= 0 && !writeValidationDone -> {
                writeValidationDone = true
                val latch = java.util.concurrent.CountDownLatch(1)
                bleManager.validateWriteTransport { r ->
                    aapsLogger.info(LTag.PUMP, "YpsoPump WRITE-VALIDATION: $r"); latch.countDown()
                }
                latch.await(20, java.util.concurrent.TimeUnit.MINUTES)   // counter discovery can take minutes
            }

            else                                            -> bleManager.readStatus { reconcileSuspendTbr() }
        }
    }

    override val lastDataTime: Long get() = pumpState.lastConnectionTime
    override val lastBolusTime: Long? get() = pumpSync.expectedPumpState().bolus?.timestamp
    override val lastBolusAmount: Double? get() = pumpSync.expectedPumpState().bolus?.amount
    // Pump basal profile mirrors the AAPS profile (setNewBasalProfile is a no-op accept), and our status
    // read does not decode the active basal rate — so derive base basal from the active AAPS profile.
    // Must be > 0 or LoopPlugin.invoke() silently returns (if (pump.baseBasalRate < 0.01) return).
    override val baseBasalRate: Double get() = profileFunction.getProfile()?.getBasal() ?: 0.0
    override val reservoirLevel: Double get() = pumpState.reservoirUnits
    override val batteryLevel: Int? get() = pumpState.batteryPercent

    // ---- dosing (wired to the proven canary-gated BLE writes; AAPS owns the write counter) ----

    /** Block the queue-worker thread until the pump is connected, connecting if needed. */
    private fun ensureConnected(timeoutMs: Long = 40_000): Boolean {
        if (bleManager.isConnected) return true
        if (!configured()) return false
        seedAndConnect()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!bleManager.isConnected && System.currentTimeMillis() < deadline) Thread.sleep(250)
        return bleManager.isConnected
    }

    /** YpsoPump TBR duration must be a 15-minute step (confirmed on-pump: 3-min was rejected 0x82). */
    private fun round15(minutes: Int): Int = (Math.round(minutes / 15.0).toInt() * 15).coerceAtLeast(15)

    private fun fail(msg: String): PumpEnactResult =
        pumpEnactResultProvider.get().success(false).enacted(false).comment("YpsoPump: $msg")

    override fun setNewBasalProfile(profile: Profile): PumpEnactResult =
        // The YpsoPump's basal profile is programmed ON THE PUMP (mylife / pump UI); this driver does not
        // write it. The loop steers with percent TBRs relative to the pump's basal, so the pump's programmed
        // basal MUST match this AAPS profile — the user keeps them in sync. Report success accordingly.
        pumpEnactResultProvider.get().success(true).enacted(true).comment("YpsoPump: basal profile is programmed on the pump")

    override fun isThisProfileSet(profile: Profile): Boolean = true

    // Bolus delivery is CONFIRM-BY-READ: we never trust the write-accept callback alone (a dropped BLE ack
    // can mean the pump ALREADY delivered — the 2026-07-05 IOB-desync incident). After the START write we
    // poll the pump's own bolus status to the true delivered amount, drive the progress dialog from it, and
    // record THAT. See report + [[ypsopump-bolus-hang]].
    private val bolusConfirmTimeoutMs = 5 * 60 * 1000L
    private val bolusPollFastMs = 500L
    private val bolusPollSlowMs = 2000L
    private val bolusStepU = 0.05
    @Volatile private var bolusCancelRequested = false

    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        val requested = detailedBolusInfo.insulin
        if (requested <= 0.0) return fail("bolus <= 0")
        if (!ensureConnected()) return fail("not connected")
        bolusCancelRequested = false

        // Baseline (prior-bolus 'injected' the pump still reports) — logged so validation can confirm whether
        // the pump RESETS deliveredUnits per bolus. Attribution only trusts values AFTER status goes
        // 'delivering', so a lingering prior reading alone can never be mistaken for this dose.
        val baseInjected = readBolusStatusBlocking()?.deliveredUnits ?: 0.0

        // 1) START via the canary-gated write. 'started' is only the START-command ACK — droppable while the
        //    pump still delivers — so it is NEVER used to decide what to record. The pump status is truth.
        var started = false; var startMsg = ""
        val startLatch = java.util.concurrent.CountDownLatch(1)
        bleManager.testBolusCanary(requested, bleManager.writeCounter) { ok, m -> started = ok; startMsg = m; startLatch.countDown() }
        startLatch.await(2, java.util.concurrent.TimeUnit.MINUTES)

        // 2) CONFIRM-BY-READ: poll the pump's status until delivery finishes (or timeout / cancel / disconnect),
        //    tracking the actual delivered units and driving the progress bar (fixes the stuck-at-0%).
        var sawDelivering = false
        var delivered = 0.0
        var total = requested
        var pollMs = bolusPollFastMs
        val deadline = dateUtil.now() + bolusConfirmTimeoutMs
        while (dateUtil.now() < deadline) {
            val st = readBolusStatusBlocking()
            if (st != null) {
                if (st.isDelivering) sawDelivering = true
                if (sawDelivering) delivered = max(delivered, st.deliveredUnits)
                if (st.totalProgrammedUnits > 0.0) total = st.totalProgrammedUnits
                val pct = if (total > 0.0) ((delivered / total) * 100).toInt().coerceIn(0, 99) else 0
                rxBus.send(EventOverviewBolusProgress(rh, percent = pct, id = detailedBolusInfo.id))
                aapsLogger.info(LTag.PUMP, "YpsoPump bolus poll: status=${st.bolusStatusCode} delivering=${st.isDelivering} injected=${st.deliveredUnits} total=${st.totalProgrammedUnits} | base=$baseInjected saw=$sawDelivering tracked=$delivered req=$requested")
                if (sawDelivering && !st.isDelivering) break                 // our bolus completed
            } else if (sawDelivering && !bleManager.isConnected) {
                aapsLogger.warn(LTag.PUMP, "YpsoPump bolus: lost connection mid-delivery; recording confirmed $delivered U"); break
            }
            if (bolusCancelRequested) {
                val cl = java.util.concurrent.CountDownLatch(1)
                bleManager.cancelBolus(bleManager.writeCounter, extended = false) { _, cm -> aapsLogger.info(LTag.PUMP, "YpsoPump bolus cancel: $cm"); cl.countDown() }
                cl.await(30, java.util.concurrent.TimeUnit.SECONDS)
                readBolusStatusBlocking()?.let { if (it.isDelivering || sawDelivering) delivered = max(delivered, it.deliveredUnits) }
                aapsLogger.info(LTag.PUMP, "YpsoPump bolus CANCELLED by user; delivered so far=$delivered")
                break
            }
            Thread.sleep(pollMs)
            pollMs = min(pollMs * 3 / 2, bolusPollSlowMs)                    // ramp 0.5s -> 2s
        }
        rxBus.send(EventOverviewBolusProgress(rh, percent = 100, id = detailedBolusInfo.id))

        // 3) RECORD the pump's TRUTH — never gated on the droppable start ack.
        return when {
            sawDelivering && delivered > 0.0 -> {                            // confirmed (possibly partial)
                syncBolus(detailedBolusInfo, delivered)
                val partial = delivered + bolusStepU < requested
                pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(delivered)
                    .comment("YpsoPump: delivered %.2fU%s".format(delivered, if (partial) " (PARTIAL of %.2f)".format(requested) else ""))
            }
            started                          -> {                            // ack OK but read never confirmed:
                // FAIL SAFE for the loop — record the requested dose so IOB is if anything OVER-stated (loop
                // then UNDER-doses) rather than the dangerous under-count that over-doses. Warn to verify.
                syncBolus(detailedBolusInfo, requested)
                pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(requested)
                    .comment("YpsoPump: UNCONFIRMED — recorded %.2fU, VERIFY on pump".format(requested))
            }
            else                             -> fail("bolus not delivered (start failed, none confirmed): $startMsg")
        }
    }

    private fun syncBolus(info: DetailedBolusInfo, amount: Double) {
        pumpSync.syncBolusWithPumpId(
            timestamp = info.timestamp, amount = amount, type = info.bolusType,
            pumpId = dateUtil.now(), pumpType = PumpType.YPSOPUMP, pumpSerial = serialNumber()
        )
    }

    /** One bolus-status read, blocking the caller (queue-worker) thread until the BLE callback returns. */
    private fun readBolusStatusBlocking(timeoutMs: Long = 8000): app.aaps.pump.ypsopump.comm.commands.BolusCommand? {
        var out: app.aaps.pump.ypsopump.comm.commands.BolusCommand? = null
        val l = java.util.concurrent.CountDownLatch(1)
        bleManager.readBolusStatus { st -> out = st; l.countDown() }
        l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return out
    }

    // Real cancel now that confirm-by-read tracks the partial: the Stop button flags a cancel; the poll loop
    // sends the pump's all-zero START_STOP cancel and records what actually went in.
    override fun stopBolusDelivering() { bolusCancelRequested = true }

    // --- pump-side suspend reflection ---
    // A loop/app suspend already zeroes basal via a recorded 0% TBR. But a suspend initiated ON THE PUMP
    // (the pump's "Stop", or an occlusion/empty auto-suspend) is only learned from a status read: the BLE
    // layer sets pumpState.isSuspended, but nothing tells AAPS the basal stopped — so the graph keeps drawing
    // and IOB keeps ACCRUING the profile basal that isn't being delivered (over-stated IOB). Mirror the pump's
    // real state as a 0-rate PUMP_SUSPEND temp basal (Medtrum/Combo pattern) so the graph + IOB read 0 while
    // stopped. Rolling window refreshed each status read; ended on resume. Called from readStatus's onDone.
    private val suspendTbrWindowMin = 30L
    private var suspendTbrStartMs = 0L

    private fun reconcileSuspendTbr() {
        val now = dateUtil.now()
        when {
            pumpState.isSuspended && suspendTbrStartMs == 0L -> {                 // pump just stopped
                suspendTbrStartMs = now
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = now, rate = 0.0, duration = T.mins(suspendTbrWindowMin).msecs(),
                    isAbsolute = true, type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = now, pumpType = PumpType.YPSOPUMP, pumpSerial = serialNumber()
                )
                aapsLogger.info(LTag.PUMP, "YpsoPump: pump suspended -> recorded PUMP_SUSPEND 0-TBR")
            }
            pumpState.isSuspended && suspendTbrStartMs != 0L -> {                 // still stopped: extend window
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = suspendTbrStartMs, rate = 0.0,
                    duration = (now - suspendTbrStartMs) + T.mins(suspendTbrWindowMin).msecs(),
                    isAbsolute = true, type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = suspendTbrStartMs, pumpType = PumpType.YPSOPUMP, pumpSerial = serialNumber()
                )
            }
            !pumpState.isSuspended && suspendTbrStartMs != 0L -> {                // resumed: end it
                pumpSync.syncStopTemporaryBasalWithPumpId(now, now, PumpType.YPSOPUMP, serialNumber())
                aapsLogger.info(LTag.PUMP, "YpsoPump: pump resumed -> ended PUMP_SUSPEND 0-TBR")
                suspendTbrStartMs = 0L
            }
        }
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        val dur = round15(durationInMinutes)
        if (!ensureConnected()) return fail("not connected")
        var accepted = false; var msg = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        bleManager.testTbrCanary(percent, dur, bleManager.writeCounter) { ok, m -> accepted = ok; msg = m; latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.MINUTES)
        if (!accepted) return fail(msg)
        pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = dateUtil.now(),
            rate = percent.toDouble(),
            duration = T.mins(dur.toLong()).msecs(),
            isAbsolute = false,
            type = tbrType,
            pumpId = dateUtil.now(),
            pumpType = PumpType.YPSOPUMP,
            pumpSerial = serialNumber()
        )
        val result = pumpEnactResultProvider.get().success(true).enacted(true).comment("YpsoPump: $msg")
        result.isPercent = true; result.percent = percent; result.duration = dur
        return result
    }

    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        val base = profile.getBasal()
        val percent = if (base > 0) Math.round(absoluteRate / base * 100.0).toInt() else 100
        return setTempBasalPercent(percent, durationInMinutes, profile, enforceNew, tbrType)
    }

    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        // No dedicated stop-TBR command RE'd yet; setting 100% for a 15-min step overrides any active
        // override back to the normal (pump-programmed) basal.
        if (!ensureConnected()) return fail("not connected")
        var accepted = false; var msg = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        bleManager.testTbrCanary(100, 15, bleManager.writeCounter) { ok, m -> accepted = ok; msg = m; latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.MINUTES)
        if (!accepted) return fail(msg)
        pumpSync.syncStopTemporaryBasalWithPumpId(
            timestamp = dateUtil.now(),
            endPumpId = dateUtil.now(),
            pumpType = PumpType.YPSOPUMP,
            pumpSerial = serialNumber()
        )
        val result = pumpEnactResultProvider.get().success(true).enacted(true).comment("YpsoPump: cancelled (100%): $msg")
        result.isTempCancel = true
        return result
    }

    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = notImplemented()
    override fun cancelExtendedBolus(): PumpEnactResult = notImplemented()
    override fun loadTDDs(): PumpEnactResult = notImplemented()

    // ---- identity ----
    override fun manufacturer(): ManufacturerType = ManufacturerType.Ypsomed
    override fun model(): PumpType = PumpType.YPSOPUMP
    override fun serialNumber(): String = pumpState.serialNumber
    override val isFakingTempsByExtendedBoluses: Boolean = false
    override fun canHandleDST(): Boolean = false
    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {}
    override fun pumpSpecificShortStatus(veryShort: Boolean): String =
        "Reservoir ${pumpState.reservoirUnits}U Battery ${pumpState.batteryPercent}%"
}

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
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.ble.YpsoHistoryEntry
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
    private val uiInteraction: UiInteraction,
    private val pumpEnactResultProvider: Provider<PumpEnactResult>
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(YpsoPumpFragment::class.java.name)
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
    // An empty cartridge is a suspended pump as far as the loop is concerned — this is what puts AAPS
    // into SUSPENDED_BY_PUMP (LoopPlugin) instead of letting it keep issuing doses into an empty pump.
    override fun isSuspended(): Boolean = pumpState.isSuspended || reservoirEmpty()
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

            else                                            -> bleManager.readStatus { onStatusRead() }
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

        // PRE-FLIGHT on a FRESH read, not on whatever the last status happened to say. A pump that is
        // stopped (user Stop, occlusion, or an empty-reservoir auto-stop) accepts nothing, so without
        // this the bolus used to sit at 0% for the full five-minute confirm window before failing.
        // Refusing here costs one status read and turns that into an immediate, explainable failure.
        readStatusBlocking()
        if (pumpState.isSuspended) return fail(SUSPENDED_MESSAGE)
        if (reservoirEmpty()) return fail(EMPTY_MESSAGE)

        // Baseline (prior-bolus 'injected' the pump still reports) — logged so validation can confirm whether
        // the pump RESETS deliveredUnits per bolus. Attribution only trusts values AFTER status goes
        // 'delivering', so a lingering prior reading alone can never be mistaken for this dose.
        val baseInjected = readBolusStatusBlocking()?.deliveredUnits ?: 0.0

        // 1) START via the canary-gated write. The ACK is droppable while the pump still delivers, so it is
        //    NEVER used to decide what to record — the pump status is truth. What the outcome DOES decide is
        //    how hard to look: only [BolusStart.NOT_SENT] proves the bolus characteristic was never written.
        var startOutcome = YpsoBleManager.BolusStart.NOT_SENT; var startMsg = "no response from pump"
        val startLatch = java.util.concurrent.CountDownLatch(1)
        bleManager.startBolus(requested, bleManager.writeCounter) { o, m -> startOutcome = o; startMsg = m; startLatch.countDown() }
        if (!startLatch.await(2, java.util.concurrent.TimeUnit.MINUTES)) {
            // No callback at all — the write may still have landed. Treat as uncertain, never as "no bolus".
            startOutcome = YpsoBleManager.BolusStart.UNCERTAIN
            startMsg = "no start response within 2 min — confirming against the pump"
        }
        val started = startOutcome == YpsoBleManager.BolusStart.SENT
        if (startOutcome == YpsoBleManager.BolusStart.NOT_SENT) {
            // Certain no-op: the bolus char was never written, so there is nothing to confirm and nothing
            // to record. Return NOW rather than polling a pump that isn't delivering.
            rxBus.send(EventOverviewBolusProgress(rh, percent = 100, id = detailedBolusInfo.id))
            aapsLogger.warn(LTag.PUMP, "YpsoPump bolus NOT SENT: $startMsg")
            return fail(if (pumpState.isSuspended) SUSPENDED_MESSAGE else "bolus not sent: $startMsg")
        }

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
                // A 'completed' frame is also proof the pump delivered — capture it so a bolus that races
                // 1→4 between polls still counts as seen (status decode fixed in BolusCommand.isDelivering).
                if (st.isDelivering || st.isCompleted) sawDelivering = true
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

        // 2b) RECONCILE against the pump's OWN bolus history when the live poll did NOT confirm the full
        //     requested dose. A dropped write-ack / BLE blip can leave `delivered` short of (or at zero,
        //     while) what the pump actually pushed — the exact failure that let a 12.4U bolus record as
        //     nothing. The pump logs every fast bolus (completed/cancelled) with the delivered units; that
        //     is ground truth. ADDITIVE ONLY: we adopt the pump's figure solely when it exceeds what we saw
        //     and is plausibly this bolus (<= requested + one step). A failed/implausible read changes
        //     nothing, so this can only rescue an under-count, never invent or reduce a dose.
        if (delivered + bolusStepU < requested) {
            val hist = readLastFastBolusEventBlocking()
            if (hist != null && hist.v1Units > delivered && hist.v1Units <= requested + bolusStepU) {
                aapsLogger.warn(
                    LTag.PUMP,
                    "YpsoPump bolus reconcile: pump history type=${hist.eventType} delivered=${hist.v1Units}U (poll saw $delivered U of $requested U) — recording history; dropped-confirm rescued"
                )
                delivered = hist.v1Units
                sawDelivering = true
            } else {
                aapsLogger.info(LTag.PUMP, "YpsoPump bolus reconcile: no usable history (hist=${hist?.eventType}/${hist?.v1Units}); keeping polled $delivered U")
            }
        }

        // 2c) Nothing confirmed? Then find out WHY before deciding what to record. The pre-flight said the
        //     pump was running with insulin in it, and only a fresh read can tell us whether it stopped or
        //     ran dry during the delivery — which is exactly the case where banking the requested dose
        //     would invent IOB. This also raises the empty-reservoir alarm at the moment it happens.
        if (!(sawDelivering && delivered > 0.0)) readStatusBlocking()

        // 3) RECORD the pump's TRUTH — never gated on the droppable start ack.
        return when {
            sawDelivering && delivered > 0.0 -> {                            // confirmed (possibly partial)
                syncBolus(detailedBolusInfo, delivered)
                val partial = delivered + bolusStepU < requested
                pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(delivered)
                    .comment("YpsoPump: delivered %.2fU%s".format(delivered, if (partial) " (PARTIAL of %.2f)".format(requested) else ""))
            }
            // The pump told us it is not delivering. Recording the request here is what manufactured
            // phantom IOB the day the reservoir ran dry: every "unconfirmed" dose was banked as real
            // while nothing went in, and the loop then under-dosed against an IOB that did not exist.
            // A stopped or empty pump is not an ambiguous read — it is a known no-delivery state.
            pumpState.isSuspended || reservoirEmpty() -> {
                aapsLogger.error(LTag.PUMP, "YpsoPump bolus: pump ${if (pumpState.isSuspended) "stopped" else "reservoir empty"} and nothing confirmed — recording NOTHING (start=$startMsg)")
                notifyNoDelivery()
                fail(if (pumpState.isSuspended) SUSPENDED_MESSAGE else EMPTY_MESSAGE)
            }
            started                          -> {                            // ack OK but read never confirmed:
                // FAIL SAFE for the loop — record the requested dose so IOB is if anything OVER-stated (loop
                // then UNDER-doses) rather than the dangerous under-count that over-doses. Warn to verify.
                syncBolus(detailedBolusInfo, requested)
                uiInteraction.addNotification(
                    Notification.PUMP_SYNC_ERROR,
                    "Bolus of %.2f U could not be confirmed on the pump. It was recorded so IOB is not under-counted — check the pump's history and remove it from Recent insulin if it was not delivered.".format(requested),
                    Notification.URGENT
                )
                pumpEnactResultProvider.get().success(true).enacted(true).bolusDelivered(requested)
                    .comment("YpsoPump: UNCONFIRMED — recorded %.2fU, VERIFY on pump".format(requested))
            }
            else                             -> fail("bolus not delivered (start failed, none confirmed): $startMsg")
        }
    }

    private fun syncBolus(info: DetailedBolusInfo, amount: Double) {
        // Anchor the record to delivery-CONFIRMATION time (now), NOT the bolus start (info.timestamp). A big
        // bolus takes minutes to deliver, so by the time confirm-by-read finishes, info.timestamp can already
        // be older than AAPS's 1-minute freshness gate (see YpsoBleManager.connect serial seed) -> the sync
        // gets silently rejected and the delivered dose is lost. Recording at confirmation time is at most a
        // couple of minutes later than the true start (negligible for IOB) and can NEVER be dropped as stale.
        pumpSync.syncBolusWithPumpId(
            timestamp = dateUtil.now(), amount = amount, type = info.bolusType,
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

    /**
     * Blocking reconciliation read of the pump's last fast-bolus history event (multi-step: count read +
     * index write + value read — hence a longer timeout than a single status read). Null on any failure.
     */
    private fun readLastFastBolusEventBlocking(timeoutMs: Long = 15000): YpsoHistoryEntry? {
        var out: YpsoHistoryEntry? = null
        val l = java.util.concurrent.CountDownLatch(1)
        bleManager.readLastFastBolusEvent { e -> out = e; l.countDown() }
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

    /** Everything that must happen after a status read lands: mirror a pump-side stop, then check supplies. */
    private fun onStatusRead() {
        reconcileSuspendTbr()
        checkReservoir()
    }

    /**
     * Reservoir surveillance.
     *
     * The pump running dry is the one failure this app used to be completely silent about: the loop kept
     * commanding basal and boluses, the reservoir pill on Home *disappeared* at zero (it was only drawn
     * when `> 0`), and the first sign anything was wrong was the glucose curve. So: warn while there is
     * still time to act, alarm when there isn't.
     *
     * Gated on a status read having actually succeeded — [YpsoPumpState.reservoirUnits] is 0.0 before the
     * first read and after [YpsoPumpState.reset], and alarming on "not read yet" would train the alarm out.
     */
    private fun checkReservoir() {
        if (pumpState.lastStatusTime <= 0L) return
        val units = pumpState.reservoirUnits
        // Thresholds come from the app's OWN reservoir preferences (Overview → status lights), which
        // already exist, are already translated and are already on a settings screen. They were left
        // unread when the redesign dropped the status-lights row; this puts them back to work rather
        // than inventing a second set of numbers nobody can find.
        //
        // Only CRITICAL raises a notification. "Warning" is the level the status lights always meant —
        // a colour, not a nag — and it stays a colour, on the Home reservoir pill.
        val level = when {
            units <= RESERVOIR_EMPTY_UNITS                  -> ReservoirLevel.EMPTY
            units <= preferences.get(IntKey.OverviewResCritical) -> ReservoirLevel.LOW
            else                                            -> ReservoirLevel.OK
        }
        // Clear the other alerts only when the level actually MOVED. Raising is left unconditional: the
        // store de-dupes by id (so the alarm doesn't re-sound every read) but a user who swipes an empty
        // reservoir away and does nothing about it gets it back on the next read, which is the point.
        if (level != lastReservoirLevel) {
            if (level != ReservoirLevel.EMPTY) rxBus.send(EventDismissNotification(Notification.PUMP_RESERVOIR_EMPTY))
            if (level != ReservoirLevel.LOW) rxBus.send(EventDismissNotification(Notification.PUMP_RESERVOIR_LOW))
            lastReservoirLevel = level
        }
        when (level) {
            ReservoirLevel.EMPTY -> uiInteraction.addNotificationWithSound(
                Notification.PUMP_RESERVOIR_EMPTY,
                "Pump reservoir is EMPTY — no insulin is being delivered. Change the cartridge now.",
                Notification.URGENT,
                app.aaps.core.ui.R.raw.alarm
            )

            ReservoirLevel.LOW   -> uiInteraction.addNotification(
                Notification.PUMP_RESERVOIR_LOW,
                "Pump reservoir low: %.0f U left. Change the cartridge soon.".format(units),
                Notification.URGENT
            )

            ReservoirLevel.OK    -> Unit
        }
    }

    private enum class ReservoirLevel { OK, LOW, EMPTY }

    private var lastReservoirLevel = ReservoirLevel.OK

    /** True only on a fresh read — see [checkReservoir] for why "0.0" alone is not enough. */
    private fun reservoirEmpty(): Boolean = pumpState.lastStatusTime > 0L && pumpState.reservoirUnits <= RESERVOIR_EMPTY_UNITS

    /** One blocking status read, so a pre-flight check tests the pump's state now, not minutes ago. */
    private fun readStatusBlocking(timeoutMs: Long = 8000) {
        val l = java.util.concurrent.CountDownLatch(1)
        bleManager.readStatus { onStatusRead(); l.countDown() }
        l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Raise the alarm for a dose the pump could not take, so a refusal is never silent. */
    private fun notifyNoDelivery() {
        if (reservoirEmpty()) checkReservoir()
        else uiInteraction.addNotificationWithSound(
            Notification.PUMP_SUSPENDED, SUSPENDED_MESSAGE, Notification.URGENT, app.aaps.core.ui.R.raw.boluserror
        )
    }

    private fun reconcileSuspendTbr() {
        val now = dateUtil.now()
        // An EMPTY reservoir is a not-delivering state exactly like a stop, whether or not the pump has
        // already flagged itself stopped. Without this the basal IOB kept accruing against insulin that
        // was never pushed — half of the IOB error after the cartridge ran dry.
        val notDelivering = pumpState.isSuspended || reservoirEmpty()
        when {
            notDelivering && suspendTbrStartMs == 0L -> {                 // pump just stopped
                suspendTbrStartMs = now
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = now, rate = 0.0, duration = T.mins(suspendTbrWindowMin).msecs(),
                    isAbsolute = true, type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = now, pumpType = PumpType.YPSOPUMP, pumpSerial = serialNumber()
                )
                aapsLogger.info(LTag.PUMP, "YpsoPump: pump suspended -> recorded PUMP_SUSPEND 0-TBR")
            }
            notDelivering && suspendTbrStartMs != 0L -> {                 // still stopped: extend window
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = suspendTbrStartMs, rate = 0.0,
                    duration = (now - suspendTbrStartMs) + T.mins(suspendTbrWindowMin).msecs(),
                    isAbsolute = true, type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = suspendTbrStartMs, pumpType = PumpType.YPSOPUMP, pumpSerial = serialNumber()
                )
            }
            !notDelivering && suspendTbrStartMs != 0L -> {                // resumed: end it
                pumpSync.syncStopTemporaryBasalWithPumpId(now, now, PumpType.YPSOPUMP, serialNumber())
                aapsLogger.info(LTag.PUMP, "YpsoPump: pump resumed -> ended PUMP_SUSPEND 0-TBR")
                suspendTbrStartMs = 0L
            }
        }
    }

    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        val dur = round15(durationInMinutes)
        if (!ensureConnected()) return fail("not connected")
        // A stopped or empty pump delivers nothing, and recording a TBR against it would overwrite the
        // 0-rate PUMP_SUSPEND window [reconcileSuspendTbr] keeps — re-inflating IOB with insulin that
        // never left the cartridge. Refuse instead; the loop switches to SUSPENDED_BY_PUMP on its own.
        if (pumpState.isSuspended) return fail(SUSPENDED_MESSAGE)
        if (reservoirEmpty()) return fail(EMPTY_MESSAGE)
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

    companion object {

        /** The pump reports remaining insulin in centi-units, so a true empty reads as exactly 0. */
        private const val RESERVOIR_EMPTY_UNITS = 0.0

        const val SUSPENDED_MESSAGE =
            "Pump is stopped — it will not deliver insulin. Start it on the pump (Menu \u25b8 Run), then try again."

        const val EMPTY_MESSAGE =
            "Pump reservoir is empty — it cannot deliver insulin. Change the cartridge, then try again."
    }
}

package app.aaps.plugins.source

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.IDs
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.BgSource
import app.aaps.core.interfaces.source.SensorLifecycle
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.libre3.Libre3BleClient
import app.aaps.libre3.Libre3GlucoseRecord
import app.aaps.libre3.Libre3History
import app.aaps.libre3.Libre3LagOverride
import app.aaps.libre3.Libre3NfcActivation
import app.aaps.libre3.Libre3NfcV
import app.aaps.libre3.Libre3PatchStatus
import app.aaps.libre3.Libre3SecuritySession
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveIntPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.plugins.source.compose.Libre3SensorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Native Libre 3 / 3+ BG source — AAPS talking to the sensor directly, with no Juggluco and no
 * xDrip in the path. See report/libre3-native-plan.md.
 *
 * The protocol and transport live in `:libre3`, which has no AAPS dependencies and is unit
 * tested off-device. This class is only the AAPS-facing shell: credentials in, glucose out.
 *
 * WHY THIS EXISTS: the xDrip path costs ~7.5 min of filter group delay plus 4-in-5 decimation,
 * and the sensor's own record shows the retained value running up to 1.3 mmol/L behind
 * real-time during a fall. This reads `readingMgDl` — the sensor's real-time number — at the
 * full 1-minute rate.
 */
@Singleton
class Libre3SourcePlugin @Inject constructor(
    rh: ResourceHelper,
    aapsLogger: AAPSLogger,
    private val context: Context,
    private val persistenceLayer: PersistenceLayer,
    private val dateUtil: DateUtil,
    private val credentials: Libre3CredentialStore,
    private val preferences: Preferences,
    private val profileUtil: app.aaps.core.interfaces.profile.ProfileUtil
) : AbstractBgSourceWithSensorInsertLogPlugin(
    PluginDescription()
        .mainType(PluginType.BGSOURCE)
        .fragmentClass(Libre3SensorFragment::class.java.name)
        .pluginIcon(app.aaps.core.objects.R.drawable.ic_blooddrop_48)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .pluginName(R.string.source_libre3)
        .preferencesVisibleInSimpleMode(false)
        .description(R.string.description_source_libre3),
    aapsLogger, rh
), BgSource {

    /**
     * The sensor streams factory-calibrated mg/dL at 1-minute intervals, so the data is at
     * least as filterable as a native Dexcom feed. Reported true so SMB is not gated.
     */
    override fun advancedFilteringSupported(): Boolean = true

    override var sensorBatteryLevel = -1

    private var client: Libre3BleClient? = null

    /** Last accepted record, to drop the duplicate notifications the sensor sometimes repeats. */
    private var lastLifeCount = -1

    /** Highest history lifeCount already stored, so backfill only asks for the real gap. */
    private var lastHistoryLifeCount = 0

    /** One history request per connection; patch status arrives repeatedly. */
    private var historyRequested = false

    /** Sensor start already recorded as a SENSOR_CHANGE, so warm-up/expiry show and we don't re-insert. */
    private var sensorStartRecorded: Long? = null

    /** Manual-BG lag override: a finger-prick during a fast rise corrects the lagging sensor stream.
     *  Rebuilt from preferences in [onStart], so pref changes apply on the next plugin start. */
    private var lagOverride = Libre3LagOverride()

    private fun lagConfig() = Libre3LagOverride.Config(
        windowSize = preferences.get(IntKey.Libre3LagWindow),
        armThresholdMgdlPerMin = preferences.get(DoubleKey.Libre3LagArmRate),
        decayHalfLifeMin = preferences.get(DoubleKey.Libre3LagHalfLifeMin),
        maxDurationMin = preferences.get(DoubleKey.Libre3LagMaxDurationMin)
    )

    /** Latest sensor lifecycle state, for the UI to show warm-up/expiry honestly. */
    var patchStatus: Libre3PatchStatus? = null
        private set

    /** Live view for the Sensor screen. */
    private val _sensorState = MutableStateFlow(Libre3SensorState())
    val sensorState: StateFlow<Libre3SensorState> = _sensorState.asStateFlow()

    private var lastReadingAt: Long = 0L
    private var backfilled = 0

    private fun update(block: (Libre3SensorState) -> Libre3SensorState) {
        _sensorState.value = block(_sensorState.value)
    }

    /**
     * Record the sensor's activation as a SENSOR_CHANGE once, so the overview shows warm-up and a
     * real expiry countdown instead of treating a warming-up sensor's last stale reading as current.
     * No glucose is inserted during warm-up (readings are out-of-range sentinels dropped before the
     * DB), so this cannot ride along on insertCgmSourceData's sensor-start argument — it must be an
     * explicit therapy event. Deduped in the DB by timestamp and by [sensorStartRecorded] per session.
     */
    private fun recordSensorStart() {
        val startedMs = credentials.load()?.startedEpochMs ?: return
        if (sensorStartRecorded == startedMs) return
        sensorStartRecorded = startedMs
        persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
            therapyEvent = TE(
                timestamp = startedMs, type = TE.Type.SENSOR_CHANGE, duration = 0,
                note = null, enteredBy = "AndroidAPS-Libre3",
                glucose = null, glucoseType = null, glucoseUnit = GlucoseUnit.MGDL, ids = IDs()
            ),
            timestamp = startedMs, action = Action.CAREPORTAL, source = Sources.Libre3, note = null,
            listValues = listOf(ValueWithUnit.Timestamp(startedMs), ValueWithUnit.TEType(TE.Type.SENSOR_CHANGE))
        ).subscribe({ }, { aapsLogger.error(LTag.BGSOURCE, "Libre3: sensor-start record failed", it) })
    }

    /**
     * The authoritative lifecycle the Overview reads (warm-up / active / expired). Derived from the
     * credentials onboarding wrote immediately — real activation time, the sensor's own warm-up
     * window and its life — so it is correct the moment a sensor is applied, before any BLE reading
     * or SENSOR_CHANGE therapy event exists. Returns null when no sensor is configured.
     */
    override val sensorLifecycle: SensorLifecycle?
        get() {
            val c = credentials.load() ?: return null
            val elapsedMin = ((dateUtil.now() - c.startedEpochMs) / 60_000L).toInt()
            val lifeMin = c.lifeDays * 24 * 60
            return when {
                elapsedMin < 0               -> null
                elapsedMin < c.warmupMinutes -> SensorLifecycle.WarmingUp(
                    minutesRemaining = c.warmupMinutes - elapsedMin,
                    fractionElapsed = (elapsedMin.toFloat() / c.warmupMinutes).coerceIn(0f, 1f)
                )
                elapsedMin >= lifeMin        -> SensorLifecycle.Expired
                else                         -> {
                    val remMin = lifeMin - elapsedMin
                    val h = (remMin / 60).toLong()
                    SensorLifecycle.Active(
                        remainingHours = h,
                        fractionRemaining = remMin.toFloat() / lifeMin,
                        label = if (h >= 24) "${h / 24}d ${h % 24}h" else "${h}h"
                    )
                }
            }
        }

    /**
     * Recompute lifecycle from the credentials' activation time. Derived rather than stored, so
     * it stays right across restarts without any persistence of its own.
     */
    private fun lifecycleNow(): Libre3SensorState.Lifecycle {
        val c = credentials.load() ?: return Libre3SensorState.Lifecycle.NoSensor
        val elapsedMin = ((dateUtil.now() - c.startedEpochMs) / 60_000L).toInt()
        val lifeMin = c.lifeDays * 24 * 60
        return when {
            elapsedMin < 0            -> Libre3SensorState.Lifecycle.NoSensor
            elapsedMin < c.warmupMinutes ->
                Libre3SensorState.Lifecycle.WarmingUp(c.warmupMinutes - elapsedMin)
            elapsedMin >= lifeMin     -> Libre3SensorState.Lifecycle.Expired
            else                      -> {
                val remaining = lifeMin - elapsedMin
                val h = remaining / 60
                Libre3SensorState.Lifecycle.Active(
                    fractionRemaining = remaining.toFloat() / lifeMin,
                    remainingLabel = if (h >= 24) "${h / 24}d ${h % 24}h" else "${h}h"
                )
            }
        }
    }

    private val listener = object : Libre3BleClient.Listener {

        override fun onGlucoseRecord(plain: ByteArray) {
            val record = Libre3GlucoseRecord.parse(plain)
            if (record == null) {
                aapsLogger.error(LTag.BGSOURCE, "Libre3: unparseable ${plain.size}-byte record")
                return
            }
            if (!record.isValid) {
                // Outside Abbott's own 39..501 window. Never clamp a reading into range —
                // an out-of-range value means the sensor is not measuring, not that glucose
                // is at the limit.
                aapsLogger.debug(LTag.BGSOURCE, "Libre3: reading ${record.readingMgDl} out of range, dropped")
                return
            }
            if (record.lifeCount == lastLifeCount) return      // repeat of the same minute
            lastLifeCount = record.lifeCount
            val now = dateUtil.now()
            lastReadingAt = now
            // Feed the raw reading to the lag override; the value stored is corrected only while an
            // override is active (raw otherwise). The override keeps its slope on the RAW stream.
            val stored = lagOverride.onReading(now, record.readingMgDl)
            if (stored != record.readingMgDl)
                aapsLogger.info(LTag.BGSOURCE, "Libre3: lag override ${record.readingMgDl} -> $stored mg/dL")
            update {
                it.copy(
                    connection = Libre3SensorState.Connection.Connected,
                    lifecycle = lifecycleNow(),
                    lastReadingAgoMinutes = 0,
                    lastError = null
                )
            }

            val gv = GV(
                timestamp = now,
                value = stored.toDouble(),
                raw = null,
                noise = null,
                trendArrow = trendOf(record),
                sourceSensor = SourceSensor.LIBRE_3
            )
            persistenceLayer.insertCgmSourceData(Sources.Libre3, listOf(gv), emptyList(), null)
                .subscribe({ }, { aapsLogger.error(LTag.BGSOURCE, "Libre3: insert failed", it) })
        }

        /**
         * Gap backfill — the capability the Juggluco/xDrip path does not have at all. Readings
         * missed while out of range are recovered from the sensor's own retained history.
         *
         * Timestamps are reconstructed from `lifeCount` (minutes since sensor start) against the
         * stored activation time, NOT from arrival time: these are old readings and dating them
         * "now" would corrupt both the loop and the retrospective record.
         */
        override fun onHistory(entries: List<Libre3History.Entry>) {
            val startedMs = credentials.load()?.startedEpochMs ?: return
            val fresh = entries.filter { it.lifeCount > lastHistoryLifeCount }
            if (fresh.isEmpty()) return
            val gvs = fresh.map { e ->
                GV(
                    timestamp = startedMs + e.lifeCount * 60_000L,
                    value = e.mgDl.toDouble(),
                    raw = null,
                    noise = null,
                    trendArrow = TrendArrow.NONE,   // history carries no trend; do not invent one
                    sourceSensor = SourceSensor.LIBRE_3
                )
            }
            lastHistoryLifeCount = fresh.maxOf { it.lifeCount }
            backfilled += gvs.size
            update { it.copy(backfilledCount = backfilled) }
            aapsLogger.debug(LTag.BGSOURCE, "Libre3: backfilling ${gvs.size} readings")
            persistenceLayer.insertCgmSourceData(Sources.Libre3, gvs, emptyList(), null)
                .subscribe({ }, { aapsLogger.error(LTag.BGSOURCE, "Libre3: backfill insert failed", it) })
        }

        override fun onRssi(dbm: Int) = update { it.copy(signalDbm = dbm) }

        override fun onPatchStatus(status: Libre3PatchStatus) {
            patchStatus = status
            aapsLogger.debug(LTag.BGSOURCE, "Libre3: patch state=${status.patchState} life=${status.currentLifeCount}")
            recordSensorStart()
            // The data channel is demonstrably live now, so the control write will be accepted.
            // Only ask once per connection — patch status repeats.
            if (!historyRequested) {
                historyRequested = true
                aapsLogger.debug(LTag.BGSOURCE, "Libre3: requesting history from $lastHistoryLifeCount")
                client?.requestHistory(lastHistoryLifeCount)
            }
        }

        override fun onAuthorized(kAuth: ByteArray) {
            // Persist immediately: a kAuth that reaches the sensor but not our store means the
            // next reconnect falls back to a full pairing it may refuse.
            credentials.updateKAuth(kAuth)
            aapsLogger.debug(LTag.BGSOURCE, "Libre3: authorized, kAuth stored")
            // History is NOT requested here. Writing to PATCH_CONTROL before its notifications
            // are actually up is rejected with ATT 0xFD (CCCD Improperly Configured) — seen on
            // hardware 2026-09-07. Ask once patch status has arrived, which proves the data
            // channel is live; this is also where Juggluco does it.
        }

        override fun onDisconnected(status: Int) {
            aapsLogger.debug(LTag.BGSOURCE, "Libre3: disconnected status=$status")
            // status 147 (GATT_CONNECTION_TIMEOUT) is ROUTINE on this sensor — it drops and
            // returns by itself. Surfacing it as an error would cry wolf several times a day.
            historyRequested = false          // ask again on the next connection
            // Drop any active lag override across the gap (a stale correction is unsafe); keep the
            // reading window so it can re-arm without waiting for a full refill on the same sensor.
            lagOverride.cancel()
            update {
                it.copy(
                    connection = Libre3SensorState.Connection.Connecting,
                    // Recompute the (time-derived) lifecycle on every disconnect. Otherwise the Sensor
                    // tab freezes on whatever it last showed — e.g. a disconnect-backoff loop during a
                    // failed first connection left it reading "Warming up Nm" long after warm-up ended,
                    // disagreeing with the Overview (which recomputes live). Disconnects fire ~every
                    // 60s, so this keeps the tab advancing without a dedicated ticker.
                    lifecycle = lifecycleNow(),
                    signalDbm = null
                )
            }
        }

        override fun onError(reason: String) {
            aapsLogger.error(LTag.BGSOURCE, "Libre3: $reason")
            update { it.copy(lastError = reason) }
        }
    }

    /**
     * Map the sensor's own trend to AAPS's arrows. `trend == 0` means the sensor is not
     * reporting one — that is NONE, not flat, and the two must not be conflated.
     */
    private fun trendOf(record: Libre3GlucoseRecord): TrendArrow {
        val rate = record.trendMgDlPerMin ?: return TrendArrow.NONE
        return when (rate.roundToInt()) {
            in Int.MIN_VALUE..-3 -> TrendArrow.DOUBLE_DOWN
            -2                   -> TrendArrow.SINGLE_DOWN
            -1                   -> TrendArrow.FORTY_FIVE_DOWN
            0                    -> TrendArrow.FLAT
            1                    -> TrendArrow.FORTY_FIVE_UP
            2                    -> TrendArrow.SINGLE_UP
            else                 -> TrendArrow.DOUBLE_UP
        }
    }

    override fun onStart() {
        super.onStart()
        lagOverride = Libre3LagOverride(lagConfig())     // pick up any pref changes
        val creds = credentials.load()
        update {
            it.copy(
                serial = creds?.serial,
                mac = creds?.mac,
                lifecycle = lifecycleNow(),
                connection = Libre3SensorState.Connection.Connecting
            )
        }
        if (creds == null) {
            aapsLogger.warn(LTag.BGSOURCE, "Libre3: no credentials stored — nothing to connect to")
            return
        }
        connect(creds)
    }

    /** Open the BLE link for [creds]; resume when a kAuth is stored, pair when it is not. */
    private fun connect(creds: Libre3CredentialStore.Credentials) {
        val adapter: BluetoothAdapter? =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            aapsLogger.warn(LTag.BGSOURCE, "Libre3: Bluetooth unavailable")
            return
        }
        val session = Libre3SecuritySession(creds.blePin)
        val c = Libre3BleClient(context, session, listener).apply { storedKAuth = creds.kAuth }
        client = c
        runCatching { c.connect(adapter.getRemoteDevice(creds.mac)) }
            .onFailure { aapsLogger.error(LTag.BGSOURCE, "Libre3: connect failed", it) }
    }

    /**
     * BgSource hook: a manually-entered BG routed here from the "Calibrate" / "BG Check" dialogs.
     * Feeds the rise-lag override. Returns a message when it armed, blank when there was nothing to
     * correct (still "handled", so the caller does NOT fall back to an xDrip calibration broadcast).
     */
    override fun onManualBg(timeMs: Long, glucoseMgdl: Int): String =
        when (val r = applyManualBg(glucoseMgdl, timeMs)) {
            is Libre3LagOverride.ArmResult.Armed ->
                "Rise-lag correction on: sensor +${profileUtil.fromMgdlToStringInUnits(r.gapMgdl)} ${profileUtil.units.asText}, fading as the rise flattens."

            is Libre3LagOverride.ArmResult.Rejected -> ""   // consumed by the native source; nothing to correct
        }

    /**
     * Feed a finger-prick BG (mg/dL) as ground truth. On a fast rise the lagging sensor reads low and
     * the loop under-doses; this arms the lag override — the finger-prick is inserted immediately as
     * the current BG, and a decaying correction is carried onto subsequent sensor readings until the
     * rise resolves (see [Libre3LagOverride]). Returns the arm result so the UI can explain what
     * happened (armed, or why not). If it doesn't arm, nothing is inserted here — log it as a normal
     * BG check instead.
     */
    fun applyManualBg(manualMgdl: Int, timeMs: Long = dateUtil.now()): Libre3LagOverride.ArmResult {
        val arm = lagOverride.armFromManualBg(timeMs, manualMgdl)
        if (arm is Libre3LagOverride.ArmResult.Armed) {
            val gv = GV(
                timestamp = timeMs,
                value = arm.correctedMgdl.toDouble(),   // clamped + sensor-anchored, NEVER the raw prick
                raw = null,
                noise = null,
                trendArrow = TrendArrow.NONE,
                sourceSensor = SourceSensor.LIBRE_3
            )
            persistenceLayer.insertCgmSourceData(Sources.Libre3, listOf(gv), emptyList(), null)
                .subscribe({ }, { aapsLogger.error(LTag.BGSOURCE, "Libre3: manual BG insert failed", it) })
            aapsLogger.info(
                LTag.BGSOURCE,
                "Libre3: lag override armed, gap=${arm.gapMgdl} mg/dL${if (arm.clamped) " (clamped)" else ""}"
            )
        } else {
            aapsLogger.debug(
                LTag.BGSOURCE,
                "Libre3: manual BG not armed: ${(arm as Libre3LagOverride.ArmResult.Rejected).reason}"
            )
        }
        return arm
    }

    /**
     * Read the scanned tag's patch info — a pure NFC read, safe to run so the UI can show the user
     * exactly what a scan will do (fresh sensor → activate, running sensor → take over) before they
     * commit. Runs on the caller's (background) thread; NFC I/O must not touch the main thread.
     */
    fun readSensorInfo(tag: android.nfc.Tag): Libre3NfcActivation.SensorInfo? =
        Libre3NfcActivation(Libre3NfcV.transceiver(tag)).readInfo()

    /**
     * Activate (or take over) the scanned sensor and switch AAPS onto it. **IRREVERSIBLE for a fresh
     * sensor** — only call from behind the Libre3ScanFlow confirmation. On success it writes a fresh
     * credentials file (kAuth null, so the first BLE connection pairs) and connects; the first
     * pairing's `onAuthorized` then persists the kAuth. Runs on a background thread.
     */
    fun activateSensor(tag: android.nfc.Tag): Libre3NfcActivation.Result {
        val nowSec = dateUtil.now() / 1000L
        val result = Libre3NfcActivation(Libre3NfcV.transceiver(tag)).activate(nowSec, LIBRE3_ACCOUNT_ID)
        if (result is Libre3NfcActivation.Result.Activated) {
            client?.disconnect(); client = null
            lastLifeCount = -1; lastHistoryLifeCount = 0; backfilled = 0; sensorStartRecorded = null
            lagOverride.reset()                              // new sensor — old readings/override void
            credentials.save(
                Libre3CredentialStore.Credentials(
                    serial = result.serialNumber,
                    mac = result.mac,
                    blePin = result.blePin,
                    kAuth = null,                                   // first BLE connect pairs and mints it
                    startedEpochMs = result.activationTimeSec * 1000L,
                    warmupMinutes = result.warmupMinutes,
                    lifeDays = if (result.isPlus) 15 else 14
                )
            )
            update {
                it.copy(
                    serial = result.serialNumber, mac = result.mac,
                    lifecycle = lifecycleNow(), connection = Libre3SensorState.Connection.Connecting
                )
            }
            credentials.load()?.let { connect(it) }
            aapsLogger.info(LTag.BGSOURCE, "Libre3: activated ${result.serialNumber} (${result.mac}); pairing")
        }
        return result
    }

    /**
     * End the current sensor: drop the link and clear the stored credentials. The sensor keeps
     * running physically — this only stops AAPS following it, which is why the wording says
     * "cannot be restarted" rather than pretending we can terminate the patch.
     */
    fun stopSensor() {
        client?.disconnect()
        client = null
        credentials.clear()
        lastLifeCount = -1
        lastHistoryLifeCount = 0
        backfilled = 0
        lagOverride.reset()
        update { Libre3SensorState() }
        aapsLogger.debug(LTag.BGSOURCE, "Libre3: sensor stopped by user")
    }

    /** Same as [stopSensor] today; kept separate because their meanings will diverge. */
    fun forgetSensor() = stopSensor()

    override fun onStop() {
        client?.disconnect()
        client = null
        lastLifeCount = -1
        lagOverride.reset()
        update { Libre3SensorState() }
        super.onStop()
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "libre3_lag_settings"
            title = context.getString(R.string.libre3_lag_category)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveIntPreference(ctx = context, intKey = IntKey.Libre3LagWindow, title = R.string.libre3_lag_window))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.Libre3LagArmRate, title = R.string.libre3_lag_arm_rate))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.Libre3LagHalfLifeMin, title = R.string.libre3_lag_halflife))
            addPreference(AdaptiveDoublePreference(ctx = context, doubleKey = DoubleKey.Libre3LagMaxDurationMin, title = R.string.libre3_lag_maxduration))
        }
    }

    companion object {
        /**
         * The Libre account id baked into the activation payload (`startTime ‖ accountId ‖ CRC16`).
         * There is no keyed MAC here: the sensor stores the id as the owner and validates only the
         * CRC, so any non-zero value activates a fresh sensor — but 0 is a known-bad path (Juggluco
         * reports it as 0xF8). A fixed non-zero id is fine for a fresh AAPS activation; only TAKEOVER
         * of a sensor started elsewhere needs to match that app's id.
         *
         * ⚠️ This has NOT been exercised against a real sensor from AAPS. Before the first live
         * activation, confirm the value (e.g. set it to the id Juggluco used, captured by hooking
         * `getlibreAccountIDnumber`) — a rejected activation costs a physical sensor.
         */
        // The Libre account id baked into the sensor at NFC activation. The pairing crypto is tied to
        // it, so this MUST match the account every sensor was activated under in Juggluco — otherwise
        // the sensor ACKs BEGIN_PAIRING but withholds its certificate and never pairs. Was the leftover
        // placeholder 0x41415053 ("AAPS"), which silently broke every AAPS-native activation; the real
        // account (Juggluco "Write down!" value) is 4935647751. uint32LE-truncated in activationData,
        // exactly as the faithful Juggluco port does.
        const val LIBRE3_ACCOUNT_ID: Long = 4935647751L
    }
}

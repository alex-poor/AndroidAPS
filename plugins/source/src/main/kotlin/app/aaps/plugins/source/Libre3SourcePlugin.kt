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
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.libre3.Libre3BleClient
import app.aaps.libre3.Libre3GlucoseRecord
import app.aaps.libre3.Libre3History
import app.aaps.libre3.Libre3PatchStatus
import app.aaps.libre3.Libre3SecuritySession
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
    private val credentials: Libre3CredentialStore
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
            lastReadingAt = dateUtil.now()
            update {
                it.copy(
                    connection = Libre3SensorState.Connection.Connected,
                    lifecycle = lifecycleNow(),
                    lastReadingAgoMinutes = 0,
                    lastError = null
                )
            }

            val gv = GV(
                timestamp = dateUtil.now(),
                value = record.readingMgDl.toDouble(),
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
            update {
                it.copy(
                    connection = Libre3SensorState.Connection.Connecting,
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
        update { Libre3SensorState() }
        aapsLogger.debug(LTag.BGSOURCE, "Libre3: sensor stopped by user")
    }

    /** Same as [stopSensor] today; kept separate because their meanings will diverge. */
    fun forgetSensor() = stopSensor()

    override fun onStop() {
        client?.disconnect()
        client = null
        lastLifeCount = -1
        update { Libre3SensorState() }
        super.onStop()
    }
}

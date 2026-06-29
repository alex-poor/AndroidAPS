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
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.ypsopump.ble.YpsoBleManager
import app.aaps.pump.ypsopump.ble.YpsoBleManager.ConnectionState
import app.aaps.pump.ypsopump.data.YpsoPumpState
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

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

    private fun seedAndConnect() {
        bleManager.setSharedKey(YpsoPumpConst.CAPTURED_KEY_HEX)
        if (YpsoPumpConst.CAPTURED_WRITE_COUNTER >= 0)
            bleManager.setCounters(YpsoPumpConst.CAPTURED_WRITE_COUNTER, YpsoPumpConst.CAPTURED_REBOOT_COUNTER)
        bleManager.connect(YpsoPumpConst.PUMP_MAC)
    }

    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "connect: $reason")
        if (YpsoPumpConst.CAPTURED_KEY_HEX.isEmpty()) {
            aapsLogger.info(LTag.PUMP, "YpsoPump: CAPTURED_KEY_HEX not set — skipping connect")
            return
        }
        seedAndConnect()
    }

    override fun disconnect(reason: String) { aapsLogger.debug(LTag.PUMP, "disconnect: $reason"); bleManager.disconnect() }
    override fun stopConnecting() { bleManager.disconnect() }

    override fun getPumpStatus(reason: String) {
        aapsLogger.debug(LTag.PUMP, "getPumpStatus: $reason")
        if (YpsoPumpConst.CAPTURED_KEY_HEX.isEmpty()) {
            aapsLogger.info(LTag.PUMP, "YpsoPump: CAPTURED_KEY_HEX not set — skipping read")
            return
        }
        if (!bleManager.isConnected) { seedAndConnect(); return }
        // ZERO-THERAPY write-transport validation runs once instead of a status read, when armed.
        if (YpsoPumpConst.RUN_WRITE_VALIDATION && YpsoPumpConst.CAPTURED_WRITE_COUNTER >= 0 && !writeValidationDone) {
            writeValidationDone = true
            bleManager.validateWriteTransport { r -> aapsLogger.info(LTag.PUMP, "YpsoPump WRITE-VALIDATION: $r") }
        } else {
            bleManager.readStatus()
        }
    }

    override val lastDataTime: Long get() = pumpState.lastConnectionTime
    override val lastBolusTime: Long? get() = null
    override val lastBolusAmount: Double? get() = null
    override val baseBasalRate: Double get() = pumpState.activeBasalRate
    override val reservoirLevel: Double get() = pumpState.reservoirUnits
    override val batteryLevel: Int? get() = pumpState.batteryPercent

    // ---- dosing (stubbed for the read-only milestone) ----
    override fun setNewBasalProfile(profile: Profile): PumpEnactResult = notImplemented()
    override fun isThisProfileSet(profile: Profile): Boolean = true
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult = notImplemented()
    override fun stopBolusDelivering() {}
    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult = notImplemented()
    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult = notImplemented()
    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult = notImplemented()
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

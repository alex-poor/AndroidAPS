package app.aaps.pump.virtual

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.data.pump.defs.DoseStepSize
import app.aaps.core.data.pump.defs.PumpTempBasalType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.pump.defs.baseBasalRange
import app.aaps.core.interfaces.pump.defs.hasExtendedBasals
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.toStringFull
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.pump.virtual.compose.VirtualPumpRow
import app.aaps.pump.virtual.compose.VirtualPumpScreen
import app.aaps.pump.virtual.compose.VirtualPumpState
import app.aaps.pump.virtual.events.EventVirtualPumpUpdateGui
import app.aaps.pump.virtual.keys.VirtualBooleanNonPreferenceKey
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class VirtualPumpFragment : DaggerFragment() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var virtualPumpPlugin: VirtualPumpPlugin
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var preferences: Preferences

    private val disposable = CompositeDisposable()

    private lateinit var refreshLoop: Runnable
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private val state = mutableStateOf(VirtualPumpState())


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    VirtualPumpScreen(state.value) { suspended ->
                        preferences.put(VirtualBooleanNonPreferenceKey.IsSuspended, suspended)
                        updateGui()
                    }
                }
            }
        }

    @Synchronized
    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventVirtualPumpUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ updateGui() }, fabricPrivacy::logException)
        refreshLoop = Runnable {
            activity?.runOnUiThread { updateGui() }
            handler.postDelayed(refreshLoop, T.mins(1).msecs())
        }
        handler.postDelayed(refreshLoop, T.mins(1).msecs())

        updateGui()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    @Synchronized
    private fun updateGui() {
        val profile = profileFunction.getProfile() ?: return
        virtualPumpPlugin.refreshConfiguration()
        val pumpType = virtualPumpPlugin.pumpType
        state.value = VirtualPumpState(
            suspended = preferences.get(VirtualBooleanNonPreferenceKey.IsSuspended),
            status = listOf(
                VirtualPumpRow("Base basal rate", rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, virtualPumpPlugin.baseBasalRate)),
                VirtualPumpRow(
                    rh.gs(app.aaps.core.ui.R.string.tempbasal_label),
                    persistenceLayer.getTemporaryBasalActiveAt(dateUtil.now())?.toStringFull(profile, dateUtil, rh) ?: ""
                ),
                VirtualPumpRow(
                    rh.gs(app.aaps.core.ui.R.string.extended_bolus),
                    persistenceLayer.getExtendedBolusActiveAt(dateUtil.now())?.toStringFull(dateUtil, rh) ?: ""
                ),
                VirtualPumpRow("Battery", rh.gs(app.aaps.core.ui.R.string.format_percent, virtualPumpPlugin.batteryPercent)),
                VirtualPumpRow(
                    rh.gs(app.aaps.core.ui.R.string.reservoir_label),
                    rh.gs(app.aaps.core.ui.R.string.format_insulin_units, virtualPumpPlugin.reservoirInUnits.toDouble())
                ),
                VirtualPumpRow("Type", pumpType?.description ?: ""),
                VirtualPumpRow(rh.gs(app.aaps.core.ui.R.string.serial_number), virtualPumpPlugin.serialNumber())
            ).filter { it.value.isNotBlank() },
            definition = pumpType?.getFullDescription(rh.gs(R.string.virtual_pump_pump_def), pumpType.hasExtendedBasals(), rh) ?: ""
        )
    }

    private fun getStep(step: String, stepSize: DoseStepSize?): String =
        if (stepSize != null) step + " [" + stepSize.description + "] *"
        else step

    private fun PumpType.getFullDescription(i18nTemplate: String, hasExtendedBasals: Boolean, rh: ResourceHelper): String {
        val unit = if (pumpTempBasalType() == PumpTempBasalType.Percent) "%" else ""
        val eb = extendedBolusSettings() ?: return "INVALID"
        val tbr = tbrSettings() ?: return "INVALID"
        val extendedNote = if (hasExtendedBasals) rh.gs(R.string.def_extended_note) else ""
        return String.format(
            i18nTemplate,
            getStep(bolusSize().toString(), specialBolusSize()),
            eb.step, eb.durationStep, eb.maxDuration / 60,
            getStep(baseBasalRange(), baseBasalSpecialSteps()),
            tbr.minDose.toString() + unit + "-" + tbr.maxDose + unit, tbr.step.toString() + unit,
            tbr.durationStep, tbr.maxDuration / 60, extendedNote
        )
    }
}

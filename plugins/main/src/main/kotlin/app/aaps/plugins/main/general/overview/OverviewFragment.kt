package app.aaps.plugins.main.general.overview

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AnimationDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.text.toSpanned
import androidx.recyclerview.widget.LinearLayoutManager
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.graph.data.GraphViewWithCleanup
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.bgQualityCheck.BgQualityCheck
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.nsclient.NSSettingsStatus
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.overview.Overview
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAcceptOpenLoopChange
import app.aaps.core.interfaces.rx.events.EventBucketedDataCreated
import app.aaps.core.interfaces.rx.events.EventEffectiveProfileSwitchChanged
import app.aaps.core.interfaces.rx.events.EventExtendedBolusChange
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventNewOpenLoopNotification
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.rx.events.EventRunningModeChange
import app.aaps.core.interfaces.rx.events.EventScale
import app.aaps.core.interfaces.rx.events.EventTempBasalChange
import app.aaps.core.interfaces.rx.events.EventTempTargetChange
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewCalcProgress
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewGraph
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewIobCob
import app.aaps.core.interfaces.rx.events.EventUpdateOverviewSensitivity
import app.aaps.core.interfaces.source.XDripSource
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.directionToIcon
import app.aaps.core.objects.extensions.displayText
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.UIRunnable
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.elements.SingleClickButton
import app.aaps.core.ui.extensions.runOnUiThread
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.extensions.toVisibilityKeepSpace
import app.aaps.plugins.main.R
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import app.aaps.core.compose.theme.AapsSemantic
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.main.general.overview.compose.HomeActions
import app.aaps.plugins.main.general.overview.compose.HomeScreen
import app.aaps.plugins.main.general.overview.compose.TreatmentKind
import app.aaps.plugins.main.general.overview.compose.HomeGlucoseChart
import app.aaps.plugins.main.general.overview.compose.HomeChartData
import app.aaps.plugins.main.general.overview.compose.GlucosePoint
import app.aaps.plugins.main.general.overview.compose.ChartTreatment
import app.aaps.plugins.main.general.overview.compose.BasalStep
import app.aaps.core.data.model.BS
import app.aaps.plugins.main.general.overview.compose.HomeUiState
import app.aaps.plugins.main.general.overview.graphData.GraphData
import app.aaps.plugins.main.general.overview.notifications.NotificationStore
import app.aaps.plugins.main.general.overview.notifications.events.EventUpdateOverviewNotification
import app.aaps.plugins.main.general.overview.ui.StatusLightHandler
import com.jjoe64.graphview.GraphView
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class OverviewFragment : DaggerFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var statusLightHandler: StatusLightHandler
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var nsSettingsStatus: NSSettingsStatus
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var xDripSource: XDripSource
    @Inject lateinit var notificationStore: NotificationStore
    @Inject lateinit var config: Config
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var overviewMenus: OverviewMenus
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var overview: Overview
    @Inject lateinit var lastBgData: LastBgData
    @Inject lateinit var bgQualityCheck: BgQualityCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var graphDataProvider: Provider<GraphData>
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var calculationWorkflow: CalculationWorkflow

    private val disposable = CompositeDisposable()

    private var smallWidth = false
    private var smallHeight = false
    private var axisWidth: Int = 0
    private var composeHome: ComposeView? = null
    private val chartData = mutableStateOf(HomeChartData())
    private lateinit var refreshLoop: Runnable
    private var handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)



    // ---- Redesigned Home (Compose overlay) ----
    private val homeState = mutableStateOf(HomeUiState())
    // Recent carb records for the COB-tap undo sheet. Computed off the UI thread in updateIobCob()
    // (which already reads persistence there) and read synchronously by buildHomeState().
    private var recentCarbs: List<HomeUiState.CarbEntry> = emptyList()

    /**
     * The legacy overview layout is inflated but `android:visibility="gone"` and covered by the
     * opaque Compose home, so nothing in it is ever seen. With this set, its update functions
     * return immediately instead of formatting ~120 values into invisible views and redrawing the
     * legacy primary and secondary GraphViews on every refresh, and the hidden subtree costs no
     * measure/layout/draw.
     *
     * Flip to false to bring the legacy overview back (it also needs its `visibility` removed in
     * overview_fragment.xml) — useful if something in the redesign needs comparing against it.
     */




    //@SuppressLint("NewApi")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).also { composeHome = it }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        axisWidth = when {
            resources.displayMetrics.densityDpi <= 120 -> 3
            resources.displayMetrics.densityDpi <= 160 -> 10
            resources.displayMetrics.densityDpi <= 320 -> 35
            resources.displayMetrics.densityDpi <= 420 -> 50
            resources.displayMetrics.densityDpi <= 560 -> 70
            else                                       -> 80
        }

        // ---- Redesigned Home (Compose) ----
        val actions = buildHomeActions()
        composeHome?.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeHome?.setContent {
            AapsTheme {
                HomeScreen(
                    state = homeState.value,
                    actions = actions,
                    graph = { HomeGlucoseChart(chartData.value, Modifier.fillMaxSize()) }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewCalcProgress::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewIobCob::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ updateIobCob() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewSensitivity::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewGraph::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ refreshChart() }, fabricPrivacy::logException)
        disposable += activePlugin.activeOverview.overviewBus
            .toObservable(EventUpdateOverviewNotification::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventScale::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           overviewData.rangeToDisplay = it.hours
                           preferences.put(IntNonKey.RangeToDisplay, it.hours)
                           rxBus.send(EventPreferenceChange(IntNonKey.RangeToDisplay.key))
                           preferences.put(BooleanNonKey.ObjectivesScaleUsed, true)
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventBucketedDataCreated::class.java)
            .debounce(1L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRefreshOverview::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                           if (it.now) refreshAll()
                           else scheduleUpdateGUI()
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAcceptOpenLoopChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .delay(30, TimeUnit.MILLISECONDS, aapsSchedulers.main)
            .subscribe({
                           overviewData.pumpStatus = it.getStatus(requireContext())
                       }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventEffectiveProfileSwitchChanged::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempTargetChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventExtendedBolusChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTempBasalChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventRunningModeChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ scheduleUpdateGUI() }, fabricPrivacy::logException)

        refreshLoop = Runnable {
            refreshAll()
            handler.postDelayed(refreshLoop, 60 * 1000L)
        }
        handler.postDelayed(refreshLoop, 60 * 1000L)

        // Graph series are not prepared while the overview is off screen (see
        // CalculationWorkflowImpl.runCalculation), so rebuild them now rather than showing a stale
        // or empty chart until the next CGM tick. Presentation only - no IOB/COB, no loop.
        calculationWorkflow.runGraphsOnly(iobCobCalculator, overviewData)

        handler.post { refreshAll() }

        popupBolusDialogIfRunning(onClick = false)
    }

    fun refreshAll() {
        if (!config.appInitialized) return
        // updateIobCob() is the fast path that refreshes the Compose hero's IOB/COB about a second
        // after a carb entry, instead of waiting for the next 60 s tick.
        updateIobCob()
        refreshChart()
        runOnUiThread {
            composeHome ?: return@runOnUiThread
            buildHomeState()
        }
    }

    // region ---- Redesigned Home (Compose) ----

    /** Wire the Compose Home actions to the SAME protected dialog paths as the legacy buttons. */
    private fun buildHomeActions(): HomeActions {
        fun bolusProtected(run: () -> Unit) = activity?.let { act ->
            if (childFragmentManager.isStateSaved) return@let
            protectionCheck.queryProtection(act, ProtectionCheck.Protection.BOLUS, UIRunnable { if (isAdded) run() })
        }
        return HomeActions(
            onCarbs = { bolusProtected { uiInteraction.runCarbsDialog(childFragmentManager) } },
            onBolus = { bolusProtected { uiInteraction.runTreatmentDialog(childFragmentManager) } },
            onWizard = { bolusProtected { uiInteraction.runWizardDialog(childFragmentManager) } },
            // "Record only" insulin entry (log a delivered pump/pen bolus into IOB WITHOUT re-delivering).
            // InsulinDialog is the ONLY dialog with a user-toggleable "Record only" — the redesign dropped
            // its old insulin_button, so surface it here in the "+" menu.
            onInsulinRecord = { bolusProtected { uiInteraction.runInsulinDialog(childFragmentManager) } },
            onDismissAlert = { alert ->
                context?.let { ctx ->
                    notificationStore.snapshot().firstOrNull { it.id == alert.id }?.let { notificationStore.dismiss(it, ctx) }
                }
                refreshAll()
            },
            onMore = { bolusProtected { uiInteraction.runTempTargetDialog(childFragmentManager) } },
            onLoop = { bolusProtected { uiInteraction.runLoopDialog(childFragmentManager, 1) } },
            onTempTarget = { bolusProtected { uiInteraction.runTempTargetDialog(childFragmentManager) } },
            onProfile = { uiInteraction.runProfileViewerDialog(childFragmentManager, dateUtil.now(), UiInteraction.Mode.RUNNING_PROFILE) },
            onIob = { activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.iob), iobDialogText()) } },
            onCob = { bolusProtected { uiInteraction.runCarbsDialog(childFragmentManager) } },
            onDeleteCarb = { entry -> bolusProtected { removeCarbEntry(entry) } },
            onBasal = { activity?.let { OKDialog.show(it, rh.gs(app.aaps.core.ui.R.string.basal), overviewData.temporaryBasalDialogText()) } },
            // graph range: reuse the existing EventScale path (persists RangeToDisplay + refreshes)
            onRange = { hours -> rxBus.send(EventScale(hours)) },
            onCalibration = { bolusProtected { uiInteraction.runCalibrationDialog(childFragmentManager) } }
        )
    }

    /**
     * Undo a recent carb entry from the COB-tap sheet. Confirms first, then reuses the SAME
     * `persistenceLayer.invalidateCarbs` path (with UEL audit log) as the legacy Treatments screen —
     * no bypass. The next iobCob refresh rebuilds the hero + sheet, so the removed entry disappears.
     */
    private fun removeCarbEntry(entry: HomeUiState.CarbEntry) {
        val activity = activity ?: return
        OKDialog.showConfirmation(
            activity,
            rh.gs(app.aaps.core.ui.R.string.removerecord),
            rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + entry.grams + "\n" +
                rh.gs(app.aaps.core.ui.R.string.date) + ": " + dateUtil.dateAndTimeString(entry.timestamp),
            Runnable {
                disposable += persistenceLayer.invalidateCarbs(
                    entry.id,
                    action = Action.CARBS_REMOVED,
                    source = Sources.Overview,
                    listValues = listOf(
                        ValueWithUnit.Timestamp(entry.timestamp),
                        ValueWithUnit.Gram(entry.amount)
                    )
                ).subscribe()
            }
        )
    }

    private fun trendSymbol(arrow: TrendArrow?): String = when (arrow) {
        TrendArrow.TRIPLE_UP, TrendArrow.DOUBLE_UP -> "⇈"
        TrendArrow.SINGLE_UP                       -> "↑"
        TrendArrow.FORTY_FIVE_UP                   -> "↗"
        TrendArrow.FLAT                            -> "→"
        TrendArrow.FORTY_FIVE_DOWN                 -> "↘"
        TrendArrow.SINGLE_DOWN                     -> "↓"
        TrendArrow.TRIPLE_DOWN, TrendArrow.DOUBLE_DOWN -> "⇊"
        else                                       -> ""
    }

    /** Map the current Overview providers into [HomeUiState]. Runs on the UI thread. */
    @SuppressLint("SetTextI18n")
    private fun buildHomeState() {
        if (!config.appInitialized) return
        val ctx = context ?: return
        val units = profileFunction.getUnits()
        val unitsStr = if (units == GlucoseUnit.MMOL) "mmol/L" else "mg/dL"
        val lastBg = lastBgData.lastBg()
        val isActual = lastBgData.isActualBg()
        val gs = glucoseStatusProvider.glucoseStatusData
        val profile = profileFunction.getProfile()
        val bgMgdl = lastBg?.recalculated
        // Colour the BG value against the display HYPO/HYPER thresholds (Overview Low/High marks —
        // the same thresholds AAPS uses for BG colouring elsewhere), NOT the tighter profile target
        // band, so a BG just above target isn't alarmingly amber. The state line below still describes
        // position vs the target band (informational).
        val lowMarkMgdl = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), units)
        val highMarkMgdl = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewHighMark), units)
        val bgColor = when {
            bgMgdl == null        -> AapsSemantic.inRange
            bgMgdl > highMarkMgdl -> AapsSemantic.high   // amber (hyper)
            bgMgdl < lowMarkMgdl  -> AapsSemantic.low    // red (hypo)
            else                  -> AapsSemantic.inRange // green
        }

        // Loop mode → pill label / color / looping
        val mode = loop.runningMode
        val loopActive = mode == RM.Mode.CLOSED_LOOP || mode == RM.Mode.CLOSED_LOOP_LGS || mode == RM.Mode.SUPER_BOLUS
        val loopColor = when {
            loopActive                          -> AapsSemantic.inRange
            mode == RM.Mode.OPEN_LOOP           -> AapsSemantic.high
            mode == RM.Mode.DISABLED_LOOP ||
                mode == RM.Mode.DISCONNECTED_PUMP -> AapsSemantic.low
            else                                -> AapsSemantic.high // suspended variants
        }
        val loopLabel = when (mode) {
            RM.Mode.CLOSED_LOOP       -> rh.gs(app.aaps.core.ui.R.string.closedloop)
            RM.Mode.CLOSED_LOOP_LGS   -> rh.gs(app.aaps.core.ui.R.string.uel_lgs_loop_mode)
            RM.Mode.OPEN_LOOP         -> rh.gs(app.aaps.core.ui.R.string.openloop)
            RM.Mode.DISABLED_LOOP     -> rh.gs(R.string.disabled_loop)
            RM.Mode.DISCONNECTED_PUMP -> rh.gs(app.aaps.core.ui.R.string.disconnected)
            RM.Mode.SUPER_BOLUS       -> rh.gs(app.aaps.core.ui.R.string.superbolus)
            else                      -> rh.gs(app.aaps.core.ui.R.string.pumpsuspended)
        }
        val loopSub = if (loopActive) "· looping"
        else if (mode == RM.Mode.SUSPENDED_BY_USER || mode == RM.Mode.DISCONNECTED_PUMP || mode == RM.Mode.SUSPENDED_BY_DST)
            dateUtil.age(loop.minutesToEndOfSuspend() * 60000L, true, rh) else ""

        // Eventual BG = the algorithm's own output (APSResult.eventualBG via RT). The ONLY forward-
        // looking number on the hero. Hidden when null (open loop / no run yet).
        val rt = loop.lastRun?.constraintsProcessed?.rawData() as? RT
        val eventualMgdl = if (config.APS) rt?.eventualBG else null

        // State line — describes the CURRENT reading only, vs the profile target band.
        val targetLow = profile?.getTargetLowMgdl()
        val targetHigh = profile?.getTargetHighMgdl()
        val targetRange = if (targetLow != null && targetHigh != null)
            "${profileUtil.fromMgdlToStringInUnits(targetLow)}–${profileUtil.fromMgdlToStringInUnits(targetHigh)} $unitsStr" else ""
        val stateLine = if (bgMgdl != null && targetLow != null && targetHigh != null) when {
            bgMgdl > targetHigh -> "${profileUtil.fromMgdlToStringInUnits(bgMgdl - targetHigh)} above target"
            bgMgdl < targetLow  -> "${profileUtil.fromMgdlToStringInUnits(targetLow - bgMgdl)} below target"
            else                -> "In target range"
        } else ""

        // Basal — lead with the delivered rate (U/h); scheduled changes through the day.
        val basalData = profile?.let { iobCobCalculator.getBasalData(it, dateUtil.now()) }
        val scheduledBasal = basalData?.basal ?: 0.0
        val rateNow = if (basalData?.isTempBasalRunning == true) basalData.tempBasalAbsolute else scheduledBasal
        val basalPercent = if (scheduledBasal > 0) (rateNow / scheduledBasal * 100).roundToInt() else 100
        val basalText = String.format(Locale.getDefault(), "%.2f U/h", rateNow)
        val basalSubText = if (basalData?.isTempBasalRunning == true && scheduledBasal > 0)
            "$basalPercent% · ${String.format(Locale.getDefault(), "%.2f", scheduledBasal)} sched" else ""

        // Stats
        val cobText = iobCobCalculator.getCobInfo("Overview COB").displayText(rh, decimalFormatter)
        val autosensRatio = iobCobCalculator.ads.getLastAutosensData("Overview", aapsLogger, dateUtil)?.autosensResult?.ratio

        // Supplies: cannula + sensor age (always available from therapy events) + reservoir/battery
        // (only when the pump actually reports them — they read 0/unknown until a fresh pump read).
        val pump = activePlugin.activePump
        val now = dateUtil.now()
        fun ageDays(type: TE.Type): String? = persistenceLayer.getLastTherapyRecordUpToNow(type)?.let {
            "${TimeUnit.MILLISECONDS.toDays(now - it.timestamp)}d"
        }
        val supplies = buildList {
            ageDays(TE.Type.CANNULA_CHANGE)?.let {
                add(HomeUiState.Supply(if (pump.pumpDescription.isPatchPump) "Patch" else "Cannula", it, AapsSemantic.inRange))
            }
            // Sensor: show a depleting countdown to EXPIRY (not just elapsed age). Life assumed 10 d
            // (Dexcom G6); expiry = last SENSOR_CHANGE + life. Ring fraction = life remaining.
            persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.SENSOR_CHANGE)?.let { te ->
                val lifeMs = TimeUnit.DAYS.toMillis(10)
                val remaining = te.timestamp + lifeMs - now
                val fraction = (remaining.toFloat() / lifeMs).coerceIn(0f, 1f)
                val remH = TimeUnit.MILLISECONDS.toHours(remaining)
                val label = when {
                    remaining <= 0 -> "Expired"
                    remH >= 24     -> "${remH / 24}d ${remH % 24}h"
                    remH >= 1      -> "${remH}h"
                    else           -> "${TimeUnit.MILLISECONDS.toMinutes(remaining)}m"
                }
                val color = when {
                    remaining <= 0 -> AapsSemantic.low
                    remH < 12      -> AapsSemantic.low
                    remH < 48      -> AapsSemantic.high
                    else           -> AapsSemantic.inRange
                }
                add(HomeUiState.Supply("Sensor", label, color, fraction = fraction))
            }
            val res = pump.reservoirLevel
            if (res > 0) add(
                HomeUiState.Supply(
                    "Reservoir",
                    rh.gs(app.aaps.core.ui.R.string.format_insulin_units, res),
                    if (res < 20) AapsSemantic.high else AapsSemantic.inRange
                )
            )
            // Keep the battery pill stable: the pump reports 0 while disconnected/unread, so show "—"
            // (neutral) rather than letting the pill vanish and reappear.
            pump.batteryLevel?.let { bat ->
                add(HomeUiState.Supply("Battery", if (bat > 0) "$bat%" else "—", if (bat in 1..24) AapsSemantic.low else AapsSemantic.inRange))
            }
        }

        homeState.value = HomeUiState(
            loopStateLabel = loopLabel,
            loopSubLabel = loopSub,
            loopColor = loopColor,
            looping = loopActive,
            bg = profileUtil.fromMgdlToStringInUnits(lastBg?.recalculated),
            bgColor = bgColor,
            bgStale = !isActual,
            units = unitsStr,
            trendArrow = trendSymbol(trendCalculator.getTrendArrow(iobCobCalculator.ads)),
            delta = gs?.let { profileUtil.fromMgdlToSignedStringInUnits(it.delta) } ?: "",
            timeAgo = dateUtil.minOrSecAgo(rh, lastBg?.timestamp),
            eventualBg = eventualMgdl?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "",
            stateLine = stateLine,
            targetRange = targetRange,
            iob = iobText(),
            iobSub = rh.gs(app.aaps.core.ui.R.string.bolus) + " + " + rh.gs(app.aaps.core.ui.R.string.basal),
            cob = cobText ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short),
            cobSub = "",
            basal = basalText,
            basalSub = basalSubText,
            supplies = supplies,
            recentCarbs = recentCarbs,
            graphRangeHours = overviewData.rangeToDisplay,
            algorithmName = (activePlugin.activeAPS as? PluginBase)?.name ?: "",
            sensitivity = autosensRatio?.let { "${(it * 100).toInt()}%" } ?: "",
            profileName = profileFunction.getProfileName(),
            tempTarget = null,
            ready = true,
            notifications = notificationStore.snapshot().map {
                HomeUiState.Alert(
                    id = it.id,
                    text = it.text,
                    time = dateUtil.timeString(it.date),
                    level = it.level,
                    buttonText = if (it.buttonText != 0) rh.gs(it.buttonText) else rh.gs(app.aaps.core.ui.R.string.snooze)
                )
            }
        )
    }

    /**
     * Assemble the home chart series. Same sources the GraphView pipeline used — readings from
     * [overviewData], delivered rate from [iobCobCalculator], treatments from the persistence layer —
     * so this is a rendering change, not a data change.
     *
     * Runs off the UI thread (see [refreshAll]); the effective-rate sampling below is the expensive
     * part and must not land on the main thread.
     */
    /** Rebuild the chart series on the background handler, then publish to Compose. */
    private fun refreshChart() {
        handler.post {
            val d = try { buildChartData() } catch (e: Exception) { fabricPrivacy.logException(e); null }
            d?.let { runOnUiThread { chartData.value = it } }
        }
    }

    private fun buildChartData(): HomeChartData {
        val profile = profileFunction.getProfile() ?: return HomeChartData()
        val from = overviewData.fromTime
        val to = overviewData.toTime
        if (to <= from) return HomeChartData()

        val readings = overviewData.bgReadingsArray
            .filter { it.timestamp in from..to }
            .sortedBy { it.timestamp }
            .map { GlucosePoint(it.timestamp, profileUtil.fromMgdlToUnits(it.value)) }
        if (readings.isEmpty()) return HomeChartData()

        // Temp basals overlap and supersede one another, so sample the EFFECTIVE rate on a grid
        // rather than drawing one step per record — a per-record path doubles back on itself.
        val samples = 240
        val stepMs = ((to - from) / samples).coerceAtLeast(60_000L)
        val basal = ArrayList<BasalStep>(samples + 1)
        var t = from
        while (t <= to) {
            val bd = iobCobCalculator.getBasalData(profile, t)
            basal.add(BasalStep(t, if (bd.isTempBasalRunning) bd.tempBasalAbsolute else bd.basal))
            t += stepMs
        }

        val treatments = ArrayList<ChartTreatment>()
        persistenceLayer.getBolusesFromTimeToTime(from, to, true).forEach { b ->
            if (b.isValid && b.type != BS.Type.PRIMING && b.amount > 0.0)
                treatments.add(ChartTreatment(b.timestamp, b.amount, if (b.type == BS.Type.SMB) TreatmentKind.SMB else TreatmentKind.BOLUS))
        }
        // NOT the expanded query: that splits one meal into an absorption series, which would draw a
        // 90 g meal as a row of identical dots instead of a single mark sized by the meal.
        persistenceLayer.getCarbsFromTimeNotExpanded(from, true).blockingGet().forEach { c ->
            if (c.isValid && c.timestamp <= to && c.amount > 0.0)
                treatments.add(ChartTreatment(c.timestamp, c.amount, TreatmentKind.CARBS))
        }

        return HomeChartData(
            from = from,
            to = to,
            now = dateUtil.now(),
            readings = readings,
            basal = basal,
            scheduledBasal = profile.getBasal(dateUtil.now()),
            treatments = treatments,
            // The SAME thresholds that colour the hero BG, so band and headline can never disagree.
            // UnitDoubleKey values are stored in the user's DISPLAY units already — converting them
            // from mg/dL here would divide 10.0 mmol down to 0.55 and collapse the band.
            lowMark = preferences.get(UnitDoubleKey.OverviewLowMark),
            highMark = preferences.get(UnitDoubleKey.OverviewHighMark),
            decimals = if (profileFunction.getUnits() == GlucoseUnit.MGDL) 0 else 1
        )
    }

    // endregion

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        composeHome = null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    var task: Runnable? = null

    private fun scheduleUpdateGUI() {
        class UpdateRunnable : Runnable {

            override fun run() {
                refreshAll()
                task = null
            }
        }
        task?.let { handler.removeCallbacks(it) }
        task = UpdateRunnable()
        task?.let { handler.postDelayed(it, 500) }
    }

    @SuppressLint("SetTextI18n")
    private fun bolusIob(): IobTotal = iobCobCalculator.calculateIobFromBolus().round()
    private fun basalIob(): IobTotal = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
    private fun iobText(): String =
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob + basalIob().basaliob)

    private fun iobDialogText(): String =
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob + basalIob().basaliob) + "\n" +
            rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob().iob) + "\n" +
            rh.gs(app.aaps.core.ui.R.string.basal) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, basalIob().basaliob)

    private fun updateIobCob() {
        // Recent carb entries for the COB-tap undo sheet (last 6h, newest first). Off the UI thread here.
        recentCarbs = persistenceLayer.getCarbsFromTimeNotExpanded(dateUtil.now() - 6 * 60 * 60 * 1000L, false)
            .blockingGet()
            .filter { it.amount > 0 }
            .take(10)
            .map { ca ->
                HomeUiState.CarbEntry(
                    id = ca.id,
                    time = dateUtil.timeString(ca.timestamp),
                    grams = rh.gs(app.aaps.core.objects.R.string.format_carbs, ca.amount.toInt()),
                    timestamp = ca.timestamp,
                    amount = ca.amount.toInt()
                )
            }
        runOnUiThread {
            composeHome ?: return@runOnUiThread
            // Refresh the hero so its IOB/COB reflect a just-entered treatment within ~1s (this event
            // fires debounced after the iobCob recalc). Previously the hero only rebuilt in refreshAll()
            // — up to 60s / next CGM tick later — so a fresh carb entry looked like it hadn't
            // registered, tempting a duplicate entry.
            buildHomeState()
        }
    }

    @SuppressLint("SetTextI18n")
    fun popupBolusDialogIfRunning(onClick: Boolean) {
        // Check if bolus is in progress and show dialog if needed
        // Only show for manual bolus (not SMB) with progress > 0
        if (commandQueue.bolusInQueue()) {

            // Show bolus progress dialog automatically only for manual bolus with progress
            if (!BolusProgressData.bolusEnded && (!BolusProgressData.isSMB || onClick)) {
                activity?.let { activity ->
                    protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, UIRunnable {
                        if (isAdded)
                            uiInteraction.runBolusProgressDialog(childFragmentManager)
                    })
                }
            }
        }
    }
}

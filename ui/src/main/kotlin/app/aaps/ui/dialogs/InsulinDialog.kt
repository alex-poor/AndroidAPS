package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.dialogs.compose.HoldConfirmDialog
import app.aaps.ui.dialogs.compose.PumpReadyGate
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.InsulinInputs
import app.aaps.ui.dialogs.compose.InsulinSheet
import app.aaps.ui.dialogs.compose.InsulinSheetState
import com.google.common.base.Joiner
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

/**
 * Redesigned Insulin (careportal bolus) dialog. UI is Compose ([InsulinSheet]); [submit] runs the
 * SAME constraint + `OKDialog` confirmation + eating-soon TT + record / `commandQueue.bolus` path.
 */
class InsulinDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var ctx: Context
    @Inject lateinit var config: Config
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var loop: Loop
    @Inject lateinit var pumpReadyGate: PumpReadyGate

    private var queryingProtection = false
    private val disposable = CompositeDisposable()

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.BOTTOM)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)

        val pump = activePlugin.activePump
        val bolusStep = pump.pumpDescription.bolusStep
        val suspended = loop.runningMode.isPumpSuspended() || !pump.isInitialized()
        val state = InsulinSheetState(
            maxInsulin = constraintChecker.getMaxBolusAllowed().value(),
            bolusStep = bolusStep,
            decimals = if (bolusStep < 0.1) 2 else 1,
            quickIncrements = listOf(
                preferences.get(DoubleKey.OverviewInsulinButtonIncrement1),
                preferences.get(DoubleKey.OverviewInsulinButtonIncrement2),
                preferences.get(DoubleKey.OverviewInsulinButtonIncrement3)
            ),
            forceRecordOnly = config.AAPSCLIENT || suspended,
            suspendedWarning = suspended
        )
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { InsulinSheet(state = state, onSubmit = ::submit, onClose = { dismiss() }) } }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private fun submit(inputs: InsulinInputs) {
        val pumpDescription = activePlugin.activePump.pumpDescription
        val insulin = inputs.amount
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        val actions: LinkedList<String?> = LinkedList()
        val units = profileFunction.getUnits()
        val unitLabel = if (units == GlucoseUnit.MMOL) rh.gs(app.aaps.core.ui.R.string.mmol) else rh.gs(app.aaps.core.ui.R.string.mgdl)
        val recordOnlyChecked = inputs.recordOnly
        val eatingSoonChecked = inputs.eatingSoon

        if (insulinAfterConstraints > 0) {
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + decimalFormatter.toPumpSupportedBolus(insulinAfterConstraints, pumpDescription.bolusStep)
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor)
            )
            if (recordOnlyChecked)
                actions.add(rh.gs(app.aaps.core.ui.R.string.bolus_recorded_only).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
            if (abs(insulinAfterConstraints - insulin) > pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints))
                actions.add(rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, insulin, insulinAfterConstraints).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
        }
        val eatingSoonTTDuration = preferences.get(IntKey.OverviewEatingSoonDuration)
        val eatingSoonTT = preferences.get(UnitDoubleKey.OverviewEatingSoonTarget)
        if (eatingSoonChecked)
            actions.add(rh.gs(R.string.temp_target_short) + ": " + (decimalFormatter.to1Decimal(eatingSoonTT) + " " + unitLabel + " (" + rh.gs(app.aaps.core.ui.R.string.format_mins, eatingSoonTTDuration) + ")").formatColor(context, rh, app.aaps.core.ui.R.attr.tempTargetConfirmation))

        val timeOffset = inputs.timeOffsetMin
        val time = dateUtil.now() + T.mins(timeOffset.toLong()).msecs()
        if (timeOffset != 0)
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(time))
        val notes = inputs.notes
        if (notes.isNotEmpty())
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)

        if (insulinAfterConstraints > 0 || eatingSoonChecked) {
            activity?.let { activity ->
                val delivers = insulinAfterConstraints > 0 && !recordOnlyChecked
                // A dose that reaches the pump is pre-flighted BEFORE the hold-to-confirm. A record-only
                // entry (reconciling a dose already given by hand) never touches the pump.
                val confirm: (String, android.text.Spanned, Runnable) -> Unit =
                    if (delivers) { t2, m, r -> pumpReadyGate.runWhenPumpCanDeliver(activity) { HoldConfirmDialog.show(activity, t2, m, r) } }
                    else { t2, m, r -> OKDialog.showConfirmation(activity, t2, m, r) }
                confirm(rh.gs(app.aaps.core.ui.R.string.bolus), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), Runnable {
                    if (eatingSoonChecked) {
                        disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                            TT(
                                timestamp = System.currentTimeMillis(),
                                duration = TimeUnit.MINUTES.toMillis(eatingSoonTTDuration.toLong()),
                                reason = TT.Reason.EATING_SOON,
                                lowTarget = profileUtil.convertToMgdl(eatingSoonTT, profileFunction.getUnits()),
                                highTarget = profileUtil.convertToMgdl(eatingSoonTT, profileFunction.getUnits())
                            ),
                            action = Action.TT, source = Sources.InsulinDialog,
                            note = notes,
                            listValues = listOf(
                                ValueWithUnit.TETTReason(TT.Reason.EATING_SOON),
                                ValueWithUnit.fromGlucoseUnit(eatingSoonTT, units),
                                ValueWithUnit.Minute(eatingSoonTTDuration)
                            )
                        ).subscribe()
                    }
                    if (insulinAfterConstraints > 0) {
                        val detailedBolusInfo = DetailedBolusInfo()
                        detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
                        detailedBolusInfo.insulin = insulinAfterConstraints
                        detailedBolusInfo.context = context
                        detailedBolusInfo.notes = notes
                        detailedBolusInfo.timestamp = time
                        if (recordOnlyChecked) {
                            disposable += persistenceLayer.insertOrUpdateBolus(
                                bolus = detailedBolusInfo.createBolus(),
                                action = Action.BOLUS,
                                source = Sources.InsulinDialog,
                                note = rh.gs(app.aaps.core.ui.R.string.record) + if (notes.isNotEmpty()) ": $notes" else ""
                            ).subscribe()
                        } else {
                            uel.log(Action.BOLUS, Sources.InsulinDialog, notes, ValueWithUnit.Insulin(insulinAfterConstraints))
                            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                                override fun run() {
                                    if (!result.success)
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                }
                            })
                        }
                    }
                })
            }
        } else
            activity?.let { activity ->
                OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.bolus), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
            }
        dismiss()
    }

    override fun onResume() {
        super.onResume()
        if (!queryingProtection) {
            queryingProtection = true
            activity?.let { activity ->
                val cancelFail = {
                    queryingProtection = false
                    aapsLogger.debug(LTag.APS, "Dialog canceled on resume protection: ${this.javaClass.simpleName}")
                    ToastUtils.warnToast(ctx, R.string.dialog_canceled)
                    dismiss()
                }
                protectionCheck.queryProtection(activity, ProtectionCheck.Protection.BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}

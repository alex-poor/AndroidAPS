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
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionCheck.Protection.BOLUS
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.CarbTt
import app.aaps.ui.dialogs.compose.CarbsInputs
import app.aaps.ui.dialogs.compose.CarbsSheet
import app.aaps.ui.dialogs.compose.CarbsSheetState
import com.google.common.base.Joiner
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.ceil

/**
 * Redesigned Carbs dialog. UI is Compose ([CarbsSheet]); [submit] runs the SAME constraint +
 * `OKDialog` confirmation + temp-target / carbs-`commandQueue.bolus` path as
 * the legacy dialog. Event time is expressed as the minutes offset (as before); the notes + duration
 * (extended carbs) + TT presets + eat/bolus reminders are all preserved.
 */
class CarbsDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var ctx: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var dateUtil: DateUtil

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

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { CarbsSheet(state = buildState(), onSubmit = ::submit, onClose = { dismiss() }) } }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private fun buildState(): CarbsSheetState {
        val maxCarbs = constraintChecker.getMaxCarbsAllowed().value().toDouble()
        // Auto-select a Hypo temp target when BG is low (mirrors the legacy pre-check).
        var autoHypo = false
        iobCobCalculator.ads.actualBg()?.let { bgReading ->
            if (bgReading.recalculated < 72) {
                val activeTT = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now())
                val hypoTTDuration = preferences.get(IntKey.OverviewHypoDuration)
                var shouldAutoCheckHypo = true
                if (activeTT != null) {
                    val remainingDurationMin = ((activeTT.timestamp + activeTT.duration) - System.currentTimeMillis()) / 60000
                    if (activeTT.highTarget > Constants.NORMAL_TARGET_MGDL && remainingDurationMin > hypoTTDuration) shouldAutoCheckHypo = false
                }
                autoHypo = shouldAutoCheckHypo
            }
        }
        val showBolusReminder = preferences.get(BooleanKey.OverviewUseBolusReminder) &&
            (glucoseStatusProvider.glucoseStatusData?.let { it.glucose + 3 * it.delta < 70.0 } ?: false)
        return CarbsSheetState(
            maxCarbs = maxCarbs,
            quickIncrements = listOf(
                preferences.get(IntKey.OverviewCarbsButtonIncrement1),
                preferences.get(IntKey.OverviewCarbsButtonIncrement2),
                preferences.get(IntKey.OverviewCarbsButtonIncrement3)
            ),
            maxDurationHours = HardLimits.MAX_CARBS_DURATION_HOURS.toInt(),
            autoHypoTt = autoHypo,
            showBolusReminder = showBolusReminder
        )
    }

    private fun submit(inputs: CarbsInputs) {
        val carbs = inputs.carbs
        var carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(carbs, aapsLogger)).value()
        val units = profileUtil.units
        val cob = iobCobCalculator.ads.getLastAutosensData("carbsDialog", aapsLogger, dateUtil)?.cob ?: 0.0
        val activityTTDuration = preferences.get(IntKey.OverviewActivityDuration)
        val activityTT = preferences.get(UnitDoubleKey.OverviewActivityTarget)
        val eatingSoonTTDuration = preferences.get(IntKey.OverviewEatingSoonDuration)
        val eatingSoonTT = preferences.get(UnitDoubleKey.OverviewEatingSoonTarget)
        val hypoTTDuration = preferences.get(IntKey.OverviewHypoDuration)
        val hypoTT = preferences.get(UnitDoubleKey.OverviewHypoTarget)
        val actions: LinkedList<String?> = LinkedList()
        val unitLabel = if (units == GlucoseUnit.MMOL) rh.gs(app.aaps.core.ui.R.string.mmol) else rh.gs(app.aaps.core.ui.R.string.mgdl)
        val useAlarm = inputs.useAlarm
        val remindBolus = inputs.remindBolus

        val eventTimeOriginal = dateUtil.nowWithoutMilliseconds()
        val timeOffset = inputs.timeOffsetMin
        val eventTime = eventTimeOriginal + timeOffset.toLong() * 1000 * 60
        val eventTimeChanged = timeOffset != 0
        val duration = inputs.durationHours
        val notes = inputs.notes

        val activitySelected = inputs.tt == CarbTt.ACTIVITY
        if (activitySelected)
            actions.add(rh.gs(R.string.temp_target_short) + ": " + (decimalFormatter.to1Decimal(activityTT) + " " + unitLabel + " (" + rh.gs(app.aaps.core.ui.R.string.format_mins, activityTTDuration) + ")").formatColor(context, rh, app.aaps.core.ui.R.attr.tempTargetConfirmation))
        val eatingSoonSelected = inputs.tt == CarbTt.EATING_SOON
        if (eatingSoonSelected)
            actions.add(rh.gs(R.string.temp_target_short) + ": " + (decimalFormatter.to1Decimal(eatingSoonTT) + " " + unitLabel + " (" + rh.gs(app.aaps.core.ui.R.string.format_mins, eatingSoonTTDuration) + ")").formatColor(context, rh, app.aaps.core.ui.R.attr.tempTargetConfirmation))
        val hypoSelected = inputs.tt == CarbTt.HYPO
        if (hypoSelected)
            actions.add(rh.gs(R.string.temp_target_short) + ": " + (decimalFormatter.to1Decimal(hypoTT) + " " + unitLabel + " (" + rh.gs(app.aaps.core.ui.R.string.format_mins, hypoTTDuration) + ")").formatColor(context, rh, app.aaps.core.ui.R.attr.tempTargetConfirmation))

        if (useAlarm && carbs > 0 && timeOffset > 0)
            actions.add(rh.gs(app.aaps.core.ui.R.string.alarminxmin, timeOffset).formatColor(context, rh, app.aaps.core.ui.R.attr.infoColor))
        if (duration > 0)
            actions.add(rh.gs(app.aaps.core.ui.R.string.duration) + ": " + duration + rh.gs(app.aaps.core.interfaces.R.string.shorthour))
        if (carbsAfterConstraints > 0) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + "<font color='" + rh.gac(context, app.aaps.core.ui.R.attr.carbsColor) + "'>" + rh.gs(app.aaps.core.objects.R.string.format_carbs, carbsAfterConstraints) + "</font>")
            if (carbsAfterConstraints != carbs)
                actions.add("<font color='" + rh.gac(context, app.aaps.core.ui.R.attr.warningColor) + "'>" + rh.gs(R.string.carbs_constraint_applied) + "</font>")
        }
        if (carbsAfterConstraints < 0) {
            if (carbsAfterConstraints < -cob) carbsAfterConstraints = ceil(-cob).toInt()
            if (timeOffset != 0) carbsAfterConstraints = 0
            actions.add(rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + "<font color='" + rh.gac(context, app.aaps.core.ui.R.attr.warningColor) + "'>" + rh.gs(app.aaps.core.objects.R.string.format_carbs, carbsAfterConstraints) + "</font>")
            if (carbsAfterConstraints != carbs)
                actions.add("<font color='" + rh.gac(context, app.aaps.core.ui.R.attr.warningColor) + "'>" + rh.gs(R.string.carbs_constraint_applied) + "</font>")
        }
        if (notes.isNotEmpty())
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)
        if (eventTimeChanged)
            actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))

        if (carbsAfterConstraints != 0 || activitySelected || eatingSoonSelected || hypoSelected) {
            activity?.let { activity ->
                OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.carbs), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                    val selectedTTDuration = when {
                        activitySelected   -> activityTTDuration
                        eatingSoonSelected -> eatingSoonTTDuration
                        hypoSelected       -> hypoTTDuration
                        else               -> 0
                    }
                    val selectedTT = when {
                        activitySelected   -> activityTT
                        eatingSoonSelected -> eatingSoonTT
                        hypoSelected       -> hypoTT
                        else               -> 0.0
                    }
                    val reason = when {
                        activitySelected   -> TT.Reason.ACTIVITY
                        eatingSoonSelected -> TT.Reason.EATING_SOON
                        hypoSelected       -> TT.Reason.HYPOGLYCEMIA
                        else               -> TT.Reason.CUSTOM
                    }
                    if (reason != TT.Reason.CUSTOM)
                        disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                            temporaryTarget = TT(
                                timestamp = System.currentTimeMillis(),
                                duration = TimeUnit.MINUTES.toMillis(selectedTTDuration.toLong()),
                                reason = reason,
                                lowTarget = profileUtil.convertToMgdl(selectedTT, profileUtil.units),
                                highTarget = profileUtil.convertToMgdl(selectedTT, profileUtil.units)
                            ),
                            action = Action.TT,
                            source = Sources.CarbDialog,
                            note = null,
                            listValues = listOf(
                                ValueWithUnit.TETTReason(reason),
                                ValueWithUnit.fromGlucoseUnit(selectedTT, units),
                                ValueWithUnit.Minute(selectedTTDuration)
                            )
                        ).subscribe()
                    if (carbsAfterConstraints != 0) {
                        val detailedBolusInfo = DetailedBolusInfo().also {
                            it.eventType = TE.Type.CORRECTION_BOLUS
                            it.carbs = carbsAfterConstraints.toDouble()
                            it.context = context
                            it.notes = notes
                            it.carbsDuration = T.hours(duration.toLong()).msecs()
                            it.carbsTimestamp = eventTime
                        }
                        uel.log(
                            action = if (duration == 0) Action.CARBS else Action.EXTENDED_CARBS, source = Sources.CarbDialog,
                            note = notes,
                            listValues = listOfNotNull(
                                ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged },
                                ValueWithUnit.Gram(carbsAfterConstraints),
                                ValueWithUnit.Minute(timeOffset).takeIf { timeOffset != 0 },
                                ValueWithUnit.Hour(duration).takeIf { duration != 0 }
                            )
                        )
                        commandQueue.bolus(detailedBolusInfo, object : Callback() {
                            override fun run() {
                                if (!result.success)
                                    uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                            }
                        })
                    }
                }, null)
            }
        } else
            activity?.let { activity ->
                OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.carbs), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
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
                protectionCheck.queryProtection(activity, BOLUS, { queryingProtection = false }, cancelFail, cancelFail)
            }
        }
    }
}

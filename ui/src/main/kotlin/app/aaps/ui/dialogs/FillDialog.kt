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
import androidx.fragment.app.FragmentManager
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.dialogs.compose.HoldConfirmDialog
import app.aaps.ui.dialogs.compose.PumpReadyGate
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.FillInputs
import app.aaps.ui.dialogs.compose.FillSheet
import app.aaps.ui.dialogs.compose.FillSheetState
import com.google.common.base.Joiner
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.abs

/**
 * Redesigned Prime / Fill dialog. UI is Compose ([FillSheet]); [submit] runs the SAME constraint +
 * `OKDialog` confirmation + prime-`commandQueue.bolus` + site/insulin-change persistence + site-rotation
 * path as the legacy dialog. Event time is `dateUtil.now()` (the legacy sheet had no time offset input).
 */
class FillDialog(val fm: FragmentManager) : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var ctx: Context
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var decimalFormatter: DecimalFormatter
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil
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

        val bolusStep = activePlugin.activePump.pumpDescription.bolusStep
        val state = FillSheetState(
            maxInsulin = constraintChecker.getMaxBolusAllowed().value(),
            insulinStep = bolusStep,
            insulinDecimals = if (bolusStep < 0.1) 2 else 1,
            presets = listOf(
                preferences.get(DoubleKey.ActionsFillButton1),
                preferences.get(DoubleKey.ActionsFillButton2),
                preferences.get(DoubleKey.ActionsFillButton3)
            ).filter { it > 0 },
            showNotes = preferences.get(BooleanKey.OverviewShowNotesInDialogs)
        )
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { FillSheet(state = state, onSubmit = ::submit, onClose = { dismiss() }) } }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private fun submit(inputs: FillInputs) {
        val insulin = inputs.insulin
        val actions: LinkedList<String?> = LinkedList()

        // Back-date the RECORD only. `requestPrimeBolus` below is untouched and still delivers now --
        // insulin cannot be given retroactively, and letting the offset reach the bolus would put a
        // fictional delivery time into IOB.
        var eventTime = dateUtil.now() - T.mins(inputs.minutesAgo.toLong()).msecs()
        eventTime -= eventTime % 1000

        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        if (insulinAfterConstraints > 0) {
            actions.add(rh.gs(R.string.fill_warning))
            actions.add("")
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + decimalFormatter.toPumpSupportedBolus(insulinAfterConstraints, activePlugin.activePump.pumpDescription.bolusStep)
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.insulinButtonColor)
            )
            if (abs(insulinAfterConstraints - insulin) > 0.01)
                actions.add(
                    rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, insulin, insulinAfterConstraints).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
                )
        }
        val siteChange = inputs.siteChange
        if (siteChange)
            actions.add(rh.gs(R.string.record_pump_site_change).formatColor(context, rh, app.aaps.core.ui.R.attr.actionsConfirmColor))
        val insulinChange = inputs.insulinChange
        if (insulinChange)
            actions.add(rh.gs(R.string.record_insulin_cartridge_change).formatColor(context, rh, app.aaps.core.ui.R.attr.actionsConfirmColor))
        val notes: String = inputs.notes
        if (notes.isNotEmpty())
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)
        // Surface the back-dated time in the confirmation. A silently back-dated record would be
        // worse than none: site age feeds the post-change analysis and the HovorkaMPC site guard.
        if (inputs.minutesAgo > 0)
            actions.add(
                (rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
            )

        if (insulinAfterConstraints > 0 || siteChange || insulinChange) {
            activity?.let { activity ->
                // A prime is insulin too, so pre-flight it whenever this dialog will push one. Logging a
                // site/cartridge change on its own reaches nothing, so that path stays ungated.
                val confirm: (String, android.text.Spanned, Runnable) -> Unit =
                    if (insulinAfterConstraints > 0) { t2, m, r -> pumpReadyGate.runWhenPumpCanDeliver(activity) { HoldConfirmDialog.show(activity, t2, m, r) } }
                    else { t2, m, r -> OKDialog.showConfirmation(activity, t2, m, r) }
                confirm(rh.gs(app.aaps.core.ui.R.string.prime_fill), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), Runnable {
                    if (insulinAfterConstraints > 0) {
                        uel.log(
                            action = Action.PRIME_BOLUS, source = Sources.FillDialog,
                            note = notes,
                            value = ValueWithUnit.Insulin(insulinAfterConstraints)
                        )
                        requestPrimeBolus(insulinAfterConstraints, notes)
                    }
                    if (siteChange) {
                        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                            therapyEvent = TE(
                                timestamp = eventTime,
                                type = TE.Type.CANNULA_CHANGE,
                                note = notes,
                                glucoseUnit = GlucoseUnit.MGDL
                            ),
                            action = Action.SITE_CHANGE, source = Sources.FillDialog,
                            note = notes,
                            listValues = listOf(
                                ValueWithUnit.TEType(TE.Type.CANNULA_CHANGE)
                            )
                        ).subscribe()
                    }
                    if (insulinChange)
                    // add a second for case of both checked
                        disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                            therapyEvent = TE(
                                timestamp = eventTime + 1000,
                                type = TE.Type.INSULIN_CHANGE,
                                note = notes,
                                glucoseUnit = GlucoseUnit.MGDL
                            ),
                            action = Action.RESERVOIR_CHANGE, source = Sources.FillDialog,
                            note = notes,
                            listValues = listOf(
                                ValueWithUnit.TEType(TE.Type.INSULIN_CHANGE)
                            )
                        ).subscribe()
                })
            }
        } else {
            activity?.let { activity ->
                OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.prime_fill), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
            }
        }
        dismiss()
    }

    private fun requestPrimeBolus(insulin: Double, notes: String) {
        val detailedBolusInfo = DetailedBolusInfo()
        detailedBolusInfo.insulin = insulin
        detailedBolusInfo.context = context
        detailedBolusInfo.bolusType = BS.Type.PRIMING
        detailedBolusInfo.notes = notes
        commandQueue.bolus(detailedBolusInfo, object : Callback() {
            override fun run() {
                if (!result.success) {
                    uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                }
            }
        })
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

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
import app.aaps.core.data.model.TE
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusStepSize
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.ui.dialogs.compose.HoldConfirmDialog
import app.aaps.ui.dialogs.compose.PumpReadyGate
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.TreatmentSheet
import app.aaps.ui.dialogs.compose.TreatmentSheetState
import com.google.common.base.Joiner
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.abs

/**
 * Redesigned Treatment (Bolus + Carbs) dialog. UI is Compose ([TreatmentSheet]); [submit] runs the
 * SAME constraint + `OKDialog` confirmation + record / `commandQueue.bolus` path as before. Only
 * insulin + carbs are used (the legacy dialog ignored event-time/notes); follower mode records only.
 */
class TreatmentDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var ctx: Context
    @Inject lateinit var config: Config
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var decimalFormatter: DecimalFormatter
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
        val state = TreatmentSheetState(
            maxInsulin = constraintChecker.getMaxBolusAllowed().value(),
            insulinStep = bolusStep,
            insulinDecimals = if (bolusStep < 0.1) 2 else 1,
            maxCarbs = constraintChecker.getMaxCarbsAllowed().value().toDouble(),
            recordOnly = config.AAPSCLIENT
        )
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { TreatmentSheet(state = state, onDeliver = ::submit, onClose = { dismiss() }) } }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private fun submit(insulin: Double, carbs: Double) {
        val pumpDescription = activePlugin.activePump.pumpDescription
        val carbsInt = carbs.toInt()
        val recordOnlyChecked = config.AAPSCLIENT
        val actions: LinkedList<String?> = LinkedList()
        val insulinAfterConstraints = constraintChecker.applyBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        val carbsAfterConstraints = constraintChecker.applyCarbsConstraints(ConstraintObject(carbsInt, aapsLogger)).value()

        if (insulinAfterConstraints > 0) {
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + decimalFormatter.toPumpSupportedBolus(insulinAfterConstraints, pumpDescription.bolusStep)
                    .formatColor(context, rh, app.aaps.core.ui.R.attr.bolusColor)
            )
            if (recordOnlyChecked)
                actions.add(rh.gs(app.aaps.core.ui.R.string.bolus_recorded_only).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
            if (abs(insulinAfterConstraints - insulin) > pumpDescription.pumpType.determineCorrectBolusStepSize(insulinAfterConstraints))
                actions.add(
                    rh.gs(app.aaps.core.ui.R.string.bolus_constraint_applied_warn, insulin, insulinAfterConstraints).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor)
                )
        }
        if (carbsAfterConstraints > 0) {
            actions.add(
                rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + rh.gs(app.aaps.core.objects.R.string.format_carbs, carbsAfterConstraints).formatColor(
                    context, rh, app.aaps.core.ui.R.attr.carbsColor
                )
            )
            if (carbsAfterConstraints != carbsInt)
                actions.add(rh.gs(R.string.carbs_constraint_applied).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
        }
        if (insulinAfterConstraints > 0 || carbsAfterConstraints > 0) {
            activity?.let { activity ->
                // Delivering insulin uses the same press-and-hold as the Bolus Wizard; carbs-only and
                // record-only stay a plain tap, since nothing reaches the pump.
                val delivers = insulinAfterConstraints > 0 && !recordOnlyChecked
                // A dose that reaches the pump is pre-flighted BEFORE the hold-to-confirm, so nobody is
                // asked to commit to insulin the pump is going to refuse. Carbs-only and record-only
                // entries never touch the pump, so they stay a plain confirmation.
                val confirm: (String, android.text.Spanned, Runnable) -> Unit =
                    if (delivers) { t2, m, r -> pumpReadyGate.runWhenPumpCanDeliver(activity) { HoldConfirmDialog.show(activity, t2, m, r) } }
                    else { t2, m, r -> OKDialog.showConfirmation(activity, t2, m, r) }
                confirm(rh.gs(app.aaps.core.ui.R.string.overview_treatment_label), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), Runnable {
                    val action = when {
                        insulinAfterConstraints.equals(0.0) -> Action.CARBS
                        carbsAfterConstraints == 0          -> Action.BOLUS
                        else                                -> Action.TREATMENT
                    }
                    val detailedBolusInfo = DetailedBolusInfo()
                    if (insulinAfterConstraints == 0.0) detailedBolusInfo.eventType = TE.Type.CARBS_CORRECTION
                    if (carbsAfterConstraints == 0) detailedBolusInfo.eventType = TE.Type.CORRECTION_BOLUS
                    detailedBolusInfo.insulin = insulinAfterConstraints
                    detailedBolusInfo.carbs = carbsAfterConstraints.toDouble()
                    detailedBolusInfo.context = context
                    if (recordOnlyChecked) {
                        if (detailedBolusInfo.insulin > 0)
                            disposable += persistenceLayer.insertOrUpdateBolus(
                                bolus = detailedBolusInfo.createBolus(),
                                action = action,
                                source = Sources.TreatmentDialog,
                                note = if (insulinAfterConstraints != 0.0) rh.gs(app.aaps.core.ui.R.string.record) else ""
                            ).subscribe()
                        if (detailedBolusInfo.carbs > 0)
                            disposable += persistenceLayer.insertOrUpdateCarbs(
                                carbs = detailedBolusInfo.createCarbs(),
                                action = action,
                                source = Sources.TreatmentDialog,
                                note = if (carbsAfterConstraints != 0) rh.gs(app.aaps.core.ui.R.string.record) else ""
                            ).subscribe()
                    } else {
                        if (detailedBolusInfo.insulin > 0) {
                            uel.log(
                                action = action,
                                source = Sources.TreatmentDialog,
                                listValues = listOf(
                                    ValueWithUnit.Insulin(insulinAfterConstraints),
                                    ValueWithUnit.Gram(carbsAfterConstraints).takeIf { carbsAfterConstraints != 0 }
                                ).filterNotNull()
                            )
                            commandQueue.bolus(detailedBolusInfo, object : Callback() {
                                override fun run() {
                                    if (!result.success) {
                                        uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                                    }
                                }
                            })
                        } else {
                            if (detailedBolusInfo.carbs > 0)
                                disposable += persistenceLayer.insertOrUpdateCarbs(
                                    detailedBolusInfo.createCarbs(),
                                    action = action,
                                    source = Sources.TreatmentDialog
                                ).subscribe()
                        }
                    }
                })
            }
        } else
            activity?.let { activity ->
                OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.overview_treatment_label), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
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

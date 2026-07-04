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
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.extensions.formatColor
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.ExtendedBolusInputs
import app.aaps.ui.dialogs.compose.ExtendedBolusSheet
import app.aaps.ui.dialogs.compose.ExtendedBolusSheetState
import com.google.common.base.Joiner
import dagger.android.support.DaggerDialogFragment
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.abs

/**
 * Redesigned Extended Bolus dialog. UI is Compose ([ExtendedBolusSheet]); [submit] runs the SAME
 * constraint + `OKDialog` confirmation + `commandQueue.extendedBolus` path as before.
 */
class ExtendedBolusDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var ctx: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var uiInteraction: UiInteraction

    private var queryingProtection = false

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

        val pumpDescription = activePlugin.activePump.pumpDescription
        val extendedStep = pumpDescription.extendedBolusStep
        val state = ExtendedBolusSheetState(
            maxInsulin = constraintChecker.getMaxExtendedBolusAllowed().value(),
            insulinStep = extendedStep,
            insulinDecimals = 2,
            durationStep = pumpDescription.extendedBolusDurationStep,
            maxDuration = pumpDescription.extendedBolusMaxDuration
        )
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { ExtendedBolusSheet(state = state, onSubmit = ::submit, onClose = { dismiss() }) } }
        }
    }

    private fun submit(inputs: ExtendedBolusInputs) {
        val insulin = inputs.insulin
        val durationInMinutes = inputs.durationMin
        val actions: LinkedList<String> = LinkedList()
        val insulinAfterConstraint = constraintChecker.applyExtendedBolusConstraints(ConstraintObject(insulin, aapsLogger)).value()
        actions.add(rh.gs(app.aaps.core.ui.R.string.format_insulin_units, insulinAfterConstraint))
        actions.add(rh.gs(app.aaps.core.ui.R.string.duration) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, durationInMinutes))
        if (abs(insulinAfterConstraint - insulin) > 0.01)
            actions.add(rh.gs(app.aaps.core.ui.R.string.constraint_applied).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))

        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.extended_bolus), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                uel.log(
                    action = Action.EXTENDED_BOLUS, source = Sources.ExtendedBolusDialog,
                    listValues = listOf(
                        ValueWithUnit.Insulin(insulinAfterConstraint),
                        ValueWithUnit.Minute(durationInMinutes)
                    )
                )
                commandQueue.extendedBolus(insulinAfterConstraint, durationInMinutes, object : Callback() {
                    override fun run() {
                        if (!result.success) {
                            uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.treatmentdeliveryerror), app.aaps.core.ui.R.raw.boluserror)
                        }
                    }
                })
            }, null)
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

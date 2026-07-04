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
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.pump.PumpSync
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
import app.aaps.ui.dialogs.compose.TempBasalInputs
import app.aaps.ui.dialogs.compose.TempBasalSheet
import app.aaps.ui.dialogs.compose.TempBasalSheetState
import com.google.common.base.Joiner
import dagger.android.support.DaggerDialogFragment
import java.util.LinkedList
import javax.inject.Inject
import kotlin.math.abs

/**
 * Redesigned Temp Basal dialog. UI is Compose ([TempBasalSheet]); [submit] runs the SAME constraint +
 * `OKDialog` confirmation + `commandQueue.tempBasalPercent` / `tempBasalAbsolute` path as the legacy
 * dialog. The pump decides which modes are offered (percent / absolute).
 */
class TempBasalDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var ctx: Context
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

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { TempBasalSheet(state = buildState(), onSubmit = ::submit, onClose = { dismiss() }) } }
        }
    }

    private fun buildState(): TempBasalSheetState {
        val pumpDescription = activePlugin.activePump.pumpDescription
        val profile = profileFunction.getProfile()
        val isPercentPump = pumpDescription.tempBasalStyle and PumpDescription.PERCENT == PumpDescription.PERCENT
        val isAbsolutePump = pumpDescription.tempBasalStyle and PumpDescription.ABSOLUTE == PumpDescription.ABSOLUTE
        val tempDurationStep = pumpDescription.tempDurationStep.toDouble()
        return TempBasalSheetState(
            percentAllowed = isPercentPump,
            absoluteAllowed = isAbsolutePump,
            defaultIsPercent = isPercentPump,
            percentDefault = 100.0,
            percentMax = pumpDescription.maxTempPercent.toDouble(),
            percentStep = pumpDescription.tempPercentStep.toDouble(),
            absoluteDefault = profile?.getBasal() ?: 0.0,
            absoluteMax = pumpDescription.maxTempAbsolute,
            absoluteStep = pumpDescription.tempAbsoluteStep,
            absoluteDecimals = 2,
            durationDefault = tempDurationStep,
            durationMax = pumpDescription.tempMaxDuration.toDouble(),
            durationStep = tempDurationStep
        )
    }

    private fun submit(inputs: TempBasalInputs) {
        var percent = 0
        var absolute = 0.0
        val durationInMinutes = inputs.durationMin
        val profile = profileFunction.getProfile() ?: return
        val isPercentPump = inputs.isPercent
        val actions: LinkedList<String> = LinkedList()
        if (isPercentPump) {
            val basalPercentInput = inputs.value.toInt()
            percent = constraintChecker.applyBasalPercentConstraints(ConstraintObject(basalPercentInput, aapsLogger), profile).value()
            actions.add(rh.gs(app.aaps.core.ui.R.string.tempbasal_label) + ": $percent%")
            actions.add(rh.gs(app.aaps.core.ui.R.string.duration) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, durationInMinutes))
            if (percent != basalPercentInput) actions.add(rh.gs(app.aaps.core.ui.R.string.constraint_applied))
        } else {
            val basalAbsoluteInput = inputs.value
            absolute = constraintChecker.applyBasalConstraints(ConstraintObject(basalAbsoluteInput, aapsLogger), profile).value()
            actions.add(rh.gs(app.aaps.core.ui.R.string.tempbasal_label) + ": " + rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, absolute))
            actions.add(rh.gs(app.aaps.core.ui.R.string.duration) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, durationInMinutes))
            if (abs(absolute - basalAbsoluteInput) > 0.01)
                actions.add(rh.gs(app.aaps.core.ui.R.string.constraint_applied).formatColor(context, rh, app.aaps.core.ui.R.attr.warningColor))
        }
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.tempbasal_label), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                val callback: Callback = object : Callback() {
                    override fun run() {
                        if (!result.success) {
                            uiInteraction.runAlarm(result.comment, rh.gs(app.aaps.core.ui.R.string.temp_basal_delivery_error), app.aaps.core.ui.R.raw.boluserror)
                        }
                    }
                }
                if (isPercentPump) {
                    uel.log(
                        action = Action.TEMP_BASAL, source = Sources.TempBasalDialog,
                        listValues = listOf(
                            ValueWithUnit.Percent(percent),
                            ValueWithUnit.Minute(durationInMinutes)
                        )
                    )
                    commandQueue.tempBasalPercent(percent, durationInMinutes, true, profile, PumpSync.TemporaryBasalType.NORMAL, callback)
                } else {
                    uel.log(
                        action = Action.TEMP_BASAL, source = Sources.TempBasalDialog,
                        listValues = listOf(
                            ValueWithUnit.Insulin(absolute),
                            ValueWithUnit.Minute(durationInMinutes)
                        )
                    )
                    commandQueue.tempBasalAbsolute(absolute, durationInMinutes, true, profile, PumpSync.TemporaryBasalType.NORMAL, callback)
                }
            })
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

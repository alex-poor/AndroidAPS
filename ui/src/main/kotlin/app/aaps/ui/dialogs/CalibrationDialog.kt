package app.aaps.ui.dialogs

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
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sync.XDripBroadcast
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.dialogs.compose.CalibrationSheet
import app.aaps.ui.dialogs.compose.CalibrationSheetState
import com.google.common.base.Joiner
import dagger.android.support.DaggerDialogFragment
import java.util.LinkedList
import javax.inject.Inject

/** Redesigned Calibration dialog. UI is Compose ([CalibrationSheet]); the send path is unchanged. */
class CalibrationDialog : DaggerDialogFragment() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var xDripBroadcast: XDripBroadcast
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider

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

        val mmol = profileUtil.units == GlucoseUnit.MMOL
        val bg = profileUtil.fromMgdlToUnits(glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0)
        val state = CalibrationSheetState(
            initial = bg,
            min = if (mmol) 2.0 else 36.0,
            max = if (mmol) 30.0 else 500.0,
            step = if (mmol) 0.1 else 1.0,
            decimals = if (mmol) 1 else 0,
            unitLabel = if (mmol) rh.gs(app.aaps.core.ui.R.string.mmol) else rh.gs(app.aaps.core.ui.R.string.mgdl)
        )
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { CalibrationSheet(state = state, onSend = ::submit, onClose = { dismiss() }) } }
        }
    }

    private fun submit(bg: Double) {
        val units = profileUtil.units
        val unitLabel = if (units == GlucoseUnit.MMOL) rh.gs(app.aaps.core.ui.R.string.mmol) else rh.gs(app.aaps.core.ui.R.string.mgdl)
        val actions: LinkedList<String?> = LinkedList()
        actions.add(rh.gs(app.aaps.core.ui.R.string.bg_label) + ": " + profileUtil.stringInCurrentUnitsDetect(bg) + " " + unitLabel)
        if (bg > 0) {
            activity?.let { activity ->
                OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.calibration), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                    uel.log(action = Action.CALIBRATION, source = Sources.CalibrationDialog, value = ValueWithUnit.fromGlucoseUnit(bg, units))
                    xDripBroadcast.sendCalibration(bg)
                })
            }
        } else
            activity?.let { activity ->
                OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.calibration), rh.gs(app.aaps.core.ui.R.string.no_action_selected))
            }
        dismiss()
    }
}

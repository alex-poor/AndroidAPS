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
import app.aaps.core.data.model.TT
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.TempTargetSheet
import app.aaps.ui.dialogs.compose.TempTargetSheetState
import app.aaps.ui.dialogs.compose.TtPreset
import app.aaps.ui.dialogs.compose.TtReason
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Redesigned Temp target sheet. UI is Compose ([TempTargetSheet]); starting/cancelling a temp target
 * reuses the SAME `persistenceLayer.insertAndCancelCurrentTemporaryTarget` /
 * `cancelCurrentTemporaryTargetIfAny` path behind an `OKDialog` confirmation. DI + `runTempTargetDialog`
 * routing unchanged.
 */
class TempTargetDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var ctx: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var protectionCheck: ProtectionCheck

    private val disposable = CompositeDisposable()
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
        dialog?.setCanceledOnTouchOutside(true)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    TempTargetSheet(
                        state = buildState(),
                        onStart = ::start,
                        onCancelActive = ::cancelActive,
                        onClose = { dismiss() }
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private val mmol get() = profileFunction.getUnits() == GlucoseUnit.MMOL

    private fun buildState(): TempTargetSheetState {
        val decimals = if (mmol) 1 else 0
        fun fmt(v: Double) = String.format(Locale.getDefault(), "%.${decimals}f", v)
        fun preset(reason: TtReason, label: String, target: Double, dur: Int) =
            TtPreset(reason, label, target, dur, "${fmt(target)} ${if (mmol) "mmol/L" else "mg/dL"}", "$dur min")

        return TempTargetSheetState(
            presets = listOf(
                preset(TtReason.EATING_SOON, rh.gs(app.aaps.core.ui.R.string.eatingsoon), preferences.get(UnitDoubleKey.OverviewEatingSoonTarget), preferences.get(IntKey.OverviewEatingSoonDuration)),
                preset(TtReason.ACTIVITY, rh.gs(app.aaps.core.ui.R.string.activity), preferences.get(UnitDoubleKey.OverviewActivityTarget), preferences.get(IntKey.OverviewActivityDuration)),
                preset(TtReason.HYPO, rh.gs(app.aaps.core.ui.R.string.hypo), preferences.get(UnitDoubleKey.OverviewHypoTarget), preferences.get(IntKey.OverviewHypoDuration))
            ),
            initialTarget = preferences.get(UnitDoubleKey.OverviewEatingSoonTarget),
            initialDuration = preferences.get(IntKey.OverviewEatingSoonDuration),
            unitStep = if (mmol) 0.1 else 1.0,
            unitLabel = if (mmol) "mmol/L" else "mg/dL",
            targetMin = if (mmol) 4.0 else 72.0,
            targetMax = if (mmol) 15.0 else 270.0,
            durationStep = 5,
            decimals = decimals,
            hasActive = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()) != null
        )
    }

    private fun start(target: Double, durationMin: Int, reason: TtReason) {
        if (target <= 0.0 || durationMin <= 0) return
        val activity = activity ?: return
        val units = profileFunction.getUnits()
        val unitLabel = if (mmol) "mmol/L" else "mg/dL"
        val summary = rh.gs(app.aaps.core.ui.R.string.target_label) + ": " + profileUtil.stringInCurrentUnitsDetect(target) + " " + unitLabel +
            "\n" + rh.gs(app.aaps.core.ui.R.string.duration) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, durationMin)
        OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.temporary_target), summary, {
            val ttReason = when (reason) {
                TtReason.EATING_SOON -> TT.Reason.EATING_SOON
                TtReason.ACTIVITY    -> TT.Reason.ACTIVITY
                TtReason.HYPO        -> TT.Reason.HYPOGLYCEMIA
                TtReason.CUSTOM      -> TT.Reason.CUSTOM
            }
            disposable += persistenceLayer.insertAndCancelCurrentTemporaryTarget(
                TT(
                    timestamp = dateUtil.now(),
                    duration = TimeUnit.MINUTES.toMillis(durationMin.toLong()),
                    reason = ttReason,
                    lowTarget = profileUtil.convertToMgdl(target, units),
                    highTarget = profileUtil.convertToMgdl(target, units)
                ),
                action = Action.TT,
                source = Sources.TTDialog,
                note = null,
                listValues = listOf(
                    ValueWithUnit.TETTReason(ttReason),
                    ValueWithUnit.fromGlucoseUnit(target, units),
                    ValueWithUnit.Minute(durationMin)
                )
            ).subscribe()
            if (durationMin == 10) preferences.put(BooleanNonKey.ObjectivesTempTargetUsed, true)
        })
        dismiss()
    }

    private fun cancelActive() {
        val activity = activity ?: return
        OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.temporary_target), rh.gs(app.aaps.core.ui.R.string.stoptemptarget), {
            disposable += persistenceLayer.cancelCurrentTemporaryTargetIfAny(
                timestamp = dateUtil.now(),
                action = Action.TT,
                source = Sources.TTDialog,
                note = null,
                listValues = listOf()
            ).subscribe()
        })
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

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
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.ProfileEffect
import app.aaps.ui.dialogs.compose.ProfileSwitchSheet
import app.aaps.ui.dialogs.compose.ProfileSwitchSheetState
import dagger.android.support.DaggerDialogFragment
import java.util.Locale
import javax.inject.Inject

/**
 * Redesigned Profile switch sheet. UI is Compose ([ProfileSwitchSheet]); applying reuses the SAME
 * validity check + confirmation + `profileFunction.createProfileSwitch(...)` path as the legacy
 * dialog. The "effect at N%" preview is illustrative (basal ×N%, ISF/IC ÷N%). DI +
 * `runProfileSwitchDialog` routing unchanged.
 */
class ProfileSwitchDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var ctx: Context
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var config: Config
    @Inject lateinit var hardLimits: HardLimits
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var protectionCheck: ProtectionCheck

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
                    ProfileSwitchSheet(
                        state = buildState(),
                        computeEffect = ::computeEffect,
                        onApply = ::apply,
                        onClose = { dismiss() }
                    )
                }
            }
        }
    }

    private fun profileNames(): List<String> {
        val store = activePlugin.activeProfileSource.profile ?: return emptyList()
        return store.getProfileList().mapNotNull { name ->
            name.toString().takeIf { store.getSpecificProfile(it) != null }
        }
    }

    private fun buildState(): ProfileSwitchSheetState {
        val names = profileNames()
        val selected = names.firstOrNull { it == profileFunction.getOriginalProfileName() } ?: names.firstOrNull().orEmpty()
        return ProfileSwitchSheetState(profiles = names, selectedProfile = selected, initialPercentage = 100, initialTimeshift = 0)
    }

    private fun computeEffect(profileName: String, percent: Int): ProfileEffect {
        val store = activePlugin.activeProfileSource.profile ?: return ProfileEffect()
        val pure = store.getSpecificProfile(profileName) ?: return ProfileEffect()
        val profile = ProfileSealed.Pure(pure, activePlugin)
        val factor = percent / 100.0
        val units = profileFunction.getUnits()
        val basal = profile.getBasal()
        val isf = profileUtil.fromMgdlToUnits(profile.getProfileIsfMgdl(), units)
        val ic = profile.getIc()
        fun n(v: Double, dec: Int) = String.format(Locale.getDefault(), "%.${dec}f", v)
        val isfDec = if (units == app.aaps.core.data.model.GlucoseUnit.MMOL) 1 else 0
        return ProfileEffect(
            basalBefore = n(basal, 2), basalAfter = n(basal * factor, 2),
            isfBefore = n(isf, isfDec), isfAfter = n(isf / factor, isfDec),
            icBefore = n(ic, 1), icAfter = n(ic / factor, 1)
        )
    }

    private fun apply(profileName: String, percent: Int, timeShift: Int, duration: Int) {
        val activity = activity ?: return
        val store = activePlugin.activeProfileSource.profile ?: return
        val ps = profileFunction.buildProfileSwitch(store, profileName, duration, percent, timeShift, dateUtil.now()) ?: return
        val validity = ProfileSealed.PS(ps, activePlugin).isValid(rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch), activePlugin.activePump, config, rh, rxBus, hardLimits, false)
        if (!validity.isValid) {
            OKDialog.show(activity, rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch), validity.reasons.joinToString("\n"))
            return
        }
        val summary = buildString {
            append(rh.gs(app.aaps.core.ui.R.string.profile)).append(": ").append(profileName)
            if (percent != 100) append("\n").append(rh.gs(app.aaps.core.ui.R.string.percent)).append(": ").append(percent).append("%")
            if (timeShift != 0) append("\n").append(rh.gs(R.string.timeshift_label)).append(": ").append(rh.gs(app.aaps.core.ui.R.string.format_hours, timeShift.toDouble()))
            if (duration > 0) append("\n").append(rh.gs(app.aaps.core.ui.R.string.duration)).append(": ").append(rh.gs(app.aaps.core.ui.R.string.format_mins, duration))
        }
        OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch), summary, {
            if (profileFunction.createProfileSwitch(
                    profileStore = store,
                    profileName = profileName,
                    durationInMinutes = duration,
                    percentage = percent,
                    timeShiftInHours = timeShift,
                    timestamp = dateUtil.now(),
                    action = Action.PROFILE_SWITCH,
                    source = Sources.ProfileSwitchDialog,
                    note = "",
                    listValues = listOf(
                        ValueWithUnit.SimpleString(profileName),
                        ValueWithUnit.Percent(percent),
                        ValueWithUnit.Hour(timeShift).takeIf { timeShift != 0 },
                        ValueWithUnit.Minute(duration).takeIf { duration != 0 }
                    ).filterNotNull()
                )
            ) {
                if (percent == 90 && duration == 10) preferences.put(BooleanNonKey.ObjectivesProfileSwitchUsed, true)
            }
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

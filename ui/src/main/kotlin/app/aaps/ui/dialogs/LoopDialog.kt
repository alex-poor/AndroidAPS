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
import app.aaps.core.compose.theme.AapsTone
import app.aaps.core.data.model.RM
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.LoopActionId
import app.aaps.ui.dialogs.compose.LoopModeOption
import app.aaps.ui.dialogs.compose.LoopSheet
import app.aaps.ui.dialogs.compose.LoopSheetState
import dagger.android.support.DaggerDialogFragment
import javax.inject.Inject

/**
 * Redesigned Loop control sheet. UI is Compose ([LoopSheet]); all mode changes reuse
 * `loop.handleRunningModeChange(...)` behind the SAME `OKDialog` confirmation as before (gated on
 * `loop.allowedNextModes()`). DI + `runLoopDialog` routing unchanged.
 */
class LoopDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var ctx: Context
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var loop: Loop
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var config: Config
    @Inject lateinit var translator: Translator

    private var queryingProtection = false
    private var showOkCancel: Boolean = true

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.BOTTOM)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putBoolean("showOkCancel", showOkCancel)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        (savedInstanceState ?: arguments)?.let { showOkCancel = it.getBoolean("showOkCancel", true) }
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(true)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    LoopSheet(state = buildState(), onAction = ::onAction, onClose = { dismiss() })
                }
            }
        }
    }

    // ---- state ----

    private fun buildState(): LoopSheetState {
        val record = loop.runningModeRecord
        val mode = record.mode
        val allowed = loop.allowedNextModes()
        val pump = activePlugin.activePump.pumpDescription

        val modes = buildList {
            fun opt(id: LoopActionId, rm: RM.Mode, title: String, sub: String) {
                if (allowed.contains(rm) || mode == rm)
                    add(LoopModeOption(id, title, sub, enabled = allowed.contains(rm), selected = mode == rm))
            }
            opt(LoopActionId.CLOSED, RM.Mode.CLOSED_LOOP, rh.gs(app.aaps.core.ui.R.string.closedloop), "Full automation")
            opt(LoopActionId.LGS, RM.Mode.CLOSED_LOOP_LGS, "LGS only", "Suspend on low only")
            opt(LoopActionId.OPEN, RM.Mode.OPEN_LOOP, rh.gs(app.aaps.core.ui.R.string.openloop), "Advice only — you confirm")
            opt(LoopActionId.DISABLE, RM.Mode.DISABLED_LOOP, rh.gs(app.aaps.core.ui.R.string.disableloop), "No automation")
        }

        return LoopSheetState(
            statusLabel = translator.translate(mode),
            statusTone = toneFor(mode),
            looping = mode == RM.Mode.CLOSED_LOOP || mode == RM.Mode.CLOSED_LOOP_LGS,
            algoLine = (activePlugin.activeAPS as? app.aaps.core.interfaces.plugin.PluginBase)?.name ?: "",
            enactedLine = "",
            reasons = record.reasons ?: "",
            modes = modes,
            suspendVisible = allowed.contains(RM.Mode.SUSPENDED_BY_USER),
            resumeVisible = allowed.contains(RM.Mode.RESUME) && mode == RM.Mode.SUSPENDED_BY_USER,
            disconnectVisible = allowed.contains(RM.Mode.DISCONNECTED_PUMP) && config.APS,
            reconnectVisible = allowed.contains(RM.Mode.RESUME) && mode == RM.Mode.DISCONNECTED_PUMP,
            disconnect15m = pump.tempDurationStep15mAllowed,
            disconnect30m = pump.tempDurationStep30mAllowed
        )
    }

    private fun toneFor(mode: RM.Mode): AapsTone = when (mode) {
        RM.Mode.CLOSED_LOOP, RM.Mode.CLOSED_LOOP_LGS -> AapsTone.InRange
        RM.Mode.OPEN_LOOP, RM.Mode.SUSPENDED_BY_USER, RM.Mode.SUSPENDED_BY_PUMP, RM.Mode.SUSPENDED_BY_DST, RM.Mode.SUPER_BOLUS -> AapsTone.High
        else -> AapsTone.Low
    }

    // ---- actions (same path as before) ----

    private fun onAction(id: LoopActionId) {
        if (showOkCancel) {
            activity?.let { OKDialog.showConfirmation(it, rh.gs(app.aaps.core.ui.R.string.confirm), description(id), Runnable { perform(id) }) }
        } else perform(id)
        dismiss()
    }

    private fun description(id: LoopActionId): String = when (id) {
        LoopActionId.CLOSED         -> rh.gs(app.aaps.core.ui.R.string.closedloop)
        LoopActionId.LGS            -> rh.gs(app.aaps.core.ui.R.string.lowglucosesuspend)
        LoopActionId.OPEN           -> rh.gs(app.aaps.core.ui.R.string.openloop)
        LoopActionId.DISABLE        -> rh.gs(app.aaps.core.ui.R.string.disableloop)
        LoopActionId.RESUME         -> rh.gs(R.string.resume)
        LoopActionId.RECONNECT      -> rh.gs(R.string.reconnect)
        LoopActionId.SUSPEND_1H     -> rh.gs(R.string.suspendloopfor1h)
        LoopActionId.SUSPEND_2H     -> rh.gs(R.string.suspendloopfor2h)
        LoopActionId.SUSPEND_3H     -> rh.gs(R.string.suspendloopfor3h)
        LoopActionId.SUSPEND_10H    -> rh.gs(R.string.suspendloopfor10h)
        LoopActionId.DISCONNECT_15M -> rh.gs(R.string.disconnectpumpfor15m)
        LoopActionId.DISCONNECT_30M -> rh.gs(R.string.disconnectpumpfor30m)
        LoopActionId.DISCONNECT_1H  -> rh.gs(R.string.disconnectpumpfor1h)
        LoopActionId.DISCONNECT_2H  -> rh.gs(R.string.disconnectpumpfor2h)
        LoopActionId.DISCONNECT_3H  -> rh.gs(R.string.disconnectpumpfor3h)
    }

    private fun perform(id: LoopActionId) {
        val profile = profileFunction.getProfile() ?: return
        when (id) {
            LoopActionId.CLOSED         -> loop.handleRunningModeChange(newRM = RM.Mode.CLOSED_LOOP, action = Action.CLOSED_LOOP_MODE, source = Sources.LoopDialog, profile = profile)
            LoopActionId.LGS            -> loop.handleRunningModeChange(newRM = RM.Mode.CLOSED_LOOP_LGS, action = Action.LGS_LOOP_MODE, source = Sources.LoopDialog, profile = profile)
            LoopActionId.OPEN           -> loop.handleRunningModeChange(newRM = RM.Mode.OPEN_LOOP, action = Action.OPEN_LOOP_MODE, source = Sources.LoopDialog, profile = profile)
            LoopActionId.DISABLE        -> loop.handleRunningModeChange(newRM = RM.Mode.DISABLED_LOOP, durationInMinutes = Int.MAX_VALUE, action = Action.LOOP_DISABLED, source = Sources.LoopDialog, profile = profile)
            LoopActionId.RESUME         -> {
                loop.handleRunningModeChange(newRM = RM.Mode.RESUME, action = Action.RESUME, source = Sources.LoopDialog, profile = profile)
                preferences.put(BooleanNonKey.ObjectivesReconnectUsed, true)
            }
            LoopActionId.RECONNECT      -> {
                loop.handleRunningModeChange(newRM = RM.Mode.RESUME, action = Action.RECONNECT, source = Sources.LoopDialog, profile = profile)
                preferences.put(BooleanNonKey.ObjectivesReconnectUsed, true)
            }
            LoopActionId.SUSPEND_1H     -> loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = T.hours(1).mins().toInt(), action = Action.SUSPEND, source = Sources.LoopDialog, profile = profile)
            LoopActionId.SUSPEND_2H     -> loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = T.hours(2).mins().toInt(), action = Action.SUSPEND, source = Sources.LoopDialog, profile = profile)
            LoopActionId.SUSPEND_3H     -> loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = T.hours(3).mins().toInt(), action = Action.SUSPEND, source = Sources.LoopDialog, profile = profile)
            LoopActionId.SUSPEND_10H    -> loop.handleRunningModeChange(newRM = RM.Mode.SUSPENDED_BY_USER, durationInMinutes = T.hours(10).mins().toInt(), action = Action.SUSPEND, source = Sources.LoopDialog, profile = profile)
            LoopActionId.DISCONNECT_15M -> loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 15, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
            LoopActionId.DISCONNECT_30M -> loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 30, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
            LoopActionId.DISCONNECT_1H  -> {
                loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 60, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
                preferences.put(BooleanNonKey.ObjectivesDisconnectUsed, true)
            }
            LoopActionId.DISCONNECT_2H  -> loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 120, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
            LoopActionId.DISCONNECT_3H  -> loop.handleRunningModeChange(newRM = RM.Mode.DISCONNECTED_PUMP, durationInMinutes = 180, action = Action.DISCONNECT, source = Sources.LoopDialog, profile = profile)
        }
    }

    override fun show(manager: FragmentManager, tag: String?) {
        try {
            manager.beginTransaction().let {
                it.add(this, tag)
                it.commitAllowingStateLoss()
            }
        } catch (e: IllegalStateException) {
            aapsLogger.debug(e.localizedMessage ?: e.toString())
        }
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

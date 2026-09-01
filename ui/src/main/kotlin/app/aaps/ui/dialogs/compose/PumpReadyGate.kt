package app.aaps.ui.dialogs.compose

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.aaps.core.compose.components.AlertAction
import app.aaps.core.compose.components.AlertContent
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.RM
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.queue.Callback
import app.aaps.core.interfaces.queue.CommandQueue
import javax.inject.Inject

/** What is standing between this dose and the pump, if anything. */
internal sealed interface DeliveryBlocker {

    /** The pump reports it is not delivering. Only the user, at the pump, can clear this. */
    data class PumpStopped(val reservoirEmpty: Boolean, val detail: String, val wording: PumpWording) : DeliveryBlocker

    /** AAPS is suspended or disconnected. Reversible right here. */
    data class LoopSuspended(val mode: RM.Mode) : DeliveryBlocker
}

/**
 * What a stopped pump is called, and what the user has to do about it, for the pump actually attached.
 *
 * A cartridge is changed and primed at the pump; a patch is resumed or replaced from its own tab. Telling
 * a patch user to prime a cartridge is not a cosmetic slip — it describes a device they do not have, in
 * the one dialog standing between them and a dose. [PumpDescription.isPatchPump] is the discriminator
 * every driver already fills in, so no driver has to know this screen exists.
 */
internal data class PumpWording(
    val emptyTitle: String,
    val emptyMessage: String,
    val stoppedTitle: String,
    val stoppedMessage: String
) {

    companion object {

        fun of(description: PumpDescription): PumpWording =
            if (description.isPatchPump)
                PumpWording(
                    emptyTitle = "Patch is empty",
                    emptyMessage = "The patch has no insulin left, so it will not accept this dose. Change the patch, then check again.",
                    stoppedTitle = "Patch is not delivering",
                    stoppedMessage = "The patch is not delivering, so it will refuse this dose. Resume or replace it from the pump tab, then check again."
                )
            else
                PumpWording(
                    emptyTitle = "Reservoir is empty",
                    emptyMessage = "The pump has no insulin left, so it will not accept this dose. Change the cartridge and prime, then check again.",
                    stoppedTitle = "Pump is stopped",
                    stoppedMessage = "The pump is not delivering, so it will refuse this dose. " +
                        "Start delivery on the pump itself${restartHint(description.pumpType)}, then check again."
                )

        /**
         * The menu path to restart delivery, for the pumps whose menus this fork has actually been run
         * against. Everything else gets the sentence without a path: a wrong menu path is worse than none.
         */
        private fun restartHint(pumpType: PumpType): String = when (pumpType) {
            PumpType.YPSOPUMP -> " (Menu ▸ Run)"
            else              -> ""
        }
    }
}

/**
 * Pre-flight for anything that is about to put insulin into the pump.
 *
 * A bolus into a stopped pump used to look like it was working — the wizard did its maths, the
 * progress dialog opened, and then it sat at 0% until the driver's confirm window expired. The pump
 * had refused the very first write. This gate turns that dead end into a decision the user can act
 * on, *before* anything is queued:
 *
 *  - **Pump stopped / reservoir empty** — the pump itself refuses to deliver. A cartridge pump cannot be
 *    restarted over BLE at all (the protocol has no such command, deliberately: starting a pump is a
 *    physical act); a patch is resumed or replaced from its own tab. Either way the fix is somewhere this
 *    dialog cannot reach, so the honest options are "fix it there, then Check again" or "Cancel" — see
 *    [PumpWording] for how each pump is addressed. Check again
 *    reconnects and re-reads status, which is also what clears a merely stale reading — and when the
 *    pump comes back healthy the dose goes ahead without the user re-entering it.
 *  - **Loop suspended / pump disconnected in the app** — that IS reversible from here, so offer to
 *    resume. Not a hard block: suspending the loop is no reason to refuse a meal bolus, so
 *    "Bolus anyway" stays available.
 *  - **Anything else** — [runWhenPumpCanDeliver] just runs the action, with no extra tap.
 */
class PumpReadyGate @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val commandQueue: CommandQueue,
    private val loop: Loop,
    private val profileFunction: ProfileFunction
) {

    fun runWhenPumpCanDeliver(activity: FragmentActivity, proceed: Runnable) {
        val blocker = detect()
        if (blocker == null) proceed.run() else showSheet(activity, blocker, proceed)
    }

    private fun detect(): DeliveryBlocker? {
        val pump = activePlugin.activePump
        // isSuspended() is the pump's own answer, so this covers a user Stop, an occlusion stop and the
        // empty-cartridge auto-stop alike. The reservoir is read separately only to word the message.
        if (pump.isSuspended())
            return DeliveryBlocker.PumpStopped(
                reservoirEmpty = pump.reservoirLevel <= 0.0,
                detail = pump.pumpSpecificShortStatus(true),
                wording = PumpWording.of(pump.pumpDescription)
            )
        val mode = loop.runningMode
        if (mode.isSuspended()) return DeliveryBlocker.LoopSuspended(mode)
        return null
    }

    /** Same call the Loop sheet's Resume makes — mode change, audit log and all. */
    private fun resumeLoop() {
        val profile = profileFunction.getProfile() ?: return
        loop.handleRunningModeChange(newRM = RM.Mode.RESUME, action = Action.RESUME, source = Sources.LoopDialog, profile = profile)
    }

    private fun showSheet(activity: FragmentActivity, blocker: DeliveryBlocker, proceed: Runnable) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Every exit from this sheet has to be a deliberate choice. A "Check again" is in flight for as
        // long as it takes to connect; if a back press could close the sheet meanwhile, the callback would
        // still land and deliver a dose the user had walked away from.
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        var closed = false
        // Only ever act once, and never on an activity that has gone away underneath a slow re-check.
        fun finish(runIt: Boolean, before: () -> Unit = {}) {
            if (closed) return
            closed = true
            dialog.dismiss()
            if (runIt && !activity.isFinishing && !activity.isDestroyed) { before(); proceed.run() }
        }

        val view = ComposeView(activity).apply {
            setViewTreeLifecycleOwner(activity)
            setViewTreeViewModelStoreOwner(activity)
            setViewTreeSavedStateRegistryOwner(activity)
            setContent {
                AapsTheme {
                    PumpReadyContent(
                        initial = blocker,
                        recheck = { onResult ->
                            // The queue callback lands on the queue worker thread; Compose state has to be
                            // written from the main thread. A refused enqueue never calls back at all, which
                            // would leave the button stuck on "Checking…", so answer that case ourselves.
                            val main = Handler(Looper.getMainLooper())
                            val queued = commandQueue.readStatus("bolus pre-check", object : Callback() {
                                override fun run() {
                                    main.post { if (!closed) onResult(detect()) }
                                }
                            })
                            if (!queued) main.post { if (!closed) onResult(detect()) }
                        },
                        onDismiss = { finish(runIt = false) },
                        onProceed = { finish(runIt = true) },
                        onResumeLoop = { finish(runIt = true) { resumeLoop() } }
                    )
                }
            }
        }
        dialog.setContentView(view)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.show()
    }
}

@Composable
private fun PumpReadyContent(
    initial: DeliveryBlocker,
    recheck: ((DeliveryBlocker?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onProceed: () -> Unit,
    onResumeLoop: () -> Unit
) {
    var checking by remember { mutableStateOf(false) }
    var blocker by remember { mutableStateOf(initial) }

    when (val b = blocker) {
        is DeliveryBlocker.PumpStopped   -> AlertContent(
            title = if (b.reservoirEmpty) b.wording.emptyTitle else b.wording.stoppedTitle,
            message = buildString {
                append(if (b.reservoirEmpty) b.wording.emptyMessage else b.wording.stoppedMessage)
                if (b.detail.isNotBlank()) append("\n\n").append(b.detail)
            },
            tint = AapsTheme.colors.low,
            actions = listOf(
                AlertAction(
                    if (checking) "Checking the pump…" else "Check again",
                    primary = true,
                    onClick = {
                        if (!checking) {
                            checking = true
                            recheck { still ->
                                checking = false
                                // Cleared while the sheet was open: deliver without making the user
                                // rebuild the dose. Still blocked: re-render with the current reason.
                                if (still == null) onProceed() else blocker = still
                            }
                        }
                    }
                ),
                AlertAction("Cancel", onClick = onDismiss)
            )
        )

        is DeliveryBlocker.LoopSuspended -> AlertContent(
            title = "Loop is suspended",
            message = "AAPS is ${suspensionLabel(b.mode)}, so it is not adjusting basal. The pump can still take this bolus.",
            tint = AapsTheme.colors.high,
            actions = listOf(
                AlertAction("Resume loop and bolus", primary = true, onClick = onResumeLoop),
                AlertAction("Bolus anyway", onClick = onProceed),
                AlertAction("Cancel", onClick = onDismiss)
            )
        )
    }
}

private fun suspensionLabel(mode: RM.Mode): String = when (mode) {
    RM.Mode.DISCONNECTED_PUMP -> "disconnected from the pump"
    RM.Mode.SUSPENDED_BY_USER -> "suspended by you"
    RM.Mode.SUSPENDED_BY_PUMP -> "suspended by the pump"
    RM.Mode.SUSPENDED_BY_DST  -> "suspended for a clock change"
    RM.Mode.SUPER_BOLUS       -> "running a super bolus"
    else                      -> "suspended"
}

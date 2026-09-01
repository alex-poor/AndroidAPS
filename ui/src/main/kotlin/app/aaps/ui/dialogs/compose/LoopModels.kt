package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable
import app.aaps.core.compose.theme.AapsTone

/** Every discrete loop-control action. Maps 1:1 to the legacy LoopDialog button ids. */
enum class LoopActionId {
    CLOSED, LGS, OPEN, DISABLE,
    RESUME, RECONNECT,
    SUSPEND_1H, SUSPEND_2H, SUSPEND_3H, SUSPEND_10H,
    DISCONNECT_15M, DISCONNECT_30M, DISCONNECT_1H, DISCONNECT_2H, DISCONNECT_3H
}

@Immutable
data class LoopModeOption(
    val id: LoopActionId,
    val title: String,
    val sub: String,
    val enabled: Boolean,
    val selected: Boolean
)

/** Display state for the redesigned Loop control sheet. Built by LoopDialog from the Loop plugin. */
@Immutable
data class LoopSheetState(
    val statusLabel: String = "",
    val statusTone: AapsTone? = null,
    val looping: Boolean = false,
    val algoLine: String = "",
    val enactedLine: String = "",
    val reasons: String = "",
    val modes: List<LoopModeOption> = emptyList(),
    val suspendVisible: Boolean = false,
    val resumeVisible: Boolean = false,
    val disconnectVisible: Boolean = false,
    val reconnectVisible: Boolean = false,
    val disconnect15m: Boolean = false,
    val disconnect30m: Boolean = false
)

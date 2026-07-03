package app.aaps.plugins.main.general.overview.compose

/**
 * Callbacks the Home screen invokes. Each is wired in `OverviewFragment` to the **existing**
 * click handlers (which run `protectionCheck.queryProtection(...)` and open the real dialogs via
 * `uiInteraction`), so the Compose UI never bypasses any confirmation/constraint path.
 */
data class HomeActions(
    val onCarbs: () -> Unit = {},
    val onBolus: () -> Unit = {},
    val onWizard: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onLoop: () -> Unit = {},
    val onTempTarget: () -> Unit = {},
    val onProfile: () -> Unit = {},
    val onIob: () -> Unit = {},
    val onCob: () -> Unit = {},
    val onBasal: () -> Unit = {},
    val onRange: (hours: Int) -> Unit = {},   // graph range segmented control
    val onCalibration: () -> Unit = {}        // "+" overflow → calibrate CGM
)

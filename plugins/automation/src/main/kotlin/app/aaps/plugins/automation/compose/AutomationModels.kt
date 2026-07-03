package app.aaps.plugins.automation.compose

/** Presentation state for the redesigned Automation rules screen (handoff Section 6 — Automation). */
data class AutomationUiState(
    val rules: List<AutomationRule> = emptyList()
)

data class AutomationRule(
    val position: Int,
    val title: String,
    val enabled: Boolean,
    val readOnly: Boolean,
    val system: Boolean,
    val whenChips: List<String>,
    val thenChips: List<String>
)

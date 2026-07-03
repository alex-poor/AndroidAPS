package app.aaps.plugins.configuration.configBuilder.compose

import androidx.compose.runtime.Immutable

/** A row in the "Your loop, right now" summary card. */
@Immutable
data class ConfigSummary(val label: String, val value: String, val ok: Boolean)

/** A toggleable general plugin (Automation / Wear / SMS / Autotune …). Toggled by [index]. */
@Immutable
data class ConfigToggle(val index: Int, val name: String, val sub: String, val enabled: Boolean)

/** A plugin that has a settings screen — tapping opens its (search-enabled) preferences. Keyed by [index]. */
@Immutable
data class PrefEntry(val index: Int, val name: String, val group: String)

@Immutable
data class ConfigUiState(
    val summary: List<ConfigSummary> = emptyList(),
    val plugins: List<ConfigToggle> = emptyList(),
    val prefs: List<PrefEntry> = emptyList()
)

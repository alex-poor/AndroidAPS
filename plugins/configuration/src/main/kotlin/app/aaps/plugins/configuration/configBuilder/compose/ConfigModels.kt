package app.aaps.plugins.configuration.configBuilder.compose

import androidx.compose.runtime.Immutable

/** A row in the "Your loop, right now" summary card. */
@Immutable
data class ConfigSummary(val label: String, val value: String, val ok: Boolean)

/** A toggleable general plugin (Automation / Wear / SMS / Autotune …). Toggled by [index]. */
@Immutable
data class ConfigToggle(val index: Int, val name: String, val sub: String, val enabled: Boolean)

/**
 * One plugin offered inside a [ConfigCategory]. [index] addresses it in the fragment's flat list of
 * selectable plugins; [fixed] marks the ones AAPS always keeps on, which must not be tappable.
 */
@Immutable
data class ConfigOption(val index: Int, val name: String, val description: String, val selected: Boolean, val fixed: Boolean)

/**
 * A category the user picks a plugin from — the pump, the algorithm, the BG source. [multiple] mirrors
 * `ConfigBuilder.areMultipleSelectionsAllowed`: false renders a radio group (choosing one drops the
 * rest), true renders checkboxes.
 */
@Immutable
data class ConfigCategory(val title: String, val description: String, val multiple: Boolean, val options: List<ConfigOption>)

/** A plugin that has a settings screen — tapping opens its (search-enabled) preferences. Keyed by [index]. */
@Immutable
data class PrefEntry(val index: Int, val name: String, val group: String)

@Immutable
data class ConfigUiState(
    val summary: List<ConfigSummary> = emptyList(),
    val categories: List<ConfigCategory> = emptyList(),
    val plugins: List<ConfigToggle> = emptyList(),
    val prefs: List<PrefEntry> = emptyList()
)

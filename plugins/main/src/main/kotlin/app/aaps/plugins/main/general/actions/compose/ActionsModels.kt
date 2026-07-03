package app.aaps.plugins.main.general.actions.compose

import androidx.compose.runtime.Immutable

/** Every discrete Actions/Careportal action. The fragment dispatches these to the existing paths. */
enum class ActionId {
    TEMP_TARGET, TEMP_BASAL, TEMP_BASAL_CANCEL, EXTENDED_BOLUS, EXTENDED_BOLUS_CANCEL, PROFILE_SWITCH,
    FILL, SENSOR_INSERT, BATTERY_CHANGE, BG_CHECK, NOTE, EXERCISE, ANNOUNCEMENT, QUESTION,
    SITE_ROTATION, HISTORY, TDD
}

@Immutable
data class TherapyAction(val id: ActionId, val label: String, val sub: String = "", val cancelable: Boolean = false)

@Immutable
data class EventAction(val id: ActionId, val label: String)

@Immutable
data class ToolAction(val id: ActionId, val label: String)

/** Display state for the redesigned Actions screen — availability is decided in the fragment. */
@Immutable
data class ActionsUiState(
    val therapy: List<TherapyAction> = emptyList(),
    val events: List<EventAction> = emptyList(),
    val tools: List<ToolAction> = emptyList()
)

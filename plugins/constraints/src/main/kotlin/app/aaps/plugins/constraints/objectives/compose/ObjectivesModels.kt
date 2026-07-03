package app.aaps.plugins.constraints.objectives.compose

/** Presentation state for the redesigned Objectives journey (handoff Section 8 — Objectives). */
data class ObjectivesUiState(
    val completed: Int = 0,
    val total: Int = 0,
    val items: List<ObjItem> = emptyList()
)

/** [state]: 0 = done, 1 = current, 2 = locked. */
data class ObjItem(
    val number: Int,
    val title: String,
    val gate: String,
    val state: Int,
    val progress: String
)

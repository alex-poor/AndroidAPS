package app.aaps.plugins.aps.compose

/** Presentation state for the redesigned Algorithm screen (handoff Section 6 — Algorithm). */
data class AlgorithmUiState(
    val title: String = "Loop & algorithm",
    val chips: List<AlgoChip> = emptyList(),
    val predictedMmol: Double? = null,
    val targetMmol: Double? = null,
    val bodyWeight: Double? = null,
    val toggles: List<AlgoToggle> = emptyList()
)

data class AlgoChip(val label: String, val active: Boolean)

/** [id] maps back to a preference in the fragment; [on] is the current value. */
data class AlgoToggle(val id: String, val label: String, val sub: String, val on: Boolean)

package app.aaps.plugins.insulin.compose

/** Presentation state for the redesigned Insulin curve screen (handoff Section 6 — Insulin). */
data class InsulinUiState(
    val types: List<InsulinChip> = emptyList(),
    val activeName: String = "",
    val comment: String = "",
    val diaHours: Double = 0.0,
    val peakMinutes: Int = 0
)

data class InsulinChip(val label: String, val active: Boolean)

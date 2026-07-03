package app.aaps.plugins.configuration.maintenance.compose

/** Presentation state for the redesigned Maintenance & backup screen (handoff Section 8). */
data class MaintenanceUiState(
    val version: String = "",
    val buildInfo: String = ""
)

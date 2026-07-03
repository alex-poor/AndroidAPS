package app.aaps.plugins.sync.nsShared.compose

/** Presentation state for the redesigned Connectivity & sync screen (handoff Section 7). */
data class ConnectivityUiState(
    val cgmName: String = "",
    val cloudName: String = "Cloud",
    val connections: List<ConnCard> = emptyList()
)

/** [level]: 0 = ok (green), 1 = warning (amber), 2 = off (grey). */
data class ConnCard(
    val id: String,
    val name: String,
    val sub: String,
    val level: Int,
    val tappable: Boolean = false
)

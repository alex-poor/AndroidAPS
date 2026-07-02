package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Presentation state for the redesigned Home (Overview) screen. Pure display data — no View
 * references, no domain objects — mapped from the existing Overview providers in
 * `OverviewFragment.buildHomeState()`. Colors are pre-resolved to [Color] on the fragment side
 * (theme attrs can't be read from Compose).
 */
@Immutable
data class HomeUiState(
    // Loop
    val loopStateLabel: String = "",
    val loopSubLabel: String = "",         // e.g. "· looping" / countdown
    val loopColor: Color = Color.Unspecified,
    val looping: Boolean = false,

    // Hero glucose
    val bg: String = "--",
    val bgColor: Color = Color.Unspecified,
    val bgStale: Boolean = false,
    val units: String = "mmol/L",
    val trendArrow: String = "",           // unicode arrow
    val delta: String = "",
    val timeAgo: String = "",

    // Eventual / loop ring
    val eventualBg: String = "",
    val ringProgress: Float = 0f,          // 0..1

    // Target gauge
    val gaugeFraction: Float = 0.5f,       // marker position 0..1
    val gaugeLow: String = "",
    val gaugeTarget: String = "",
    val gaugeHigh: String = "",

    // Stat cards
    val iob: String = "",
    val iobSub: String = "",
    val cob: String = "",
    val cobSub: String = "",
    val basal: String = "",
    val basalSub: String = "",

    // Supplies strip
    val supplies: List<Supply> = emptyList(),

    // Details sheet
    val algorithmName: String = "",
    val sensitivity: String = "",
    val profileName: String = "",
    val tempTarget: String? = null,

    val ready: Boolean = false             // becomes true once first real refresh has run
) {

    @Immutable
    data class Supply(
        val label: String,
        val value: String,
        val dotColor: Color
    )
}

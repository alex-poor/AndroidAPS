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

    // Eventual (bound to APSResult.eventualBG; blank = hide, e.g. open loop / no run yet)
    val eventualBg: String = "",

    // State line — describes the CURRENT reading only: "1.8 above target" / "In target range"
    val stateLine: String = "",
    val targetRange: String = "",          // e.g. "6.5–7.5 mmol/L"

    // Stat row (absorbed into the hero card)
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

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

    // Recent carb entries (last few hours) — shown in the COB-tap "undo carbs" sheet
    val recentCarbs: List<CarbEntry> = emptyList(),

    // Recent insulin doses (last few hours) — shown in the IOB-tap sheet, with the IOB split below.
    val recentInsulin: List<InsulinEntry> = emptyList(),
    val iobBolus: String = "",
    val iobBasal: String = "",

    // Graph range control (hours shown) — mirrors overviewData.rangeToDisplay
    val graphRangeHours: Int = 6,

    // Details sheet
    val algorithmName: String = "",
    val sensitivity: String = "",
    val profileName: String = "",
    val tempTarget: String? = null,

    val ready: Boolean = false,            // becomes true once first real refresh has run

    // Active notifications (pump unreachable, NS alarms, profile failures...). The legacy overview
    // showed these in a RecyclerView under the graph; nothing displayed them after that hierarchy
    // was hidden, so they live on the hero now.
    val notifications: List<Alert> = emptyList()
) {

    /** One active notification, already resolved to presentation strings. */
    @Immutable
    data class Alert(
        val id: Int,
        val text: String,
        val time: String,
        val level: Int,          // Notification.URGENT / NORMAL / LOW / INFO / ANNOUNCEMENT
        val buttonText: String
    )


    @Immutable
    data class Supply(
        val label: String,
        val value: String,
        val dotColor: Color,
        // When set (0f..1f) the pill draws a depleting countdown RING instead of a plain dot — used by
        // the sensor to show life remaining at a glance. null = plain dot (cannula/reservoir/battery).
        val fraction: Float? = null
    )

    /**
     * One recent bolus — presentation strings plus the id/timestamp/amount the undo needs.
     *
     * The undo exists because a dose the pump never delivered still lands in the database (a dropped
     * BLE ack is recorded as delivered on purpose, so IOB is over- rather than under-stated). When the
     * pump turns out to have been stopped or empty, this is how that phantom insulin comes back out.
     */
    @Immutable
    data class InsulinEntry(
        val id: Long,
        val time: String,      // "10:32"
        val units: String,     // "1.20 U"
        val kind: String,      // "" for a normal bolus, "SMB" otherwise
        val timestamp: Long,   // for the removal confirmation + audit log
        val amount: Double     // units, for the audit log
    )

    /** One recent carb record — presentation strings plus the id/timestamp/amount the undo needs. */
    @Immutable
    data class CarbEntry(
        val id: Long,
        val time: String,      // "10:32"
        val grams: String,     // "20 g"
        val timestamp: Long,   // for the removal confirmation + audit log
        val amount: Int        // grams, for the audit log
    )
}

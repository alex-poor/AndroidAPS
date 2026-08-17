package app.aaps.plugins.aps.loop.compose

/** One label/value line of the loop's last run. [value] may be empty while nothing has run yet. */
data class LoopStatusRow(val label: String, val value: CharSequence)

/**
 * Presentation state for [LoopStatusScreen], built by `LoopFragment` from `loop.lastRun`.
 *
 * [timing] rows are split out from [detail] so the screen can group "what was asked for and why" apart
 * from "when the pump was actually told" — the same values the legacy table showed, in the same order.
 */
data class LoopStatusState(
    val lastRun: String = "",
    val source: String = "",
    val running: Boolean = false,
    val detail: List<LoopStatusRow> = emptyList(),
    val timing: List<LoopStatusRow> = emptyList()
)

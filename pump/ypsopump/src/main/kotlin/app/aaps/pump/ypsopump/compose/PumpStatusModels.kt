package app.aaps.pump.ypsopump.compose

import androidx.compose.runtime.Immutable

@Immutable
data class PumpStatusRow(val label: String, val value: String)

@Immutable
data class QueueItem(val text: String, val running: Boolean)

@Immutable
data class PumpStatusState(
    val title: String = "YpsoPump",
    val connection: String = "",
    val connected: Boolean = false,
    val reservoir: Double = 0.0,
    val reservoirMax: Double = 200.0,
    val battery: Int = 0,
    val rows: List<PumpStatusRow> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val note: String = ""
)

package app.aaps.plugins.source.compose

/**
 * One glucose reading as the list draws it.
 *
 * [dayLabel] is non-null only on the first row of each day, which is what produces the sticky-ish date
 * headers. [tooClose] marks a reading that arrived less than 20 s after the previous one — the legacy
 * list tinted those rows with `bgsourceError` because they are almost always a duplicate broadcast
 * (see the known xDrip double-broadcast).
 */
data class BgRow(
    val id: Long,
    val timestamp: Long,
    val dayLabel: String?,
    val time: String,
    val value: String,
    val trend: String,
    val fromNightscout: Boolean,
    val valid: Boolean,
    val tooClose: Boolean
)

data class BgSourceState(
    val rows: List<BgRow> = emptyList(),
    val selecting: Boolean = false,
    val selected: Set<Long> = emptySet()
)

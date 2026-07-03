package app.aaps.ui.activities.stats

import androidx.compose.runtime.Immutable

/** Presentation state for the redesigned Statistics screen. Percentages are 0..100. */
@Immutable
data class StatsUiState(
    val loading: Boolean = true,
    val rangeDays: Int = 7,
    // Time-in-range 5-band split (percent of readings)
    val veryLow: Double = 0.0,
    val low: Double = 0.0,
    val inRange: Double = 0.0,
    val high: Double = 0.0,
    val veryHigh: Double = 0.0,
    // Tiles
    val gmi: String = "--",
    val avgGlucose: String = "--",
    val avgGlucoseUnit: String = "",
    val cv: String = "--",
    val cvGood: Boolean = true,
    val avgTdd: String = "--",
    val carbsPerDay: String = "--"
)

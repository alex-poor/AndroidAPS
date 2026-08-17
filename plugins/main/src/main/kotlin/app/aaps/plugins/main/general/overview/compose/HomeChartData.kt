package app.aaps.plugins.main.general.overview.compose

import androidx.compose.runtime.Immutable

/** A CGM reading, already converted to the user's display units. */
@Immutable
data class GlucosePoint(val time: Long, val value: Double)

/**
 * Effective delivery rate (U/hr) from [time] until the next step. Sampled rather than taken
 * record-by-record: temp basals overlap and supersede each other, so drawing one step per record
 * doubles back on itself.
 */
@Immutable
data class BasalStep(val time: Long, val rate: Double)

@Immutable
enum class TreatmentKind { BOLUS, SMB, CARBS }

/** A bolus or carb entry, drawn on the rail between the two panels. */
@Immutable
data class ChartTreatment(val time: Long, val amount: Double, val kind: TreatmentKind)

/**
 * Everything [HomeGlucoseChart] draws. Built off the same sources the legacy GraphView pipeline used —
 * `overviewData.bgReadingsArray` for the trace, `iobCobCalculator.getBasalData` for delivery, the
 * persistence layer for treatments — so this changes the rendering surface, not the data.
 *
 * Values are in display units; [lowMark] / [highMark] are the same Overview thresholds that colour the
 * hero BG, so the band and the big number can never disagree.
 */
@Immutable
data class HomeChartData(
    val from: Long = 0L,
    val to: Long = 0L,
    val now: Long = 0L,
    val readings: List<GlucosePoint> = emptyList(),
    val basal: List<BasalStep> = emptyList(),
    val scheduledBasal: Double = 0.0,
    val treatments: List<ChartTreatment> = emptyList(),
    val lowMark: Double = 4.0,
    val highMark: Double = 10.0,
    val decimals: Int = 1
) {

    val hasData: Boolean get() = readings.isNotEmpty() && to > from
}

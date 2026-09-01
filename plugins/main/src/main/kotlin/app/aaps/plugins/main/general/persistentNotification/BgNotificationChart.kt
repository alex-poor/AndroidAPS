package app.aaps.plugins.main.general.persistentNotification

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.ColorInt
import app.aaps.plugins.main.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A CGM reading, already converted to the user's display units. */
data class ChartReading(val time: Long, val value: Double)

/**
 * Renders the glucose trace that the ongoing notification shows on the lock screen.
 *
 * This is the same picture [app.aaps.plugins.main.general.overview.compose.HomeGlucoseChart] draws -
 * target band behind a segment-tinted trace, with sensor dropouts left as gaps rather than
 * interpolated - reduced to what survives at notification size, and drawn with plain Canvas because
 * a notification is RemoteViews and cannot host Compose.
 *
 * Two constraints shape it:
 *
 *  - the bitmap crosses a Binder transaction, which is capped at 1 MB for the whole notification, so
 *    the sizes below are pixel budgets, not layout sizes. The ImageView scales them up.
 *  - SystemUI draws the notification in the DEVICE's theme, while ThemeSwitcherPlugin pins AAPS's
 *    own. [paletteContext] resolves the palette against the former, so the trace stays legible when
 *    the app is dark and the shade is light.
 */
class BgNotificationChart(context: Context) {

    companion object {

        /** Sensor gap beyond which the trace is broken rather than interpolated. Matches HomeGlucoseChart. */
        private const val DROPOUT_MS = 20 * 60_000L

        // Pixel budgets. Roughly 1:1 with their slots at 420 dpi; ~100 kB and ~570 kB respectively.
        const val COLLAPSED_W = 300
        const val COLLAPSED_H = 100
        const val EXPANDED_W = 660
        const val EXPANDED_H = 220
    }

    private val appContext = context.applicationContext

    /** Re-resolved whenever the device flips between light and dark, and cached in between. */
    private var cachedNightMode = -1
    private var cachedPalette: Palette? = null

    private class Palette(res: Resources) {

        @ColorInt val inRange: Int = res.getColor(R.color.notification_bg_in_range, null)
        @ColorInt val high: Int = res.getColor(R.color.notification_bg_high, null)
        @ColorInt val low: Int = res.getColor(R.color.notification_bg_low, null)
        @ColorInt val veryLow: Int = res.getColor(R.color.notification_bg_very_low, null)
        @ColorInt val axis: Int = res.getColor(R.color.notification_chart_axis, null)
    }

    @Synchronized private fun palette(): Palette {
        val night = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return cachedPalette?.takeIf { night == cachedNightMode }
            ?: Palette(paletteResources(night)).also {
                cachedPalette = it
                cachedNightMode = night
            }
    }

    /**
     * The colour a reading is drawn in. Uses the Overview marks - the same thresholds that colour the
     * in-app hero BG - so the number and the trace can never disagree. The 0.72 factor separating
     * "low" from "urgent" is [app.aaps.core.compose.theme.glucoseColorMmol]'s.
     */
    @ColorInt fun colorFor(value: Double, lowMark: Double, highMark: Double): Int = palette().let { p ->
        when {
            value < lowMark * 0.72 -> p.veryLow
            value < lowMark        -> p.low
            value <= highMark      -> p.inRange
            else                   -> p.high
        }
    }

    /**
     * @param readings ascending by time, in display units. Fewer than two points draws nothing.
     * @param from     left edge of the time axis. Passed in rather than taken from the first reading
     *                 so that a sensor outage shows as a short trace against an empty axis, instead
     *                 of whatever survived being stretched across the full width.
     * @param to       right edge, normally now - so a stale trace visibly stops short of it.
     * @param withMarks draw the target band and its two gridlines. Off for the collapsed strip, where
     *                  there is not enough height for them to read as anything but noise.
     */
    fun render(
        widthPx: Int,
        heightPx: Int,
        readings: List<ChartReading>,
        from: Long,
        to: Long,
        lowMark: Double,
        highMark: Double,
        withMarks: Boolean
    ): Bitmap? {
        if (readings.size < 2 || widthPx <= 0 || heightPx <= 0 || to <= from) return null

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val strokeW = heightPx * 0.045f
        val pad = strokeW * 2f            // keeps the trace and the end dot off the edges
        val plotTop = pad
        val plotH = heightPx - pad * 2f
        val plotW = widthPx - pad * 2f

        val span = (to - from).toFloat().coerceAtLeast(1f)

        // Always show the band plus headroom, and grow for excursions - as HomeGlucoseChart does.
        val hi = max(highMark + 2.0, ceil(readings.maxOf { it.value } + 0.5))
        val lo = min(lowMark - 1.0, readings.minOf { it.value } - 0.5).coerceAtLeast(0.0)
        val range = (hi - lo).coerceAtLeast(0.1)

        fun x(t: Long): Float = pad + (t - from) / span * plotW
        fun y(v: Double): Float = plotTop + ((hi - v.coerceIn(lo, hi)) / range).toFloat() * plotH

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val colors = palette()
        if (withMarks) {
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(colors.inRange, 0.10f)
            canvas.drawRect(0f, y(highMark), widthPx.toFloat(), y(lowMark), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1f, strokeW * 0.35f)
            // The axis colour is already translucent; fading it again made the marks invisible.
            paint.color = colors.axis
            listOf(lowMark, highMark).forEach { canvas.drawLine(0f, y(it), widthPx.toFloat(), y(it), paint) }
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeW
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        // One path per colour band, so a run that stays in range is a single smooth stroke rather
        // than a chain of separately capped segments.
        var path = Path()
        var pathColor = colorFor(midpoint(readings[0], readings[1]), lowMark, highMark)
        var open = false

        for (i in 1 until readings.size) {
            val a = readings[i - 1]
            val b = readings[i]
            val broken = b.time - a.time > DROPOUT_MS
            val color = colorFor(midpoint(a, b), lowMark, highMark)

            if (broken || color != pathColor) {
                if (open) {
                    paint.color = pathColor
                    canvas.drawPath(path, paint)
                }
                path = Path()
                open = false
                pathColor = color
                if (broken) continue
            }
            if (!open) {
                path.moveTo(x(a.time), y(a.value))
                open = true
            }
            path.lineTo(x(b.time), y(b.value))
        }
        if (open) {
            paint.color = pathColor
            canvas.drawPath(path, paint)
        }

        // The newest reading, so the eye lands on "now" rather than on the tallest excursion.
        val last = readings.last()
        paint.style = Paint.Style.FILL
        paint.color = colorFor(last.value, lowMark, highMark)
        canvas.drawCircle(x(last.time), y(last.value), strokeW * 1.6f, paint)

        return bitmap
    }

    private fun midpoint(a: ChartReading, b: ChartReading) = (a.value + b.value) / 2.0

    @ColorInt private fun withAlpha(@ColorInt color: Int, factor: Float): Int =
        (color and 0x00FFFFFF) or (((color ushr 24) * factor).toInt().coerceIn(0, 255) shl 24)

    /**
     * Resources carrying the DEVICE's night mode rather than the app's, which ThemeSwitcherPlugin
     * pins through AppCompatDelegate. The system configuration is the one AppCompatDelegate cannot
     * have overridden, so it is the honest answer to "what colour is the shade this will be drawn on".
     */
    private fun paletteResources(systemNight: Int): Resources {
        val config = Configuration(appContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or systemNight
        }
        return appContext.createConfigurationContext(config).resources
    }
}

package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.theme.AapsAccent
import app.aaps.core.compose.theme.AapsSemantic
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.HankenGrotesk
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The home glucose graph.
 *
 * Replaces the GraphView rendering, which drew glucose and basal into ONE plot on two different
 * vertical scales. Here they are two panels sharing a time axis, so neither has to be read against an
 * invented scale:
 *
 *  - glucose on top: a continuous trace tinted only where it leaves the target band, over a band drawn
 *    at low opacity so it sits behind the line instead of swallowing it;
 *  - a treatment rail between them — boluses as ticks, carbs as dots, sized by amount, which retires
 *    the rotated overlapping value labels;
 *  - delivered insulin below as a step area, with scheduled basal as a dashed reference.
 *
 * Everything is drawn from the design-system tokens, so it inherits the app's palette and typeface
 * rather than approximating them.
 */
@Composable
fun HomeGlucoseChart(data: HomeChartData, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontFamily = HankenGrotesk, fontSize = 9.sp, color = colors.textTertiary)
    val valueStyle = TextStyle(fontFamily = HankenGrotesk, fontSize = 13.sp)

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (!data.hasData) return@Canvas
            drawChart(data, colors.divider, colors.textTertiary, colors.textOnSurfaceStrong, colors.surface, measurer, axisStyle, valueStyle)
        }
    }
}

private const val GLUCOSE_WEIGHT = 0.60f   // share of height for the glucose panel
private const val RAIL_WEIGHT = 0.10f      // treatment rail
private const val INSULIN_WEIGHT = 0.30f   // delivered insulin

private fun DrawScope.drawChart(
    d: HomeChartData,
    divider: Color,
    tertiary: Color,
    traceInk: Color,
    surface: Color,
    measurer: TextMeasurer,
    axisStyle: TextStyle,
    valueStyle: TextStyle
) {
    val leftPad = 26.dp.toPx()
    val rightPad = 6.dp.toPx()
    val axisH = 14.dp.toPx()
    val plotW = size.width - leftPad - rightPad
    if (plotW <= 0f) return

    val bodyH = size.height - axisH
    val gTop = 2.dp.toPx()
    val gH = bodyH * GLUCOSE_WEIGHT
    val railY = gTop + gH + bodyH * RAIL_WEIGHT * 0.30f   // sits above the panel label, not on it
    val iTop = gTop + gH + bodyH * RAIL_WEIGHT
    val iH = bodyH * INSULIN_WEIGHT

    val span = (d.to - d.from).toFloat().coerceAtLeast(1f)
    fun x(t: Long): Float = leftPad + (t - d.from) / span * plotW

    // Glucose scale: always show the band plus a little headroom, and grow for excursions.
    val maxReading = d.readings.maxOf { it.value }
    val gHi = max(d.highMark + 2.0, kotlin.math.ceil(maxReading + 0.5))
    val gLo = min(d.lowMark - 1.0, d.readings.minOf { it.value } - 0.5).coerceAtLeast(0.0)
    fun y(v: Double): Float = gTop + ((gHi - v.coerceIn(gLo, gHi)) / (gHi - gLo)).toFloat() * gH

    // ---- target band (behind everything) ----
    drawRect(
        color = AapsSemantic.inRange.copy(alpha = 0.08f),
        topLeft = Offset(leftPad, y(d.highMark)),
        size = Size(plotW, y(d.lowMark) - y(d.highMark))
    )

    // ---- the only two gridlines that mean anything clinically ----
    listOf(d.lowMark, d.highMark).forEach { v ->
        drawLine(AapsSemantic.inRange.copy(alpha = 0.22f), Offset(leftPad, y(v)), Offset(size.width - rightPad, y(v)), 1f)
        measurer.label(this, fmt(v, d.decimals), leftPad - 4.dp.toPx(), y(v), axisStyle, alignEnd = true)
    }
    measurer.label(this, fmt(gHi, 0), leftPad - 4.dp.toPx(), y(gHi) + 4.dp.toPx(), axisStyle, alignEnd = true)

    // ---- area under the trace ----
    val pts = d.readings
    if (pts.size > 1) {
        val area = Path().apply {
            moveTo(x(pts.first().time), gTop + gH)
            pts.forEach { lineTo(x(it.time), y(it.value)) }
            lineTo(x(pts.last().time), gTop + gH)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(
                0f to traceInk.copy(alpha = 0.16f),
                1f to Color.Transparent,
                startY = gTop, endY = gTop + gH
            )
        )
    }

    // ---- trace, segment-tinted; a gap longer than 20 min is a sensor dropout, not a line ----
    val strokeW = 2.dp.toPx()
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        if (b.time - a.time > 20 * 60_000L) continue
        val mid = (a.value + b.value) / 2
        drawLine(
            color = stateColor(mid, d.lowMark, d.highMark, traceInk),
            start = Offset(x(a.time), y(a.value)),
            end = Offset(x(b.time), y(b.value)),
            strokeWidth = strokeW,
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }

    // ---- treatment rail ----
    drawLine(divider, Offset(leftPad, railY), Offset(size.width - rightPad, railY), 1f)
    d.treatments.forEach { t ->
        when (t.kind) {
            TreatmentKind.CARBS      -> drawCircle(
                AapsSemantic.high.copy(alpha = 0.9f),
                radius = (sqrt(t.amount).toFloat() * 0.6f).coerceIn(2.5f, 6f).dp.toPx(),
                center = Offset(x(t.time), railY)
            )

            TreatmentKind.BOLUS,
            TreatmentKind.SMB        -> {
                val smb = t.kind == TreatmentKind.SMB
                val h = (t.amount.toFloat() * 1.1f).coerceIn(4f, 13f).dp.toPx()
                drawLine(
                    AapsAccent.accent.copy(alpha = if (smb) 0.65f else 1f),
                    Offset(x(t.time), railY - h / 2), Offset(x(t.time), railY + h / 2),
                    strokeWidth = (if (smb) 1.5f else 2.6f).dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }

    // ---- delivered insulin ----
    val iMax = max(d.basal.maxOfOrNull { it.rate } ?: 0.0, d.scheduledBasal).coerceAtLeast(0.1) * 1.15
    fun iy(r: Double): Float = iTop + iH - (r.coerceIn(0.0, iMax) / iMax).toFloat() * iH

    if (d.basal.size > 1) {
        val step = Path().apply {
            moveTo(leftPad, iy(d.basal.first().rate))
            for (i in 1 until d.basal.size) {
                val px = x(d.basal[i].time)
                lineTo(px, iy(d.basal[i - 1].rate))
                lineTo(px, iy(d.basal[i].rate))
            }
            lineTo(size.width - rightPad, iy(d.basal.last().rate))
            lineTo(size.width - rightPad, iTop + iH)
            lineTo(leftPad, iTop + iH)
            close()
        }
        drawPath(
            step,
            Brush.verticalGradient(
                0f to AapsAccent.accent.copy(alpha = 0.38f),
                1f to AapsAccent.accent.copy(alpha = 0.06f),
                startY = iTop, endY = iTop + iH
            )
        )
        drawPath(step, AapsAccent.accent, style = Stroke(width = 1.4.dp.toPx()))
    }
    // Panel label sits INSIDE the panel on its own ground: in the rail band above, it collided with
    // whichever treatment happened to fall near the left edge.
    run {
        val laid = measurer.measure("INSULIN U/HR", axisStyle)
        drawRoundRect(
            surface.copy(alpha = 0.85f),
            topLeft = Offset(leftPad, iTop + 2.dp.toPx()),
            size = Size(laid.size.width + 6.dp.toPx(), laid.size.height + 2.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        )
        drawText(laid, topLeft = Offset(leftPad + 3.dp.toPx(), iTop + 3.dp.toPx()))
    }

    if (d.scheduledBasal > 0) {
        drawLine(
            tertiary.copy(alpha = 0.8f), Offset(leftPad, iy(d.scheduledBasal)), Offset(size.width - rightPad, iy(d.scheduledBasal)),
            strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
        )
        measurer.label(this, "sched " + fmt(d.scheduledBasal, 2), leftPad + 3.dp.toPx(), iy(d.scheduledBasal) - 3.dp.toPx(), axisStyle)
    }

    // ---- time axis ----
    val hourMs = 3_600_000L
    val stepH = if (span > 14 * hourMs) 4 else if (span > 7 * hourMs) 2 else 1
    var t = ceilToHour(d.from)
    while (t <= d.to) {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        if (cal.get(Calendar.HOUR_OF_DAY) % stepH == 0)
            measurer.label(this, String.format(Locale.getDefault(), "%02d", cal.get(Calendar.HOUR_OF_DAY)), x(t), size.height - 2.dp.toPx(), axisStyle, center = true)
        t += hourMs
    }

    // ---- now ----
    val last = pts.last()
    val nowX = x(last.time)
    drawLine(
        AapsAccent.accent.copy(alpha = 0.45f), Offset(nowX, gTop), Offset(nowX, iTop + iH),
        strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
    )
    val stateC = stateColor(last.value, d.lowMark, d.highMark, traceInk)
    drawCircle(stateC.copy(alpha = 0.16f), radius = 7.dp.toPx(), center = Offset(nowX, y(last.value)))
    drawCircle(stateC, radius = 3.4.dp.toPx(), center = Offset(nowX, y(last.value)))
    drawCircle(surface, radius = 3.4.dp.toPx(), center = Offset(nowX, y(last.value)), style = Stroke(1.4.dp.toPx()))

    // The trace runs into the endpoint, so the current value gets its own ground rather than being
    // printed over the line.
    val txt = fmt(last.value, d.decimals)
    val laid = measurer.measure(txt, valueStyle.copy(color = stateC))
    val chipW = laid.size.width + 8.dp.toPx()
    val chipH = laid.size.height + 3.dp.toPx()
    val chipX = (nowX - 10.dp.toPx() - chipW).coerceAtLeast(leftPad)
    val chipY = (y(last.value) - 10.dp.toPx() - chipH).coerceAtLeast(gTop)
    drawRoundRect(
        surface.copy(alpha = 0.92f), topLeft = Offset(chipX, chipY), size = Size(chipW, chipH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
    )
    drawText(laid, topLeft = Offset(chipX + 4.dp.toPx(), chipY + 1.5.dp.toPx()))
}

private fun stateColor(v: Double, low: Double, high: Double, inRangeInk: Color): Color = when {
    v < low  -> AapsSemantic.low
    v > high -> AapsSemantic.high
    else     -> inRangeInk
}

private fun fmt(v: Double, decimals: Int): String =
    if (decimals <= 0) String.format(Locale.getDefault(), "%.0f", v)
    else String.format(Locale.getDefault(), "%.${decimals}f", v)

private fun ceilToHour(t: Long): Long = (t / 3_600_000L + 1) * 3_600_000L

/** Draw a short axis/caption label; [y] is the text BASELINE, matching how axis labels are positioned. */
private fun TextMeasurer.label(
    scope: DrawScope,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
    alignEnd: Boolean = false,
    center: Boolean = false
) {
    val laid = measure(text, style)
    val dx = when {
        alignEnd -> x - laid.size.width
        center   -> x - laid.size.width / 2f
        else     -> x
    }
    // Clip labels that would spill outside the canvas rather than letting them overlap the edge.
    if (dx < -1f || dx + laid.size.width > scope.size.width + 1f) return
    scope.drawText(laid, topLeft = Offset(dx, y - laid.size.height))
}

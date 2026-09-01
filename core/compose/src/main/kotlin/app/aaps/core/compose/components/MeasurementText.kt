package app.aaps.core.compose.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * A measurement — "1.42 U", "0.45 U/h", "0 g" — with the unit set much smaller than the number.
 *
 * The number is the thing being read; the unit only says what kind of number it is. Setting both at
 * the same size gives the unit a share of the attention (and of the width) out of proportion to what
 * it carries, and on a row of three readouts the units are what push the numbers towards the edges.
 *
 * Narrower is a real benefit rather than a side effect: it is what stops "0.45 U/h" needing to shrink
 * at all under a wide skin font.
 */
@Composable
fun MeasurementText(
    value: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    unitScale: Float = 0.55f,
    minScale: Float = 0.62f
) {
    val max = style.fontSize
    val split = splitMeasurement(value)
    val text = if (split == null) AnnotatedString(value) else buildAnnotatedString {
        append(split.first)
        withStyle(SpanStyle(fontSize = if (max.isUnspecified) 11.sp else max * unitScale)) {
            append(split.second)
        }
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = 1,
        autoSize = if (max.isUnspecified) null
        else TextAutoSize.StepBased(minFontSize = max * minScale, maxFontSize = max, stepSize = 0.5.sp)
    )
}

/**
 * Split a formatted measurement into number and unit, or null when there is no unit to shrink.
 *
 * Deliberately conservative, because the same field carries things that only look like measurements.
 * A trailing token counts as a unit only if it has no digits in it, which keeps "1.42 U" and
 * "0.45 U/h" while leaving durations like "6d 4h" alone — shrinking the "4h" there would be reading
 * the string wrongly, not styling it. A trailing percent sign is taken as a unit on its own, since
 * it never appears with a space.
 *
 * The unit keeps its leading space so the number and unit stay visually separated at the smaller size.
 */
internal fun splitMeasurement(value: String): Pair<String, String>? {
    if (value.isBlank()) return null
    if (value.length > 1 && value.endsWith("%") && value.dropLast(1).any { it.isDigit() })
        return value.dropLast(1) to "%"
    val cut = value.lastIndexOf(' ')
    if (cut <= 0 || cut == value.lastIndex) return null
    val head = value.substring(0, cut)
    val tail = value.substring(cut + 1)
    if (tail.any { it.isDigit() } || !head.any { it.isDigit() }) return null
    return head to value.substring(cut)   // tail keeps its leading space
}

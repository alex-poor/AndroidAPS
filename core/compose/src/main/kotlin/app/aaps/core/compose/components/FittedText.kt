package app.aaps.core.compose.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp

/**
 * A single-line readout that shrinks to fit rather than losing its tail.
 *
 * The layouts were drawn against a proportional font, so a fixed size plus `maxLines = 1` silently
 * clips whatever runs past the edge. That is tolerable for a label and not tolerable for a value:
 * with a skin font about twice as wide per character, "0.45 U/h" rendered as "0.45" and "1.19 U" as
 * "1.19" — the units dropped off a dose readout while still looking like a complete number, which is
 * the worst way for text to fail on a screen someone doses from.
 *
 * Shrinking is the right trade here. A slightly smaller number is still the number; a truncated one
 * is a different number. [minScale] stops it shrinking into illegibility — past that point the text
 * clips as before, because unreadably small is no better than cut off.
 *
 * The floor is a FRACTION of the style rather than an absolute size, which matters more than it
 * looks: a skin can scale the whole type ramp down, and an absolute floor then sits above the text's
 * own size — an inverted range, and the autosizing silently does nothing. That is exactly how the
 * supply pills kept clipping after this was first written.
 */
@Composable
fun FittedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = 0.62f
) {
    val max = style.fontSize
    // Not `== TextUnit.Unspecified`: that sentinel is NaN-backed, so equality is never true.
    if (max.isUnspecified) {
        // Nothing to shrink towards; render as-is rather than guessing a size.
        BasicText(text = text, modifier = modifier, style = style.copy(color = color), maxLines = 1)
        return
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(
            minFontSize = max * minScale,
            maxFontSize = max,
            stepSize = 0.5.sp
        )
    )
}

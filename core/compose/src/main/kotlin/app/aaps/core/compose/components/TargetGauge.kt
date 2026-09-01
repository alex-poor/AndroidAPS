package app.aaps.core.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Horizontal target gauge from the Home hero: a rounded track with a red→green→amber gradient and
 * a white marker at the current-BG position. [fraction] is the marker position 0f..1f mapping
 * [rangeLow]..[rangeHigh] onto the track. Labels show low / target band / high.
 */
@Composable
fun TargetGauge(
    fraction: Float,
    lowLabel: String,
    targetLabel: String,
    highLabel: String,
    modifier: Modifier = Modifier
) {
    val colors = AapsTheme.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
        ) {
            val h = size.height
            val r = CornerRadius(h / 2f, h / 2f)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    0.0f to colors.low,
                    0.28f to colors.inRange,
                    0.72f to colors.inRange,
                    1.0f to colors.high
                ),
                size = Size(size.width, h),
                cornerRadius = r
            )
            // marker
            val x = (size.width * fraction.coerceIn(0f, 1f)).coerceIn(8.dp.toPx(), size.width - 8.dp.toPx())
            drawCircle(color = Color.White, radius = 8.dp.toPx(), center = Offset(x, h / 2f))
            drawCircle(color = Color.Black.copy(alpha = 0.25f), radius = 8.dp.toPx(), center = Offset(x, h / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(lowLabel, style = AapsType.caption, color = colors.textTertiary)
            Text(targetLabel, style = AapsType.caption, color = colors.textSecondary)
            Text(highLabel, style = AapsType.caption, color = colors.textTertiary)
        }
    }
}

package app.aaps.core.compose.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme

/**
 * The loop ring from the Home hero card: a track + a progress arc colored by loop/glucose state,
 * with the "eventual" BG value in the center. [progress] is 0f..1f; [color] carries state meaning
 * (green looping, amber, red, or accent when interactive elsewhere).
 */
@Composable
fun LoopRing(
    progress: Float,
    centerValue: String,
    modifier: Modifier = Modifier,
    color: Color = AapsTheme.colors.inRange,
    diameter: Dp = 92.dp,
    strokeWidth: Dp = 8.dp,
    centerLabel: String? = null
) {
    val colors = AapsTheme.colors
    val track = Color.White.copy(alpha = 0.08f)
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            val inset = strokeWidth.toPx() / 2f
            val arcSize = androidx.compose.ui.geometry.Size(size.width - strokeWidth.toPx(), size.height - strokeWidth.toPx())
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke
            )
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(centerValue, style = AapsTheme.type.cardValue, color = colors.textPrimary)
            if (centerLabel != null) Text(centerLabel, style = AapsTheme.type.label, color = colors.textTertiary)
        }
    }
}

package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Status pill: a colored dot (glucose/status color) + label + optional bold value, on a flat
 * control-fill background. This is a *readout*, so it stays flat (no accent, not tappable).
 * Used for the Home loop pill and the supplies strip.
 */
@Composable
fun StatusPill(
    label: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    value: String? = null,
    labelColor: Color = AapsTheme.colors.textSecondary,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val colors = AapsTheme.colors
    Row(
        modifier = modifier
            .clip(AapsShape.pill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(colors.controlFill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (dotColor != null) Dot(dotColor, glow)
        Text(label, style = AapsType.caption, color = labelColor)
        if (value != null) Text(value, style = AapsType.listTitle, color = colors.textPrimary)
    }
}

/** A colored status dot, optionally with a soft glow (for the "looping" indicator). */
@Composable
fun Dot(color: Color, glow: Boolean = false, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(contentAlignment = Alignment.Center) {
        if (glow) Box(
            Modifier
                .size(size + 8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.22f))
        )
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(color)
        )
    }
}

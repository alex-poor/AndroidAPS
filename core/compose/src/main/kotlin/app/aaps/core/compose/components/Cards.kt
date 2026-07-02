package app.aaps.core.compose.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Base surface card for the redesign: [AapsTheme] surface fill + hairline border, no elevation.
 * Pure readout by default; pass [onClick] to make it interactive.
 */
@Composable
fun AapsCard(
    modifier: Modifier = Modifier,
    shape: Shape = AapsShape.card,
    color: Color = AapsTheme.colors.surface,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(AapsSpacing.cardPad),
    content: @Composable () -> Unit
) {
    Card(
        modifier = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = color),
        border = BorderStroke(1.dp, AapsTheme.colors.hairline)
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

/**
 * Compact stat card: uppercase label, big value, optional sub, chevron when tappable.
 * Used for the Home IOB / COB / Basal trio.
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    valueColor: Color = AapsTheme.colors.textPrimary,
    onClick: (() -> Unit)? = null
) {
    val colors = AapsTheme.colors
    AapsCard(
        modifier = modifier,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = AapsSpacing.cardPadSmall, vertical = AapsSpacing.cardPadSmall)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = AapsType.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                if (onClick != null) Chevron()
            }
            Text(value, style = AapsType.cardValue, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = AapsType.caption, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun Chevron() {
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = AapsTheme.colors.textTertiary,
        modifier = Modifier.size(16.dp)
    )
}

/** Tinted rounded-square icon holder for list rows (leading-icon grammar). */
@Composable
fun TintIcon(
    icon: ImageVector,
    contentDescription: String? = null,
    tint: Color = AapsTheme.colors.accent,
    background: Color = AapsTheme.colors.accentTint,
    size: Dp = 36.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

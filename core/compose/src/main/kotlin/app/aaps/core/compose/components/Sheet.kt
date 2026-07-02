package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Bottom-sheet surface: scrim-less rounded-top panel with a grabber and a title row. Hosted inside a
 * DialogFragment whose window is bottom-gravity. [title] + optional close.
 */
@Composable
fun SheetSurface(
    title: String,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(AapsShape.sheet)
            .background(colors.surface3)
            .padding(bottom = 12.dp)
    ) {
        // grabber
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.22f))
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AapsSpacing.screenH, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            if (onClose != null) Box(Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClose).padding(4.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = colors.textSecondary)
            }
        }
        Column(Modifier.padding(horizontal = AapsSpacing.screenH), verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)) {
            content()
        }
    }
}

/** A rounded chip. [selected] fills accent-tint; otherwise control-fill. */
@Composable
fun Chip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val colors = AapsTheme.colors
    val bg = when {
        !enabled -> colors.controlFill
        selected -> colors.accentTintStrong
        else     -> colors.controlFill
    }
    val fg = when {
        !enabled -> colors.textTertiary
        selected -> colors.accentOnLight
        else     -> colors.textPrimary
    }
    Text(
        label,
        style = AapsType.listTitle,
        color = fg,
        modifier = modifier
            .clip(AapsShape.pill)
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

package app.aaps.core.compose.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Segmented control (e.g. graph range [3h][6h][12h], profile tabs). Active segment uses the accent
 * tint (interactive) — instant switch. Options are given as display labels.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AapsTheme.colors
    Row(
        modifier = modifier
            .clip(AapsShape.pill)
            .background(colors.controlFill)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selectedIndex
            val bg by animateColorAsState(if (active) colors.accentTintStrong else Color.Transparent, label = "seg-bg")
            Text(
                text = label,
                style = AapsType.label,
                color = if (active) colors.accentOnLight else colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onSelect(i) }
                    .background(bg)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

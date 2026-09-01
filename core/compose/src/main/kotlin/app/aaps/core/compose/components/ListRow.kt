package app.aaps.core.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme

/**
 * Consistent list-row grammar from the handoff: leading icon → title + sub → trailing content
 * (value / chevron / switch). Trailing is a slot so callers can drop a value Text, a [Chevron],
 * or a Switch.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = AapsTheme.colors
    Row(
        modifier = (if (onClick != null) modifier.clickable(onClick = onClick) else modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = AapsTheme.type.caption, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke(this)
    }
}

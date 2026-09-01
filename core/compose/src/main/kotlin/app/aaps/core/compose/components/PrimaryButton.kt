package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme

/** Full-width filled-accent confirm button used across the redesigned dialogs. */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = AapsTheme.colors
    Text(
        label,
        style = AapsTheme.type.title,
        color = if (enabled) colors.onAccent else colors.textTertiary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(AapsTheme.shape.button)
            .background(if (enabled) colors.accent else colors.controlFill)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp)
    )
}

/** Full-width text button in the "danger" (glucose-low red) tint for cancel/stop actions. */
@Composable
fun DangerButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    Text(
        label,
        style = AapsTheme.type.body,
        color = colors.low,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(AapsTheme.shape.pill)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
    )
}

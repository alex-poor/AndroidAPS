package app.aaps.core.compose.components

import app.aaps.core.compose.icons.AapsIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * A circular −/+ stepper with a big centered value + caption. [value] is pre-formatted by the caller.
 */
@Composable
fun Stepper(
    value: String,
    caption: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AapsTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StepBtn(minus = true, onClick = onMinus)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = AapsType.bigValue, color = colors.textPrimary)
            Text(caption, style = AapsType.caption, color = colors.textTertiary)
        }
        StepBtn(minus = false, onClick = onPlus)
    }
}

@Composable
private fun StepBtn(minus: Boolean, onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (minus) colors.controlFill else colors.accentTint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (minus) AapsIcons.Remove else Icons.Rounded.Add,
            contentDescription = if (minus) "decrease" else "increase",
            tint = if (minus) colors.textPrimary else colors.accentOnLight,
            modifier = Modifier.size(24.dp)
        )
    }
}

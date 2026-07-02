package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * A tonal action-bar button (icon + label), 58dp tall. Tint conveys role:
 *  - accent tint = primary interactive (Carbs, +),
 *  - green tint = the Bolus deliver action,
 *  - filled accent ([emphasized]) = the emphasized Wizard.
 * All are interactive (accent/tinted), never using glucose colors as chrome.
 */
@Composable
fun ActionBarButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = AapsTheme.colors.accentTint,
    content: Color = AapsTheme.colors.accentOnLight,
    emphasized: Boolean = false
) {
    val bg = if (emphasized) AapsTheme.colors.accent else container
    val fg = if (emphasized) AapsTheme.colors.onAccent else content
    Column(
        modifier = modifier
            .height(58.dp)
            .clip(AapsShape.button)
            .background(bg)
            .clickable(onClick = onClick, role = Role.Button),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
        Text(label, style = AapsType.label, color = fg)
    }
}

/** Round control-fill icon button (the "+" overflow in the action bar). */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .size(58.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(AapsTheme.colors.controlFill)
            .clickable(onClick = onClick, role = Role.Button),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = contentDescription, tint = AapsTheme.colors.textPrimary, modifier = Modifier.size(24.dp))
    }
}

package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Alarm / error sheet. Deliberately loud: a red mark, the failing message, and three explicit exits
 * (mute, mute 5 min, dismiss) — the same three the legacy dialog offered, in the same order of
 * escalation, so muscle memory still works when this fires at 3am.
 */
@Composable
fun ErrorSheet(
    title: String,
    status: String,
    onMute: () -> Unit,
    onMute5Min: () -> Unit,
    onOk: () -> Unit
) {
    val colors = AapsTheme.colors
    SheetSurface(title = title) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.screenH, vertical = AapsSpacing.cardPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(colors.low.copy(alpha = 0.18f))) {
                Box(Modifier.align(Alignment.Center).size(14.dp).clip(CircleShape).background(colors.low))
            }
            if (status.isNotBlank())
                Text(
                    status, style = AapsType.body, color = colors.textSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = AapsSpacing.rowGapSmall)
                )

            SecondaryAction("Mute 5 min", onMute5Min)
            SecondaryAction("Mute", onMute)
            PrimaryButton(label = "Dismiss", onClick = onOk, modifier = Modifier.fillMaxWidth())
            Box(Modifier.padding(bottom = AapsSpacing.rowGap))
        }
    }
}

/** Muted counterpart to [PrimaryButton] — same shape and height, surface fill instead of accent. */
@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Text(
        label,
        style = AapsType.title,
        color = colors.textOnSurfaceStrong,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clip(AapsShape.button)
            .background(colors.controlFill)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp)
    )
}

package app.aaps.ui.dialogs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.DangerButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Live bolus-delivery progress sheet. Presentation only — [percent]/[status] are driven by the
 * hosting [app.aaps.ui.dialogs.BolusProgressDialog] from the SAME rxBus bolus-progress events, and
 * [onStop] calls the SAME `commandQueue.cancelAllBoluses` cancel path as the legacy Stop button.
 *
 * No grabber-close (onClose = null) — the sheet must not be dismissible while insulin is delivering.
 */
@Composable
fun BolusProgressSheet(percent: Int, status: String, onStop: () -> Unit) {
    val colors = AapsTheme.colors
    val fraction = (percent / 100f).coerceIn(0f, 1f)

    SheetSurface(title = "Delivering bolus", onClose = null) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(status, style = AapsType.body, color = colors.textSecondary)
            Text("$percent%", style = AapsType.hero, color = colors.textPrimary)
            // progress track + accent-filled fraction (GaugeTile pattern)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.controlFill),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.accent)
                )
            }
            DangerButton(label = "Stop", onClick = onStop)
        }
    }
}

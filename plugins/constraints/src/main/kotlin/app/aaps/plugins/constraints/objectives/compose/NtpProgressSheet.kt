package app.aaps.plugins.constraints.objectives.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
 * Network-time check progress. Objectives runs this to prove the phone clock has not been wound back;
 * it is modal and non-cancellable until it completes or the user closes it.
 */
@Composable
fun NtpProgressSheet(title: String, status: String, percent: Int, closeLabel: String, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    SheetSurface(title = title) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.screenH, vertical = AapsSpacing.cardPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)
        ) {
            Text(status, style = AapsType.body, color = colors.textSecondary, textAlign = TextAlign.Center)

            // Determinate bar: the check reports real percentages, so a spinner would understate progress.
            Box(Modifier.fillMaxWidth().height(6.dp).clip(AapsShape.pill).background(colors.controlFill)) {
                Box(
                    Modifier
                        .fillMaxWidth(percent.coerceIn(0, 100) / 100f)
                        .height(6.dp)
                        .clip(AapsShape.pill)
                        .background(colors.accent)
                )
            }

            PrimaryButton(label = closeLabel, onClick = onClose, modifier = Modifier.fillMaxWidth().padding(top = AapsSpacing.rowGapSmall))
            Box(Modifier.padding(bottom = AapsSpacing.rowGap))
        }
    }
}

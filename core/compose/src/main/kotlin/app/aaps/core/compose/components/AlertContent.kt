package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/** One button in an [AlertContent] footer. */
data class AlertAction(
    val label: String,
    val onClick: () -> Unit,
    /** Filled accent (the affirmative). Exactly one action should normally be primary. */
    val primary: Boolean = false,
    /** Tinted with the "low" red — destructive / stop. */
    val destructive: Boolean = false
)

/**
 * The redesign's alert body: a rounded surface card with an accent rule, a title, a message and a
 * stacked action list. This is what every confirmation in the app now looks like — `OKDialog` hosts
 * it in a plain `Dialog` so all ~46 existing call sites move over without touching their code.
 *
 * Actions stack vertically rather than sitting in a Material button row: the messages here are
 * itemised therapy summaries, and a full-width target is both easier to hit and harder to mis-tap
 * than two small text buttons in a corner.
 */
@Composable
fun AlertContent(
    title: String,
    message: String,
    actions: List<AlertAction>,
    /** Accent rule + title tint. Defaults to the theme accent; callers pass a semantic color for alarms. */
    tint: Color = Color.Unspecified
) {
    val colors = AapsTheme.colors
    val rule = tint.takeIf { it != Color.Unspecified } ?: colors.accent
    Box(Modifier.fillMaxWidth().padding(AapsSpacing.screenH)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(AapsShape.hero)
                .background(colors.surface)
                .padding(AapsSpacing.cardPad),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)
        ) {
            if (title.isNotBlank())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .size(width = 3.dp, height = 20.dp)
                            .clip(AapsShape.pill)
                            .background(rule)
                    )
                    Text(title, style = AapsType.title, color = colors.textPrimary)
                }
            if (message.isNotBlank())
                Text(
                    message,
                    style = AapsType.body,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            Column(
                Modifier.fillMaxWidth().padding(top = AapsSpacing.rowGapSmall),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)
            ) {
                actions.forEach { a ->
                    when {
                        a.primary     -> PrimaryButton(a.label, a.onClick, Modifier.fillMaxWidth())
                        a.destructive -> DangerButton(a.label, a.onClick, Modifier.fillMaxWidth())
                        else          -> SecondaryButton(a.label, a.onClick, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** Full-width outlined/neutral button — the "not the affirmative" option in an [AlertContent]. */
@Composable
fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    Text(
        label,
        style = AapsType.body,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clip(AapsShape.button)
            .background(colors.controlFill)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

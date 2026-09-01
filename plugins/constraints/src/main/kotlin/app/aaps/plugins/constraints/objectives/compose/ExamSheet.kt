package app.aaps.plugins.constraints.objectives.compose

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/** One answer option: the text, whether the user has ticked it, and whether it is still editable. */
data class ExamOption(val text: String, val selected: Boolean)

data class ExamState(
    val name: String = "",
    val question: String = "",
    val options: List<ExamOption> = emptyList(),
    val answered: Boolean = false,
    val disabledUntil: String? = null,   // non-null while a wrong answer is timing out
    val canVerify: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoNext: Boolean = false,
    val canGoNextUnanswered: Boolean = false,
    val position: String = ""            // e.g. "3 / 12"
)

/**
 * Objectives exam question. Hints keep their legacy `TextView` rendering via [AndroidView] because
 * they carry Linkify'd URLs to the docs — re-implementing that in Compose would be the one part of
 * this screen where a rewrite could silently drop functionality.
 */
@Composable
fun ExamSheet(
    state: ExamState,
    hintViews: List<View>,
    onToggleOption: (Int) -> Unit,
    onVerify: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onNextUnanswered: () -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    SheetSurface(title = state.name, onClose = onClose) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.question, style = AapsTheme.type.listTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (state.position.isNotBlank())
                    Text(state.position, style = AapsTheme.type.caption, color = colors.textTertiary)
            }

            AapsCard(Modifier.fillMaxWidth()) {
                Column {
                    state.options.forEachIndexed { i, option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(if (state.answered) Modifier else Modifier.clickable { onToggleOption(i) })
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = option.selected,
                                onCheckedChange = if (state.answered) null else ({ onToggleOption(i) }),
                                enabled = !state.answered,
                                colors = CheckboxDefaults.colors(checkedColor = colors.accent, uncheckedColor = colors.textTertiary)
                            )
                            Text(
                                option.text,
                                style = AapsTheme.type.body,
                                color = if (state.answered) colors.textSecondary else colors.textPrimary,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }
            }

            state.disabledUntil?.let {
                Text(it, style = AapsTheme.type.caption, color = colors.high)
            }

            if (hintViews.isNotEmpty())
                AapsCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
                        Text("HINTS", style = AapsTheme.type.label, color = colors.textSecondary)
                        hintViews.forEach { hint ->
                            AndroidView(factory = { hint }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

            PrimaryButton(
                label = if (state.answered) "Answered" else "Verify",
                onClick = onVerify,
                enabled = state.canVerify,
                modifier = Modifier.fillMaxWidth()
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
                NavAction("← Back", state.canGoBack, onBack, Modifier.weight(1f))
                NavAction("Next unanswered", state.canGoNextUnanswered, onNextUnanswered, Modifier.weight(1.4f))
                NavAction("Next →", state.canGoNext, onNext, Modifier.weight(1f))
            }

            if (state.answered)
                Text(
                    "Reset answer",
                    style = AapsTheme.type.label,
                    color = colors.textSecondary,
                    modifier = Modifier.clip(AapsTheme.shape.button).clickable(onClick = onReset).padding(vertical = 10.dp)
                )
            Box(Modifier.padding(bottom = AapsSpacing.rowGap))
        }
    }
}

@Composable
private fun NavAction(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AapsTheme.colors
    Text(
        label,
        style = AapsTheme.type.label,
        color = if (enabled) colors.textOnSurfaceStrong else colors.textTertiary,
        modifier = modifier
            .clip(AapsTheme.shape.button)
            .background(colors.controlFill)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
    )
}

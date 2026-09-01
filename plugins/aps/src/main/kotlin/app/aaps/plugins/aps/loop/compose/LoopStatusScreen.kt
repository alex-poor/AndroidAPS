package app.aaps.plugins.aps.loop.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned Loop tab: what the algorithm last asked for, what the constraints did to it, and when the
 * pump was actually told. Read-only apart from "Run now", which is the same `loop.invoke(...)` the
 * legacy swipe-to-refresh and overflow menu called.
 */
@Composable
fun LoopStatusScreen(state: LoopStatusState, onRunNow: () -> Unit) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Loop", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            StatusPill(
                label = if (state.running) "Running" else "Idle",
                dotColor = if (state.running) colors.inRange else colors.textTertiary
            )
        }

        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column {
                Text("LAST RUN", style = AapsType.label, color = colors.textSecondary)
                Text(
                    state.lastRun.ifBlank { "Never" },
                    style = AapsType.listTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (state.source.isNotBlank())
                    Text(state.source, style = AapsType.caption, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
                PrimaryButton(
                    label = "Run now",
                    onClick = onRunNow,
                    modifier = Modifier.fillMaxWidth().padding(top = AapsSpacing.rowGap)
                )
            }
        }

        Section("DECISION", state.detail)
        Section("DELIVERY", state.timing)

        Box(Modifier.height(AapsSpacing.sectionGap))
    }
}

@Composable
private fun Section(title: String, rows: List<LoopStatusRow>) {
    if (rows.none { it.value.isNotBlank() }) return
    val colors = AapsTheme.colors
    Text(title, style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
    AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
        Column {
            var drawn = 0
            rows.forEach { r ->
                if (r.value.isBlank()) return@forEach
                if (drawn > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                drawn++
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.label, style = AapsType.caption, color = colors.textSecondary)
                    Text(r.value.toString(), style = AapsType.body, color = colors.textPrimary)
                }
            }
        }
    }
}

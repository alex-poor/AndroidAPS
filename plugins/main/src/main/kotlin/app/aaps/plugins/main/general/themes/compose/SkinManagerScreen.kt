package app.aaps.plugins.main.general.themes.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.theme.AapsColors
import app.aaps.core.compose.theme.AapsTheme

/**
 * Presentation state for [SkinManagerScreen]. Built by the activity; no domain types.
 */
data class SkinManagerState(
    val entries: List<Entry> = emptyList(),
    /** Set after a failed import — the reason, phrased for whoever wrote the file. */
    val error: String? = null,
    val activeId: String = ""
) {

    data class Entry(
        val id: String,
        val label: String,
        val byline: String?,
        /** Null for an appearance with no palette of its own to show, like "Follow system". */
        val swatches: List<Color>?,
        /** File-backed skins can be sent on and deleted; built-in appearances cannot. */
        val removable: Boolean
    )

    companion object {

        /** The colours that say most about a skin at a glance: its ground, its accent, its bands. */
        fun swatchesOf(colors: AapsColors) =
            listOf(colors.background, colors.surface, colors.accent, colors.inRange, colors.high, colors.low)
    }
}

data class SkinManagerActions(
    val onSelect: (id: String) -> Unit,
    val onImport: () -> Unit,
    val onExportTemplate: () -> Unit,
    val onExport: (id: String) -> Unit,
    val onRemove: (id: String) -> Unit,
    val onDismissError: () -> Unit
)

/**
 * Choose how the app looks, and manage the skin files that add to the choice.
 *
 * One list, not two screens. An earlier version showed the installed skins here and kept *selecting*
 * one in a separate preference picker, on the reasoning that choosing and managing are different
 * jobs. They are not different to the person doing them: it produced a screen that listed skins,
 * marked which was active, and did nothing when you tapped one.
 */
@Composable
fun SkinManagerScreen(state: SkinManagerState, actions: SkinManagerActions) {
    val colors = AapsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        state.error?.let { message ->
            AapsCard(Modifier.fillMaxWidth(), color = colors.low.copy(alpha = 0.12f)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Skin not installed", style = AapsTheme.type.listTitle, color = colors.low)
                    // The validator's own sentence, verbatim: it names the token and the numbers, and
                    // paraphrasing it here would cost the author the one clue they can act on.
                    Text(message, style = AapsTheme.type.body, color = colors.textSecondary)
                    Text(
                        "Dismiss",
                        style = AapsTheme.type.label,
                        color = colors.accentOnLight,
                        modifier = Modifier.clickable(onClick = actions.onDismissError)
                    )
                }
            }
        }

        Text("APPEARANCE", style = AapsTheme.type.label, color = colors.textSecondary)
        AapsCard(Modifier.fillMaxWidth()) {
            Column {
                state.entries.forEachIndexed { i, entry ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    SkinRow(entry, state.activeId == entry.id, actions)
                }
            }
        }

        PrimaryButton(label = "Import a skin file", onClick = actions.onImport, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "Export a starter template",
                style = AapsTheme.type.label,
                color = colors.accentOnLight,
                modifier = Modifier.clickable(onClick = actions.onExportTemplate).padding(8.dp)
            )
        }
    }
}

@Composable
private fun SkinRow(entry: SkinManagerState.Entry, active: Boolean, actions: SkinManagerActions) {
    val colors = AapsTheme.colors
    Row(
        // The whole row is the tap target, so the obvious gesture does the obvious thing.
        Modifier
            .fillMaxWidth()
            .clickable { actions.onSelect(entry.id) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Rounded.Check,
            contentDescription = if (active) "in use" else null,
            // Reserved rather than conditional, so selecting does not shuffle the row's contents.
            tint = if (active) colors.inRange else Color.Transparent,
            modifier = Modifier.size(18.dp)
        )
        // The palette itself is the clearest label a skin can have.
        entry.swatches?.let { swatches ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                swatches.forEach { Box(Modifier.size(12.dp).clip(CircleShape).background(it)) }
            }
        }
        // Spaced rather than stacked flush: a skin supplies its own font, and a face whose glyphs
        // are taller than their line box — a pixel font, say — runs the label into the byline.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.label, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong, maxLines = 1)
            entry.byline?.let { Text(it, style = AapsTheme.type.caption, color = colors.textTertiary, maxLines = 1) }
        }
        if (entry.removable) {
            Icon(
                Icons.Rounded.Share, contentDescription = "export", tint = colors.textSecondary,
                modifier = Modifier.size(18.dp).clickable { actions.onExport(entry.id) }
            )
            Icon(
                Icons.Rounded.Delete, contentDescription = "remove", tint = colors.textSecondary,
                modifier = Modifier.size(18.dp).clickable { actions.onRemove(entry.id) }
            )
        }
    }
}

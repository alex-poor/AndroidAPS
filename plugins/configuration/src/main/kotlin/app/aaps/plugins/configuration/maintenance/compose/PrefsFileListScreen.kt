package app.aaps.plugins.configuration.maintenance.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * One exported settings file. [flavourOk] / [versionOk] carry the legacy metadata warning colouring —
 * an export from a different variant or an older AAPS is still importable, but is flagged, and that
 * warning is the whole reason this list shows metadata at all.
 */
data class PrefsFileRow(
    val name: String,
    val flavour: String,
    val flavourOk: Boolean,
    val version: String,
    val versionOk: Boolean,
    val exportedAgo: String,
    val deviceName: String
)

/**
 * Pick an exported settings file to restore. Shared by the local and the cloud file lists; the cloud
 * one supplies [subtitle] (loaded / total) and [loadMoreLabel], since it pages its listing.
 */
@Composable
fun PrefsFileListScreen(
    title: String,
    files: List<PrefsFileRow>,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
    subtitle: String? = null,
    loadMoreLabel: String? = null,
    loadMoreEnabled: Boolean = true,
    onLoadMore: (() -> Unit)? = null
) {
    val colors = AapsTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AapsSpacing.screenH, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "←", style = AapsType.title, color = colors.textSecondary,
                modifier = Modifier.clip(AapsShape.iconButton).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(title, style = AapsType.title, color = colors.textPrimary)
                subtitle?.let { Text(it, style = AapsType.caption, color = colors.textTertiary) }
            }
        }

        if (files.isEmpty()) {
            Text(
                "No exported settings found",
                style = AapsType.body,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = AapsSpacing.screenH)
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = AapsSpacing.screenH)) {
            items(files) { file ->
                val index = files.indexOf(file)
                AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.rowGap).clickable { onSelect(index) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(file.name, style = AapsType.listTitle, color = colors.textPrimary)
                        if (file.exportedAgo.isNotBlank())
                            Text(file.exportedAgo, style = AapsType.caption, color = colors.textSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
                            if (file.flavour.isNotBlank())
                                Meta(file.flavour, if (file.flavourOk) colors.textSecondary else colors.high)
                            if (file.version.isNotBlank())
                                Meta(file.version, if (file.versionOk) colors.textSecondary else colors.high)
                            if (file.deviceName.isNotBlank())
                                Meta(file.deviceName, colors.textTertiary)
                        }
                    }
                }
            }

            if (loadMoreLabel != null && onLoadMore != null)
                item {
                    Text(
                        loadMoreLabel,
                        style = AapsType.label,
                        color = if (loadMoreEnabled) colors.textOnSurfaceStrong else colors.textTertiary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = AapsSpacing.sectionGap)
                            .clip(AapsShape.button)
                            .background(colors.controlFill)
                            .then(if (loadMoreEnabled) Modifier.clickable(onClick = onLoadMore) else Modifier)
                            .padding(vertical = 12.dp)
                    )
                }
        }
    }
}


@Composable
private fun Meta(text: String, color: Color) {
    Text(text, style = AapsType.caption, color = color)
}

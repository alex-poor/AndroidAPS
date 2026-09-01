package app.aaps.plugins.configuration.maintenance.compose

import app.aaps.core.compose.icons.AapsIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Redesigned Maintenance & backup screen (handoff Section 8): a primary "Export settings" action, an
 * import/export-logs list, and the app version. All actions reuse the existing import/export/log paths;
 * "More options" reveals the legacy screen for cloud/database advanced tools.
 */
@Composable
fun MaintenanceScreen(
    state: MaintenanceUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onExportLogs: () -> Unit,
    onMore: () -> Unit
) {
    val colors = AapsTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Text("Maintenance", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.padding(vertical = 14.dp))

        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap), color = colors.inRange.copy(alpha = 0.10f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(colors.controlFill), contentAlignment = Alignment.Center) {
                    Icon(AapsIcons.CloudUpload, contentDescription = null, tint = colors.inRange, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Back up your settings", style = AapsTheme.type.listTitle, color = colors.textPrimary)
                    Text("Keep a copy you can restore after a reinstall", style = AapsTheme.type.caption, color = colors.textTertiary)
                }
            }
        }

        Box(
            Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)
                .clip(AapsTheme.shape.button).background(colors.accent).clickable(onClick = onExport).padding(vertical = 15.dp),
            contentAlignment = Alignment.Center
        ) { Text("Export settings now", style = AapsTheme.type.listTitle, color = colors.onAccent) }

        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column {
                ActionRow(AapsIcons.Download, "Import settings", "Restore from a backup file", onImport)
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                ActionRow(AapsIcons.Description, "Export log files", "Share diagnostics logs", onExportLogs)
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                ActionRow(AapsIcons.ChevronRight, "More options", "Cloud backup, CSV, database tools", onMore)
            }
        }

        if (state.version.isNotBlank()) AapsCard(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("App version", style = AapsTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.version, style = AapsTheme.type.listTitle, color = colors.textPrimary)
                    if (state.buildInfo.isNotBlank()) Text(state.buildInfo, style = AapsTheme.type.caption, color = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(AapsTheme.shape.iconButton).background(colors.accentTint), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.accentOnLight, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong)
            Text(sub, style = AapsTheme.type.caption, color = colors.textTertiary)
        }
        Icon(AapsIcons.ChevronRight, contentDescription = null, tint = colors.textTertiary)
    }
}

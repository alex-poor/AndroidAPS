package app.aaps.plugins.configuration.maintenance.cloud.compose

import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SecondaryButton
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.theme.AapsTheme

/** How a metadata row is tinted — the status of that imported value. */
enum class ImportTint { NORMAL, WARN, ERROR }

/** One metadata row in the import summary: an icon, a label, a status icon, a tap. */
data class ImportSummaryRow(
    val label: String,
    val metaIcon: Int,
    val statusIcon: Int,
    val tint: ImportTint,
    val onTap: () -> Unit
)

/** The pre-import summary: the message, the metadata rows, optional details, and the action. */
@Composable
fun ImportSummarySheet(
    title: String,
    message: String,
    rows: List<ImportSummaryRow>,
    detailsLabel: String?,
    onDetails: () -> Unit,
    cancelLabel: String,
    importLabel: String?,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit
) {
    SheetSurface(title = title, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, style = AapsTheme.type.body, color = AapsTheme.colors.textSecondary)
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
            ) {
                rows.forEach { r ->
                    val color = when (r.tint) {
                        ImportTint.WARN  -> AapsTheme.colors.high
                        ImportTint.ERROR -> AapsTheme.colors.low
                        ImportTint.NORMAL -> AapsTheme.colors.textPrimary
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = r.onTap).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(painterResource(r.metaIcon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
                        Text(r.label, style = AapsTheme.type.body, color = color, modifier = Modifier.weight(1f))
                        Icon(painterResource(r.statusIcon), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (detailsLabel != null) SecondaryButton(detailsLabel, onDetails, Modifier.fillMaxWidth())
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(cancelLabel, onCancel, Modifier.weight(1f))
                if (importLabel != null) PrimaryButton(importLabel, onImport, Modifier.weight(1f))
            }
        }
    }
}

/** The details view: the HTML the legacy dialog built, in a WebView. */
@Composable
fun ImportDetailsSheet(title: String, html: String, okLabel: String, onClose: () -> Unit) {
    SheetSurface(title = title, onClose = onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setLayerType(WebView.LAYER_TYPE_SOFTWARE, null)
                    }
                },
                update = { it.loadData(html, "text/html; charset=utf-8", "utf-8") },
                modifier = Modifier.fillMaxWidth().height(400.dp)
            )
            PrimaryButton(okLabel, onClose, Modifier.fillMaxWidth())
        }
    }
}

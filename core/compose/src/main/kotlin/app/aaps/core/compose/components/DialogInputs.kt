package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/** A multi-line notes input styled for the redesigned dialogs. */
@Composable
fun NotesField(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, label: String = "NOTES") {
    val colors = AapsTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = AapsType.label, color = colors.textSecondary)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(AapsShape.cardSmall)
                .background(colors.surface2)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .heightIn(min = 40.dp)
        ) {
            if (value.isEmpty()) Text("Add a note…", style = AapsType.body, color = colors.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                textStyle = AapsType.body.copy(color = colors.textPrimary),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** A title (+ optional sub) row with a trailing Material Switch, styled with the redesign tokens. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null
) {
    val colors = AapsTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = AapsType.listTitle, color = colors.textOnSurfaceStrong)
            if (sub != null) Text(sub, style = AapsType.caption, color = colors.textTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedTrackColor = colors.controlFill,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedBorderColor = colors.hairline
            )
        )
    }
}

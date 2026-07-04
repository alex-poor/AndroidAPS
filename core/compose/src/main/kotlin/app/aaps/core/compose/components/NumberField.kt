package app.aaps.core.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType
import java.util.Locale

/**
 * A labeled numeric input with −/+ steppers and direct keyboard entry — the redesign's replacement for
 * the legacy `PlusMinusEditText`. Fully controlled: [value] is the source of truth (so external
 * quick-add chips / steppers work), while free typing doesn't fight the cursor.
 */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onValue: (Double) -> Unit,
    step: Double,
    min: Double,
    max: Double,
    decimals: Int,
    modifier: Modifier = Modifier,
    unit: String = ""
) {
    val colors = AapsTheme.colors
    fun fmt(v: Double) = String.format(Locale.getDefault(), "%.${decimals}f", v)
    fun parse(s: String) = s.replace(',', '.').toDoubleOrNull() ?: 0.0
    var text by remember { mutableStateOf(fmt(value)) }
    // Reflect external changes (buttons / chips) without clobbering an in-progress edit.
    LaunchedEffect(value) { if (parse(text) != value) text = fmt(value) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotBlank()) Text(label.uppercase(Locale.getDefault()), style = AapsType.label, color = colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(minus = true) { onValue((value - step).coerceIn(min, max)) }
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .clip(AapsShape.cardSmall)
                    .background(colors.surface2)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            onValue(parse(it).coerceIn(min, max))
                        },
                        singleLine = true,
                        textStyle = AapsType.cardValue.copy(color = colors.textPrimary),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = if (decimals > 0 || min < 0) KeyboardType.Number else KeyboardType.Number)
                    )
                    if (unit.isNotBlank()) Text(unit, style = AapsType.body, color = colors.textTertiary, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            StepButton(minus = false) { onValue((value + step).coerceIn(min, max)) }
        }
    }
}

@Composable
private fun StepButton(minus: Boolean, onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (minus) colors.controlFill else colors.accentTint)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (minus) Icons.Rounded.Remove else Icons.Rounded.Add,
            contentDescription = if (minus) "decrease" else "increase",
            tint = if (minus) colors.textPrimary else colors.accentOnLight,
            modifier = Modifier.size(22.dp)
        )
    }
}

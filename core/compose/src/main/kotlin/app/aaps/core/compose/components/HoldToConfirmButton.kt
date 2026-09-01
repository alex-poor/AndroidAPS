package app.aaps.core.compose.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.theme.AapsTheme

/**
 * "Hold to deliver" control — the explicit replacement for AAPS's hidden long-press. Press and hold
 * fills an accent overlay left→right over [holdMillis]; completing the hold fires [onConfirm].
 * Releasing early cancels (the fill animates back). This is a UI affordance ONLY — the caller must
 * still run the same constraint + confirmation + delivery path.
 */
@Composable
fun HoldToConfirmButton(
    label: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = 1500,
    enabled: Boolean = true
) {
    val colors = AapsTheme.colors
    var holding by remember { mutableStateOf(false) }
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    // progress animates to 1f while holding, back to 0f on release
    val progress by animateFloatAsState(
        targetValue = if (holding) 1f else 0f,
        animationSpec = tween(durationMillis = if (holding) holdMillis else 220, easing = LinearEasing),
        label = "hold-progress"
    )
    // fire once the fill reaches the end
    LaunchedEffect(progress, holding) {
        if (holding && progress >= 1f) {
            holding = false
            currentOnConfirm()
        }
    }
    val container = if (enabled) colors.accent else colors.controlFill
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(AapsTheme.shape.button)
            .background(container)
            .then(
                if (enabled) Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holding = true
                            val released = try {
                                tryAwaitRelease()
                            } finally {
                                holding = false
                            }
                            released
                        }
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        // fill overlay
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .background(colors.accentOnLight.copy(alpha = 0.25f))
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = null,
                tint = if (enabled) colors.onAccent else colors.textTertiary,
                modifier = Modifier.size(20.dp).padding(end = 8.dp)
            )
            Text(label, style = AapsTheme.type.title, color = if (enabled) colors.onAccent else colors.textTertiary)
        }
    }
}

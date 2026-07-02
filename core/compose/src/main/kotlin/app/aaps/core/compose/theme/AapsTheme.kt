package app.aaps.core.compose.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extended palette that M3's [androidx.compose.material3.ColorScheme] can't express — the semantic
 * glucose colors and the accent (is-tappable) family. Access via [AapsTheme.colors].
 */
@Immutable
data class AapsColors(
    // chrome
    val background: Color = AapsPalette.background,
    val surface: Color = AapsPalette.surface,
    val surface2: Color = AapsPalette.surface2,
    val surface3: Color = AapsPalette.surface3,
    val bar: Color = AapsPalette.bar,
    val hairline: Color = AapsPalette.hairline,
    val divider: Color = AapsPalette.divider,
    val controlFill: Color = AapsPalette.controlFill,
    // text
    val textPrimary: Color = AapsPalette.textPrimary,
    val textSecondary: Color = AapsPalette.textSecondary,
    val textTertiary: Color = AapsPalette.textTertiary,
    val textOnSurfaceStrong: Color = AapsPalette.textOnSurfaceStrong,
    // semantic (glucose / status)
    val inRange: Color = AapsSemantic.inRange,
    val high: Color = AapsSemantic.high,
    val low: Color = AapsSemantic.low,
    val veryLow: Color = AapsSemantic.veryLow,
    val veryHigh: Color = AapsSemantic.veryHigh,
    val iob: Color = AapsSemantic.iob,
    // accent (interactive)
    val accent: Color = AapsAccent.accent,
    val accentOnLight: Color = AapsAccent.onLightSurface,
    val accentTint: Color = AapsAccent.tint,
    val accentTintStrong: Color = AapsAccent.tintStrong,
    val onAccent: Color = AapsAccent.onAccent
)

val LocalAapsColors = staticCompositionLocalOf { AapsColors() }

/**
 * Root theme for all redesigned AAPS surfaces. Dark only for now (handoff ships dark first; a light
 * scheme can be derived later). Wraps [MaterialTheme] so Material components pick up the type/shape,
 * and provides [LocalAapsColors] for the extended palette.
 */
@Composable
fun AapsTheme(content: @Composable () -> Unit) {
    val m3 = darkColorScheme(
        primary = AapsAccent.accent,
        onPrimary = AapsAccent.onAccent,
        primaryContainer = AapsAccent.tintStrong,
        onPrimaryContainer = AapsAccent.onLightSurface,
        background = AapsPalette.background,
        onBackground = AapsPalette.textPrimary,
        surface = AapsPalette.surface,
        onSurface = AapsPalette.textPrimary,
        surfaceVariant = AapsPalette.surface2,
        onSurfaceVariant = AapsPalette.textSecondary,
        outline = AapsPalette.hairline,
        outlineVariant = AapsPalette.divider,
        error = AapsSemantic.low,
        onError = AapsAccent.onAccent
    )
    MaterialTheme(
        colorScheme = m3,
        typography = AapsTypography,
        shapes = AapsShapes
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalAapsColors provides AapsColors(),
            content = content
        )
    }
}

/** Convenience accessors mirroring `MaterialTheme.*`. */
object AapsTheme {

    val colors: AapsColors
        @Composable @ReadOnlyComposable get() = LocalAapsColors.current
}

/**
 * Map a glucose value (in **mmol/L**, matching how the design specifies bands) to its semantic color.
 * Callers should pass the user's real thresholds; defaults follow the handoff gauge labels.
 */
fun glucoseColorMmol(
    mmol: Double,
    lowLimit: Double = 3.9,
    targetLow: Double = 5.5,
    targetHigh: Double = 7.0,
    highLimit: Double = 10.0,
    colors: AapsColors = AapsColors()
): Color = when {
    mmol < lowLimit * 0.72 -> colors.veryLow      // deep red (~< 2.8)
    mmol < targetLow       -> colors.low          // red (below target)
    mmol <= targetHigh     -> colors.inRange      // green (in range)
    mmol <= highLimit      -> colors.high         // amber (above target)
    else                   -> colors.veryHigh     // deep amber
}

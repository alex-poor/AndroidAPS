package app.aaps.core.compose.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat

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
    val scrim: Color = AapsPalette.scrim,
    val hairline: Color = AapsPalette.hairline,
    val divider: Color = AapsPalette.divider,
    val controlFill: Color = AapsPalette.controlFill,
    val switchTrackOff: Color = AapsPalette.switchTrackOff,
    val switchKnobOff: Color = AapsPalette.switchKnobOff,
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
val LocalAapsTextStyles = staticCompositionLocalOf { DefaultAapsTextStyles }
val LocalAapsShapes = staticCompositionLocalOf { DefaultAapsShapes }

/**
 * Root theme for all redesigned AAPS surfaces.
 *
 * Which palette it provides comes from [AapsSkinState] — the skin the user picked, and whether the
 * light or dark ground of that skin applies. Both are Compose snapshot state, so changing either
 * recomposes every screen immediately: no activity recreate, unlike the XML `AppTheme` half of the
 * app. [skin] and [mode] can be passed explicitly to pin a `@Preview` to one appearance.
 *
 * Wraps [MaterialTheme] so Material components pick up the type/shape, and provides
 * [LocalAapsColors] for the extended palette.
 */
@Composable
fun AapsTheme(
    skin: AapsSkin = AapsSkinState.skin,
    mode: AapsUiMode = AapsSkinState.mode,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        AapsUiMode.LIGHT  -> false
        AapsUiMode.DARK   -> true
        AapsUiMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = skin.colors(dark)

    // Status/nav bar icons have to flip with the ground or they vanish into it. Reactive, so it
    // tracks a skin change without waiting for the activity to be recreated.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowInsetsControllerCompat(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }

    val m3 = if (dark)
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentTintStrong,
            onPrimaryContainer = colors.accentOnLight,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.hairline,
            outlineVariant = colors.divider,
            error = colors.low,
            onError = colors.onAccent
        )
    else
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentTintStrong,
            onPrimaryContainer = colors.accentOnLight,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.hairline,
            outlineVariant = colors.divider,
            error = colors.low,
            onError = colors.onAccent
        )

    // Material's own typography and shapes are derived from the same tokens, so a dialog or a
    // switch follows the skin instead of staying on the platform default while the app around it
    // changes.
    MaterialTheme(
        colorScheme = m3,
        typography = aapsM3Typography(skin.fontFamily, skin.singleWeightFont),
        shapes = aapsM3Shapes(skin.shapes)
    ) {
        CompositionLocalProvider(
            LocalAapsColors provides colors,
            LocalAapsTextStyles provides skin.type,
            LocalAapsShapes provides skin.shapes,
            content = content
        )
    }
}

/** Convenience accessors mirroring `MaterialTheme.*`. */
object AapsTheme {

    val colors: AapsColors
        @Composable @ReadOnlyComposable get() = LocalAapsColors.current

    val type: AapsTextStyles
        @Composable @ReadOnlyComposable get() = LocalAapsTextStyles.current

    val shape: AapsShapes
        @Composable @ReadOnlyComposable get() = LocalAapsShapes.current
}

/**
 * Resolve an [AapsTone] — a tone named outside composition — against the live theme.
 *
 * This is the seam that keeps the palette swappable: state builders that cannot read
 * [LocalAapsColors] name the *meaning*, and the colour is chosen here, once, from whatever scheme
 * [AapsTheme] is currently providing.
 */
@Composable
@ReadOnlyComposable
fun AapsTone.color(): Color = with(AapsTheme.colors) {
    when (this@color) {
        AapsTone.InRange  -> inRange
        AapsTone.High     -> high
        AapsTone.Low      -> low
        AapsTone.VeryLow  -> veryLow
        AapsTone.VeryHigh -> veryHigh
        AapsTone.Accent   -> accent
        AapsTone.Neutral  -> textTertiary
    }
}

/**
 * Map a glucose value (in **mmol/L**, matching how the design specifies bands) to its semantic color.
 * Callers should pass the user's real thresholds; defaults follow the handoff gauge labels.
 *
 * [colors] is required rather than defaulted: defaulting it to `AapsColors()` handed back the dark
 * palette whatever skin was active, which is exactly the kind of back door the token system exists
 * to remove. Pass `AapsTheme.colors`.
 */
fun glucoseColorMmol(
    mmol: Double,
    lowLimit: Double = 3.9,
    targetLow: Double = 5.5,
    targetHigh: Double = 7.0,
    highLimit: Double = 10.0,
    colors: AapsColors
): Color = when {
    mmol < lowLimit * 0.72 -> colors.veryLow      // deep red (~< 2.8)
    mmol < targetLow       -> colors.low          // red (below target)
    mmol <= targetHigh     -> colors.inRange      // green (in range)
    mmol <= highLimit      -> colors.high         // amber (above target)
    else                   -> colors.veryHigh     // deep amber
}

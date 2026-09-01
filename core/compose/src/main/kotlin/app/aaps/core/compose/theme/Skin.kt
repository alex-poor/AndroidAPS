package app.aaps.core.compose.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Light / dark / follow-the-system. Mirrors the three values [app.aaps.core.keys.StringKey]
 * `GeneralDarkMode` has always stored, so the existing preference drives Compose without a new key.
 */
enum class AapsUiMode(val stringValue: String) {

    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {

        fun fromString(value: String?): AapsUiMode = entries.firstOrNull { it.stringValue == value } ?: SYSTEM
    }
}

/**
 * A named palette pair. A skin supplies BOTH grounds; whether the light or dark one is used is the
 * orthogonal [AapsUiMode] choice, so a skin never has to care which the user prefers. A skin with
 * only one sensible ground just passes the same [AapsColors] twice.
 *
 * Adding a skin is a data change — construct one, add it to [AapsSkins.all]. Nothing else moves,
 * because every colour in the redesigned UI already resolves through [LocalAapsColors].
 */
@Immutable
data class AapsSkin(
    /** Stable key persisted in preferences. Never rename one — a stored id that no longer resolves silently falls back to [AapsSkins.Default]. */
    val id: String,
    val label: String,
    val dark: AapsColors,
    val light: AapsColors
) {

    fun colors(dark: Boolean): AapsColors = if (dark) this.dark else this.light
}

/** The built-in skins. */
object AapsSkins {

    /** The redesign's own palette — the dark set the handoff shipped, plus a light ground derived from it. */
    val Default = AapsSkin(
        id = "default",
        label = "Default",
        dark = AapsColors(),
        light = AapsColors(
            background = AapsLightPalette.background,
            surface = AapsLightPalette.surface,
            surface2 = AapsLightPalette.surface2,
            surface3 = AapsLightPalette.surface3,
            bar = AapsLightPalette.bar,
            scrim = AapsLightPalette.scrim,
            hairline = AapsLightPalette.hairline,
            divider = AapsLightPalette.divider,
            controlFill = AapsLightPalette.controlFill,
            switchTrackOff = AapsLightPalette.switchTrackOff,
            switchKnobOff = AapsLightPalette.switchKnobOff,
            textPrimary = AapsLightPalette.textPrimary,
            textSecondary = AapsLightPalette.textSecondary,
            textTertiary = AapsLightPalette.textTertiary,
            textOnSurfaceStrong = AapsLightPalette.textOnSurfaceStrong,
            inRange = AapsLightSemantic.inRange,
            high = AapsLightSemantic.high,
            low = AapsLightSemantic.low,
            veryLow = AapsLightSemantic.veryLow,
            veryHigh = AapsLightSemantic.veryHigh,
            iob = AapsLightSemantic.iob,
            accent = AapsLightAccent.accent,
            accentOnLight = AapsLightAccent.onLightSurface,
            accentTint = AapsLightAccent.tint,
            accentTintStrong = AapsLightAccent.tintStrong,
            onAccent = AapsLightAccent.onAccent
        )
    )

    /**
     * True black for OLED. Deliberately the *smallest possible* second skin: it changes chrome only,
     * and lifts the semantic colours slightly because they lose contrast against #000 rather than
     * against the default near-black. Its job is to prove that swapping skins is a data change.
     */
    val Midnight = AapsSkin(
        id = "midnight",
        label = "Midnight",
        dark = AapsColors(
            background = androidx.compose.ui.graphics.Color(0xFF000000),
            surface = androidx.compose.ui.graphics.Color(0xFF0A0D12),
            surface2 = androidx.compose.ui.graphics.Color(0xFF11151C),
            surface3 = androidx.compose.ui.graphics.Color(0xFF05070A),
            bar = androidx.compose.ui.graphics.Color(0xFF000000),
            hairline = androidx.compose.ui.graphics.Color(0x1AFFFFFF),
            divider = androidx.compose.ui.graphics.Color(0x14FFFFFF),
            controlFill = androidx.compose.ui.graphics.Color(0x14FFFFFF),
            inRange = androidx.compose.ui.graphics.Color(0xFF4BE8A8),
            high = androidx.compose.ui.graphics.Color(0xFFFFC266),
            low = androidx.compose.ui.graphics.Color(0xFFFF6E7D)
        ),
        // No light ground of its own — a true-black skin in light mode is a contradiction, so it
        // borrows the default one rather than inventing a bad third palette.
        light = Default.light
    )

    val all: List<AapsSkin> = listOf(Default, Midnight)

    /** Resolve a persisted id. Unknown (or a leftover from the retired layout-skin preference) → [Default]. */
    fun byId(id: String?): AapsSkin = all.firstOrNull { it.id == id } ?: Default
}

/**
 * The app-wide skin selection.
 *
 * Deliberately a plain object holding Compose snapshot state rather than something injected: it lets
 * every one of the ~43 existing `AapsTheme { }` call sites keep working untouched, and because the
 * fields are snapshot state, writing one recomposes the whole UI on the spot — a skin change needs
 * no activity recreate. Written from `ThemeSwitcherPlugin` (main thread, from the preference), read
 * only by [AapsTheme].
 */
object AapsSkinState {

    var skinId: String by mutableStateOf(AapsSkins.Default.id)
    var mode: AapsUiMode by mutableStateOf(AapsUiMode.DARK)

    val skin: AapsSkin get() = AapsSkins.byId(skinId)
}

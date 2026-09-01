package app.aaps.core.compose.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow

/**
 * Which ground of a skin to render. Not chosen directly any more — it is a property of the selected
 * [AapsAppearance] — but it still maps onto the three values `GeneralDarkMode` stores, which is what
 * the XML half of the app reads through `AppCompatDelegate`.
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
 * A named palette pair. A skin may supply two grounds; which one renders is a property of the
 * [AapsAppearance] the user picked, so a skin never has to care. A skin with only one sensible
 * ground passes the same [AapsColors] twice and contributes a single appearance.
 *
 * Adding a skin is a data change — construct one, add it to [AapsSkins.all], and give it at least
 * one entry in [AapsAppearances.all]. Nothing else moves, because every colour in the redesigned UI
 * already resolves through [LocalAapsColors].
 */
@Immutable
data class AapsSkin(
    /** Stable key persisted in preferences. Never rename one — a stored id that no longer resolves silently falls back to [AapsSkins.Default]. */
    val id: String,
    val label: String,
    val dark: AapsColors,
    val light: AapsColors,
    // Type and shape are declared as SEEDS, not as finished scales, and do not vary between grounds
    // — a font does not change when the lights go out. Seeds rather than scales because storing both
    // a family and a ready-made set of styles lets the two disagree: a skin could name a pixel font,
    // forget to rebuild the styles, and ship Material components in one font and app text in another.
    // Deriving makes that unrepresentable, and it is what keeps a skin file short enough to hand-write.
    val fontFamily: androidx.compose.ui.text.font.FontFamily = HankenGrotesk,
    /** Set for a font that ships one weight; the scale then uses size alone for hierarchy. */
    val singleWeightFont: Boolean = false,
    val typeScale: Float = 1f,
    /** One number for the whole shape language. `0.dp` squares every corner, pills included. */
    val cornerRadius: Dp = 18.dp
) {

    val type: AapsTextStyles by lazy(LazyThreadSafetyMode.NONE) { aapsTextStyles(fontFamily, typeScale, singleWeightFont) }
    val shapes: AapsShapes by lazy(LazyThreadSafetyMode.NONE) { aapsShapes(cornerRadius) }

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

    val builtIn: List<AapsSkin> = listOf(Default, Midnight)

    /**
     * Skins unpacked from files, published by `SkinStore` once storage has been read.
     *
     * Snapshot state so installing or deleting one repaints the picker — and the app, if the deleted
     * skin was the active one — without anything having to be restarted.
     */
    var installed: List<AapsSkin> by mutableStateOf(emptyList())

    val all: List<AapsSkin> get() = builtIn + installed

    /** Resolve a persisted id. Unknown (or a leftover from the retired layout-skin preference) → [Default]. */
    fun byId(id: String?): AapsSkin = all.firstOrNull { it.id == id } ?: Default
}

/**
 * One entry in the appearance picker — what the user actually chooses.
 *
 * Skins and light/dark are orthogonal in the DATA model (a skin may supply two grounds), but they
 * are NOT orthogonal to a user: most skins are a single look, and offering "palette" and "light or
 * dark" as two independent settings produces combinations that quietly do nothing. So the picker is
 * flat, and each appearance names a (skin, mode) pair that is known to be worth choosing.
 *
 * [AapsSkins.Default] genuinely has two grounds, so it contributes the familiar three. Anything with
 * one look contributes one entry. Adding a skin to the picker is a line here.
 */
@Immutable
data class AapsAppearance(
    /** Stable key persisted in preferences. */
    val id: String,
    val label: String,
    val skin: AapsSkin,
    val mode: AapsUiMode
)

object AapsAppearances {

    val FollowSystem = AapsAppearance("system", "Follow system", AapsSkins.Default, AapsUiMode.SYSTEM)
    val Light = AapsAppearance("light", "Light", AapsSkins.Default, AapsUiMode.LIGHT)
    val Dark = AapsAppearance("dark", "Dark", AapsSkins.Default, AapsUiMode.DARK)

    /** Midnight is dark by definition — a true-black light theme is a contradiction, so it offers one entry. */
    val Midnight = AapsAppearance("midnight", "Midnight", AapsSkins.Midnight, AapsUiMode.DARK)

    val builtIn: List<AapsAppearance> = listOf(FollowSystem, Light, Dark, Midnight)

    /**
     * One entry per installed skin — never three, so a handful of installed skins cannot turn the
     * picker into a list nobody can scan.
     *
     * A skin supplying two grounds follows the device; a single-look skin takes the mode its own
     * ground implies, so a light-toned one does not leave the app's remaining XML screens in dark
     * mode around it.
     */
    private fun forInstalled(skin: AapsSkin) = AapsAppearance(
        id = skin.id,
        label = skin.label,
        skin = skin,
        mode = when {
            skin.light != skin.dark      -> AapsUiMode.SYSTEM
            skin.dark.background.isDark() -> AapsUiMode.DARK
            else                         -> AapsUiMode.LIGHT
        }
    )

    val all: List<AapsAppearance> get() = builtIn + AapsSkins.installed.map(::forInstalled)

    /** Resolve a persisted id; anything unrecognised falls back to [Dark], the look the app shipped with. */
    fun byId(id: String?): AapsAppearance = all.firstOrNull { it.id == id } ?: Dark
}

/** Whether a ground reads as dark, by relative luminance — the midpoint is enough to pick a mode. */
internal fun Color.isDark(): Boolean {
    fun ch(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * ch(red) + 0.7152 * ch(green) + 0.0722 * ch(blue) < 0.5
}

/**
 * The app-wide appearance selection.
 *
 * Deliberately a plain object holding Compose snapshot state rather than something injected: it lets
 * every one of the ~43 existing `AapsTheme { }` call sites keep working untouched, and because the
 * field is snapshot state, writing it recomposes the whole UI on the spot — an appearance change
 * needs no activity recreate. Written from `ThemeSwitcherPlugin` (main thread, from the preference),
 * read only by [AapsTheme].
 */
object AapsSkinState {

    var appearanceId: String by mutableStateOf(AapsAppearances.Dark.id)

    val appearance: AapsAppearance get() = AapsAppearances.byId(appearanceId)
    val skin: AapsSkin get() = appearance.skin
    val mode: AapsUiMode get() = appearance.mode
}

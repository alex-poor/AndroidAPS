package app.aaps.core.compose.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The on-disk form of a skin: what lands in `skin.json` inside a `.aapsskin` bundle.
 *
 * Every colour is optional and falls back to the default skin's value for that ground. That is the
 * whole ergonomic argument for this format — a palette has 26 tokens per ground, and demanding 52
 * hex values would make a hand-written skin a chore and, worse, make it easy to get one of them
 * catastrophically wrong. A skin that only cares about its greens says so in ten lines and inherits
 * the rest.
 *
 * Omitting [light] entirely marks a single-look skin: it renders its dark ground whatever the user's
 * light/dark preference says, the way a Game Boy palette or a true-black theme sensibly should.
 */
@Serializable
data class SkinSpec(

    /** Bumped only for a breaking change; an unknown version is refused rather than guessed at. */
    @SerialName("formatVersion") val formatVersion: Int = CURRENT_FORMAT_VERSION,

    val id: String,
    val label: String,

    /** Author and description are carried for the sharing UI; neither affects rendering. */
    val author: String? = null,
    val description: String? = null,

    /** One number for the whole shape language. 0 squares every corner, pills included. */
    val cornerRadius: Float? = null,

    val font: FontSpec? = null,

    val dark: PaletteSpec = PaletteSpec(),
    /** Omit for a single-look skin. */
    val light: PaletteSpec? = null
) {

    @Serializable
    data class FontSpec(
        /** File name inside the bundle. Omit to keep the app's own font. */
        val file: String? = null,
        /** Set for a font shipping one weight, so the scale stops asking for bolds it cannot draw. */
        val singleWeight: Boolean = false,
        val scale: Float = 1f
    )

    /**
     * A palette. Names match [AapsColors] one for one, so what you read in the app's source is what
     * you write in the file.
     */
    @Serializable
    data class PaletteSpec(
        val background: String? = null,
        val surface: String? = null,
        val surface2: String? = null,
        val surface3: String? = null,
        val bar: String? = null,
        val scrim: String? = null,
        val hairline: String? = null,
        val divider: String? = null,
        val controlFill: String? = null,
        val switchTrackOff: String? = null,
        val switchKnobOff: String? = null,
        val textPrimary: String? = null,
        val textSecondary: String? = null,
        val textTertiary: String? = null,
        val textOnSurfaceStrong: String? = null,
        val inRange: String? = null,
        val high: String? = null,
        val low: String? = null,
        val veryLow: String? = null,
        val veryHigh: String? = null,
        val iob: String? = null,
        val accent: String? = null,
        val accentOnLight: String? = null,
        val accentTint: String? = null,
        val accentTintStrong: String? = null,
        val onAccent: String? = null
    ) {

        /** Resolve against [base], which supplies anything this palette did not name. */
        fun toColors(base: AapsColors): AapsColors = AapsColors(
            background = background.orDefault(base.background),
            surface = surface.orDefault(base.surface),
            surface2 = surface2.orDefault(base.surface2),
            surface3 = surface3.orDefault(base.surface3),
            bar = bar.orDefault(base.bar),
            scrim = scrim.orDefault(base.scrim),
            hairline = hairline.orDefault(base.hairline),
            divider = divider.orDefault(base.divider),
            controlFill = controlFill.orDefault(base.controlFill),
            switchTrackOff = switchTrackOff.orDefault(base.switchTrackOff),
            switchKnobOff = switchKnobOff.orDefault(base.switchKnobOff),
            textPrimary = textPrimary.orDefault(base.textPrimary),
            textSecondary = textSecondary.orDefault(base.textSecondary),
            textTertiary = textTertiary.orDefault(base.textTertiary),
            textOnSurfaceStrong = textOnSurfaceStrong.orDefault(base.textOnSurfaceStrong),
            inRange = inRange.orDefault(base.inRange),
            high = high.orDefault(base.high),
            low = low.orDefault(base.low),
            veryLow = veryLow.orDefault(base.veryLow),
            veryHigh = veryHigh.orDefault(base.veryHigh),
            iob = iob.orDefault(base.iob),
            accent = accent.orDefault(base.accent),
            accentOnLight = accentOnLight.orDefault(base.accentOnLight),
            accentTint = accentTint.orDefault(base.accentTint),
            accentTintStrong = accentTintStrong.orDefault(base.accentTintStrong),
            onAccent = onAccent.orDefault(base.onAccent)
        )
    }

    /**
     * Build the runtime skin.
     *
     * @param fontFamily the family loaded from the bundle, or null to keep the app's own.
     * @throws SkinFormatException if a colour string is malformed or the format version is unknown.
     */
    fun toSkin(fontFamily: FontFamily? = null): AapsSkin {
        if (formatVersion > CURRENT_FORMAT_VERSION)
            throw SkinFormatException("This skin needs a newer version of AAPS (format $formatVersion, this build understands $CURRENT_FORMAT_VERSION).")
        if (id.isBlank()) throw SkinFormatException("Skin id is empty.")
        if (label.isBlank()) throw SkinFormatException("Skin label is empty.")
        if (id in RESERVED_IDS) throw SkinFormatException("'$id' is a built-in skin id; choose another.")

        val darkColors = dark.toColors(AapsSkins.Default.dark)
        return AapsSkin(
            id = id,
            label = label,
            dark = darkColors,
            // A single-look skin renders its one palette on both grounds rather than falling back to
            // the default light one, which would silently discard its identity in light mode.
            light = light?.toColors(AapsSkins.Default.light) ?: darkColors,
            fontFamily = fontFamily ?: HankenGrotesk,
            singleWeightFont = font?.singleWeight ?: false,
            typeScale = font?.scale ?: 1f,
            cornerRadius = cornerRadius?.dp ?: 18.dp
        )
    }

    companion object {

        const val CURRENT_FORMAT_VERSION = 1

        /** The manifest inside a bundle. */
        const val MANIFEST_NAME = "skin.json"

        /** Built-in ids a file may not shadow, or selecting one would become ambiguous. */
        val RESERVED_IDS = setOf("default", "midnight", "system", "light", "dark")

        val json = Json {
            ignoreUnknownKeys = true      // a newer minor format stays readable
            prettyPrint = true
            encodeDefaults = false        // keep exported files short
        }

        fun parse(text: String): SkinSpec =
            try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                throw SkinFormatException("Could not read ${MANIFEST_NAME}: ${e.message}", e)
            }
    }
}

class SkinFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parse `#RRGGBB` or `#AARRGGBB` (the leading `#` optional).
 *
 * @throws SkinFormatException with the offending text, because "invalid colour" alone in a
 *   26-field file is not a diagnosis.
 */
internal fun String.parseSkinColor(): Color {
    val hex = trim().removePrefix("#")
    if (!hex.matches(Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")))
        throw SkinFormatException("'$this' is not a colour — expected #RRGGBB or #AARRGGBB.")
    val value = hex.toLong(16)
    return if (hex.length == 6) Color(0xFF000000L or value) else Color(value)
}

private fun String?.orDefault(fallback: Color): Color = this?.parseSkinColor() ?: fallback

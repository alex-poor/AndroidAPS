package app.aaps.core.compose.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Legibility rules every palette has to satisfy before it is allowed on screen.
 *
 * This lives in main rather than in the test because the rules have two callers: the unit test that
 * guards the built-in skins, and the loader that accepts skin files off disk. A skin file is
 * untrusted input written by hand at one in the morning, and the failure mode is not a cosmetic one
 * — this app decides insulin, and the number on the hero is the number the user acts on. A palette
 * whose text disappears into its own background has to be refused, not merely frowned at.
 */
object SkinValidation {

    /** WCAG 2.1 minimum for text. */
    const val MIN_TEXT_CONTRAST = 4.5

    /** WCAG 2.1 minimum for large text and meaningful graphics (1.4.11). */
    const val MIN_GRAPHIC_CONTRAST = 3.0

    /**
     * Perceptual-distance floor between two colours that must not be confused. One just-noticeable
     * difference is ~2.3, so this is a wide margin — chosen so the glucose bands stay separable at
     * dot size and through a screen protector.
     */
    const val MIN_DELTA_E = 20.0

    /** WCAG 2.1 relative luminance. Alpha is ignored — the tokens checked here are opaque. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    /** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** CIELAB, D65. */
    private fun lab(c: Color): Triple<Double, Double, Double> {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = channel(c.red)
        val g = channel(c.green)
        val b = channel(c.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3) else 7.787 * t + 16.0 / 116
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /**
     * CIE76 colour difference.
     *
     * Used instead of a contrast ratio for "can these two be told apart", because luminance answers
     * a different question: amber and green sit at a ratio of 1.09 while being nothing alike. A
     * single-hue palette passes this only if it spends real lightness steps on the bands, which is
     * the right bar for one to clear.
     */
    fun deltaE(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    /**
     * Every problem with [colors], as human-readable sentences. Empty means the palette is safe to
     * show. Phrased for someone editing a skin file, so each one names the offending token, what it
     * measured, and what it needed.
     */
    fun problems(colors: AapsColors): List<String> {
        val out = mutableListOf<String>()

        fun needContrast(inkName: String, ink: Color, groundName: String, ground: Color, floor: Double) {
            val v = contrast(ink, ground)
            if (v < floor) out += "%s on %s has contrast %.1f:1, needs at least %.1f:1".format(inkName, groundName, v, floor)
        }

        fun needDistinct(aName: String, a: Color, bName: String, b: Color) {
            val v = deltaE(a, b)
            if (v < MIN_DELTA_E) out += "%s and %s are too alike to tell apart (difference %.0f, needs at least %.0f)".format(aName, bName, v, MIN_DELTA_E)
        }

        val grounds = listOf(
            "background" to colors.background, "surface" to colors.surface,
            "surface2" to colors.surface2, "bar" to colors.bar
        )
        listOf(
            "textPrimary" to colors.textPrimary,
            "textSecondary" to colors.textSecondary,
            "textOnSurfaceStrong" to colors.textOnSurfaceStrong
        ).forEach { (inkName, ink) ->
            grounds.forEach { (groundName, ground) -> needContrast(inkName, ink, groundName, ground, MIN_TEXT_CONTRAST) }
        }

        // Captions and hints carry supporting text only, so they get the large-text floor.
        listOf("background" to colors.background, "surface" to colors.surface).forEach { (groundName, ground) ->
            needContrast("textTertiary", colors.textTertiary, groundName, ground, MIN_GRAPHIC_CONTRAST)
        }

        listOf(
            "inRange" to colors.inRange, "high" to colors.high, "low" to colors.low,
            "veryLow" to colors.veryLow, "veryHigh" to colors.veryHigh, "accent" to colors.accent
        ).forEach { (token, colour) ->
            needContrast(token, colour, "surface", colors.surface, MIN_GRAPHIC_CONTRAST)
        }

        needContrast("onAccent", colors.onAccent, "accent", colors.accent, MIN_TEXT_CONTRAST)

        needDistinct("low", colors.low, "inRange", colors.inRange)
        needDistinct("high", colors.high, "inRange", colors.inRange)
        needDistinct("low", colors.low, "high", colors.high)
        needDistinct("veryLow", colors.veryLow, "low", colors.low)
        needDistinct("veryHigh", colors.veryHigh, "high", colors.high)

        return out
    }

    /** Every problem across both of a skin's grounds, each prefixed with the ground it came from. */
    fun problems(skin: AapsSkin): List<String> =
        problems(skin.dark).map { "dark: $it" } + problems(skin.light).map { "light: $it" }
}

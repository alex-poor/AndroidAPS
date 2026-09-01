package app.aaps.core.compose.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A skin is data, and data gets edited. This is the guard that stops an edit shipping a palette you
 * cannot read your glucose off.
 *
 * The app decides insulin, and the number on the hero is the number the user acts on. A skin whose
 * text disappears into its own background is not a cosmetic bug — so every skin, on both of its
 * grounds, has to clear WCAG contrast on the text and status colours before it can ship. When
 * external skin files land, the loader should run these same checks and refuse the file.
 */
class SkinContrastTest {

    private companion object {

        /**
         * Perceptual-distance floor for two colours that must not be confused. One JND is ~2.3, so
         * this is a wide margin, chosen so the bands stay separable at dot size and through a screen
         * protector — not fitted to the current palettes (their tightest real pair is ~25).
         */
        const val MIN_DELTA_E = 20.0
    }

    /** WCAG 2.1 relative luminance. Alpha is ignored — the tokens checked here are all opaque. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Every skin on both of its grounds, named so a failure says which one broke. */
    private fun grounds(): List<Pair<String, AapsColors>> =
        AapsSkins.all.flatMap { listOf("${it.id}/dark" to it.dark, "${it.id}/light" to it.light) }

    private fun assertContrast(where: String, ink: Color, ground: Color, floor: Double) =
        assertWithMessage(where).that(contrast(ink, ground)).isAtLeast(floor)

    /**
     * CIELAB, D65. Needed because WCAG's luminance ratio answers "can I read this ink on that
     * ground", which is NOT the question "can these two status colours be told apart". Amber and
     * green sit at almost identical luminance (ratio 1.09) yet nobody confuses them.
     */
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

    /** CIE76 colour difference. ~2.3 is one just-noticeable difference. */
    private fun deltaE(a: Color, b: Color): Double {
        val (l1, a1, b1) = lab(a)
        val (l2, a2, b2) = lab(b)
        return sqrt((l1 - l2).pow(2) + (a1 - a2).pow(2) + (b1 - b2).pow(2))
    }

    private fun assertDistinct(where: String, a: Color, b: Color) =
        assertWithMessage(where).that(deltaE(a, b)).isAtLeast(MIN_DELTA_E)

    @Test
    fun `body text clears 4_5 to 1 on every surface of every skin`() {
        grounds().forEach { (skin, c) ->
            val surfaces = listOf("background" to c.background, "surface" to c.surface, "surface2" to c.surface2, "bar" to c.bar)
            val inks = listOf("textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary, "textOnSurfaceStrong" to c.textOnSurfaceStrong)
            surfaces.forEach { (groundName, ground) ->
                inks.forEach { (inkName, ink) ->
                    assertContrast("$skin: $inkName on $groundName", ink, ground, 4.5)
                }
            }
        }
    }

    @Test
    fun `tertiary text clears 3 to 1 on every surface of every skin`() {
        // Captions and hints only ever carry supporting text, so they get the large-text floor.
        grounds().forEach { (skin, c) ->
            listOf("background" to c.background, "surface" to c.surface).forEach { (groundName, ground) ->
                assertContrast("$skin: textTertiary on $groundName", c.textTertiary, ground, 3.0)
            }
        }
    }

    @Test
    fun `glucose status colours clear 3 to 1 on the card surface`() {
        // These carry clinical meaning — at hero size and as small dots. Below 3:1 the hypo/hyper
        // signal is not reliably visible.
        grounds().forEach { (skin, c) ->
            listOf(
                "inRange" to c.inRange, "high" to c.high, "low" to c.low,
                "veryLow" to c.veryLow, "veryHigh" to c.veryHigh, "accent" to c.accent
            ).forEach { (token, colour) ->
                assertContrast("$skin: $token on surface", colour, c.surface, 3.0)
            }
        }
    }

    @Test
    fun `ink on a solid accent fill is legible`() {
        grounds().forEach { (skin, c) ->
            assertContrast("$skin: onAccent on accent", c.onAccent, c.accent, 4.5)
        }
    }

    @Test
    fun `the glucose bands are tellable apart from one another`() {
        // Contrast-against-the-background is not enough: a skin whose low and high look alike is
        // unsafe however well each reads on its own. Measured as perceptual distance, not luminance
        // ratio — a low-lightness-contrast pair like amber/green is still obviously two colours.
        //
        // A single-hue skin (a Game Boy palette) passes this if, and only if, it spends real
        // LIGHTNESS steps on the bands rather than four flavours of the same green. That is the
        // right bar for it to clear.
        grounds().forEach { (skin, c) ->
            assertDistinct("$skin: low vs inRange", c.low, c.inRange)
            assertDistinct("$skin: high vs inRange", c.high, c.inRange)
            assertDistinct("$skin: low vs high", c.low, c.high)
            // The severe bands have to read as more than a shade of the ordinary ones.
            assertDistinct("$skin: veryLow vs low", c.veryLow, c.low)
            assertDistinct("$skin: veryHigh vs high", c.veryHigh, c.high)
        }
    }

    @Test
    fun `every skin id is unique and resolvable, and an unknown id falls back`() {
        assertThat(AapsSkins.all.map { it.id }).containsNoDuplicates()
        AapsSkins.all.forEach { assertThat(AapsSkins.byId(it.id)).isEqualTo(it) }
        // A value left behind by the retired layout-skin preference must not strand the user.
        assertThat(AapsSkins.byId("app.aaps.plugins.main.skins.SkinClassic")).isEqualTo(AapsSkins.Default)
        assertThat(AapsSkins.byId(null)).isEqualTo(AapsSkins.Default)
        assertThat(AapsSkins.byId("")).isEqualTo(AapsSkins.Default)
    }

    @Test
    fun `every appearance is unique, resolvable, and backed by a registered skin`() {
        assertThat(AapsAppearances.all.map { it.id }).containsNoDuplicates()
        assertThat(AapsAppearances.all.map { it.label }).containsNoDuplicates()
        AapsAppearances.all.forEach {
            assertWithMessage("appearance ${it.id} resolves").that(AapsAppearances.byId(it.id)).isEqualTo(it)
            assertWithMessage("appearance ${it.id} uses a registered skin").that(AapsSkins.all).contains(it.skin)
        }
        // Unknown / pre-flattening values land on the look the app shipped with rather than nothing.
        assertThat(AapsAppearances.byId(null)).isEqualTo(AapsAppearances.Dark)
        assertThat(AapsAppearances.byId("")).isEqualTo(AapsAppearances.Dark)
        assertThat(AapsAppearances.byId("default")).isEqualTo(AapsAppearances.Dark)
    }

    @Test
    fun `no appearance is a duplicate of another, so none of them can look like a no-op`() {
        // The whole reason the picker was flattened: two settings allowed combinations that silently
        // rendered something already on the list. Every entry must resolve to a distinct palette.
        val rendered = AapsAppearances.all.associate { appearance ->
            appearance.id to when (appearance.mode) {
                AapsUiMode.LIGHT  -> appearance.skin.light
                AapsUiMode.DARK   -> appearance.skin.dark
                // "Follow system" is the only entry allowed to resolve two ways; it is the default
                // pairing, and both of its grounds are covered by the other entries.
                AapsUiMode.SYSTEM -> null
            }
        }.filterValues { it != null }
        assertWithMessage("each fixed appearance renders a distinct palette")
            .that(rendered.values.toSet()).hasSize(rendered.size)
    }

    @Test
    fun `ui mode round-trips through its stored string`() {
        AapsUiMode.entries.forEach { assertThat(AapsUiMode.fromString(it.stringValue)).isEqualTo(it) }
        assertThat(AapsUiMode.fromString("nonsense")).isEqualTo(AapsUiMode.SYSTEM)
        assertThat(AapsUiMode.fromString(null)).isEqualTo(AapsUiMode.SYSTEM)
    }
}

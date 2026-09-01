package app.aaps.core.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * The type and shape halves of a skin.
 *
 * Both are derived from seeds rather than declared token by token, so what needs guarding is the
 * derivation: that one radius really does reach every corner, that a single-weight font is not
 * asked for weights it does not have, and that the scale keeps its own ordering.
 */
class SkinTypeAndShapeTest {

    private fun AapsShapes.all() = listOf(
        "hero" to hero, "card" to card, "cardSmall" to cardSmall, "pill" to pill,
        "button" to button, "iconButton" to iconButton, "sheet" to sheet, "extraSmall" to extraSmall
    )

    @Test
    fun `a zero radius squares every corner, pills included`() {
        // The point of the seed. A hard-edged skin should not have to name eight tokens, and a
        // stadium-shaped pill surviving would be the one thing breaking the language.
        aapsShapes(0.dp).all().forEach { (name, shape) ->
            assertWithMessage("$name at radius 0").that(shape).isEqualTo(RoundedCornerShape(0.dp))
        }
    }

    @Test
    fun `the default seed reproduces the handoff radii`() {
        val s = aapsShapes()
        assertThat(s.card).isEqualTo(RoundedCornerShape(18.dp))
        assertThat(s.hero).isEqualTo(RoundedCornerShape(18.dp * 1.33f))
        assertThat(s.pill).isEqualTo(RoundedCornerShape(999.dp))
        assertThat(DefaultAapsShapes).isEqualTo(s)
    }

    @Test
    fun `a negative radius is clamped rather than producing an inverted shape`() {
        assertThat(aapsShapes((-8).dp).card).isEqualTo(RoundedCornerShape(0.dp))
    }

    @Test
    fun `a single-weight font is never asked for a weight it does not have`() {
        // Synthetic bold smears exactly the sharp edges a pixel font exists to provide, so these
        // skins flatten every role and lean on size for hierarchy instead.
        val flat = aapsTextStyles(singleWeight = true)
        listOf(
            "hero" to flat.hero, "bigValue" to flat.bigValue, "cardValue" to flat.cardValue,
            "title" to flat.title, "listTitle" to flat.listTitle, "body" to flat.body,
            "label" to flat.label, "caption" to flat.caption
        ).forEach { (name, style) ->
            assertWithMessage("$name weight").that(style.fontWeight).isEqualTo(FontWeight.Normal)
        }
    }

    @Test
    fun `the default scale keeps its weight hierarchy`() {
        val t = aapsTextStyles()
        assertThat(t.hero.fontWeight).isEqualTo(FontWeight.ExtraBold)
        assertThat(t.body.fontWeight).isEqualTo(FontWeight.Medium)
        assertThat(t.caption.fontWeight).isEqualTo(FontWeight.Normal)
        assertThat(DefaultAapsTextStyles).isEqualTo(t)
    }

    @Test
    fun `sizes stay ordered at any scale, so hierarchy survives scaling`() {
        listOf(0.85f, 1f, 1.5f).forEach { scale ->
            val t = aapsTextStyles(scale = scale)
            val descending = listOf(t.hero, t.bigValue, t.cardValue, t.title, t.body, t.caption)
                .map { it.fontSize.value }
            descending.zipWithNext().forEach { (bigger, smaller) ->
                assertWithMessage("scale $scale: $bigger should exceed $smaller").that(bigger).isGreaterThan(smaller)
            }
        }
    }

    @Test
    fun `every skin derives its type from its own font, so the two cannot disagree`() {
        // The trap this replaced: a skin naming a font but keeping the default styles would put
        // Material components in one typeface and app text in another.
        AapsSkins.all.forEach { skin ->
            listOf("hero" to skin.type.hero, "body" to skin.type.body, "caption" to skin.type.caption)
                .forEach { (role, style) ->
                    assertWithMessage("${skin.id} $role font").that(style.fontFamily).isEqualTo(skin.fontFamily)
                }
            assertWithMessage("${skin.id} shapes").that(skin.shapes).isEqualTo(aapsShapes(skin.cornerRadius))
        }
    }
}

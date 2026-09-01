package app.aaps.core.compose.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The skin file format.
 *
 * A skin file is untrusted, hand-written input, so what matters is less that a good file loads than
 * that a bad one is rejected with something the author can act on, and that a partial file inherits
 * sane values rather than half a palette.
 */
class SkinSpecTest {

    @Test
    fun `a minimal skin inherits everything it did not name`() {
        // The ergonomic claim the format is built on: a skin cares about a handful of colours and
        // says so, rather than restating 52 tokens.
        val spec = SkinSpec.parse(
            """
            { "id": "minimal", "label": "Minimal", "dark": { "accent": "#FF0000" } }
            """.trimIndent()
        )
        val skin = spec.toSkin()
        assertThat(skin.dark.accent).isEqualTo(Color(0xFFFF0000))
        assertThat(skin.dark.background).isEqualTo(AapsSkins.Default.dark.background)
        assertThat(skin.dark.textPrimary).isEqualTo(AapsSkins.Default.dark.textPrimary)
        assertThat(skin.cornerRadius).isEqualTo(18.dp)
        assertThat(skin.fontFamily).isEqualTo(HankenGrotesk)
    }

    @Test
    fun `omitting the light palette makes a single-look skin rather than a half-default one`() {
        // Falling back to the default LIGHT ground would silently discard the skin's identity the
        // moment the user switched mode -- which is exactly the dead-combination bug the flattened
        // appearance picker exists to prevent.
        val spec = SkinSpec.parse("""{ "id": "gb", "label": "GB", "dark": { "background": "#0F380F" } }""")
        val skin = spec.toSkin()
        assertThat(skin.light).isEqualTo(skin.dark)
        assertThat(skin.light.background).isEqualTo(Color(0xFF0F380F))
    }

    @Test
    fun `a zero corner radius survives the round trip`() {
        val skin = SkinSpec.parse("""{ "id": "sq", "label": "Sq", "cornerRadius": 0 }""").toSkin()
        assertThat(skin.cornerRadius).isEqualTo(0.dp)
        assertThat(skin.shapes).isEqualTo(aapsShapes(0.dp))
    }

    @Test
    fun `colours accept both six and eight digit hex, with or without the hash`() {
        assertThat("#FF0000".parseSkinColor()).isEqualTo(Color(0xFFFF0000))
        assertThat("FF0000".parseSkinColor()).isEqualTo(Color(0xFFFF0000))
        assertThat("#80FF0000".parseSkinColor()).isEqualTo(Color(0x80FF0000))
    }

    @Test
    fun `a malformed colour names itself in the error`() {
        // "invalid colour" alone, in a file with 52 of them, is not a diagnosis.
        val e = assertThrows<SkinFormatException> { "#GGGGGG".parseSkinColor() }
        assertThat(e.message).contains("#GGGGGG")
    }

    @Test
    fun `a future format version is refused rather than guessed at`() {
        val spec = SkinSpec.parse("""{ "formatVersion": 99, "id": "x", "label": "X" }""")
        val e = assertThrows<SkinFormatException> { spec.toSkin() }
        assertThat(e.message).contains("newer version")
    }

    @Test
    fun `a file may not shadow a built-in id`() {
        SkinSpec.RESERVED_IDS.forEach { reserved ->
            val spec = SkinSpec.parse("""{ "id": "$reserved", "label": "Impostor" }""")
            assertWithMessage("reserved id '$reserved'").that(
                assertThrows<SkinFormatException> { spec.toSkin() }.message
            ).contains("built-in")
        }
    }

    @Test
    fun `empty id or label is refused`() {
        assertThrows<SkinFormatException> { SkinSpec.parse("""{ "id": "", "label": "X" }""").toSkin() }
        assertThrows<SkinFormatException> { SkinSpec.parse("""{ "id": "x", "label": " " }""").toSkin() }
    }

    @Test
    fun `unreadable json fails with the manifest named, not a stack trace`() {
        val e = assertThrows<SkinFormatException> { SkinSpec.parse("{ this is not json") }
        assertThat(e.message).contains(SkinSpec.MANIFEST_NAME)
    }

    @Test
    fun `unknown keys are tolerated so a newer minor format still loads`() {
        val skin = SkinSpec.parse("""{ "id": "x", "label": "X", "somethingNew": 42 }""").toSkin()
        assertThat(skin.id).isEqualTo("x")
    }

    @Test
    fun `a skin file that would be unreadable is caught by the same rules as a built-in`() {
        // The end-to-end claim: parsing succeeds, validation is what refuses it.
        val spec = SkinSpec.parse(
            """
            {
              "id": "unreadable", "label": "Unreadable",
              "dark": { "background": "#101010", "surface": "#101010", "textPrimary": "#111111" }
            }
            """.trimIndent()
        )
        val problems = SkinValidation.problems(spec.toSkin())
        assertThat(problems).isNotEmpty()
        assertWithMessage("names the offending token").that(problems.any { it.contains("textPrimary") }).isTrue()
    }

    @Test
    fun `a well-formed skin round-trips through serialisation`() {
        val original = SkinSpec(
            id = "roundtrip", label = "Round Trip", author = "alex", cornerRadius = 0f,
            font = SkinSpec.FontSpec(file = "font.ttf", singleWeight = true),
            dark = SkinSpec.PaletteSpec(background = "#0F380F", accent = "#8BAC0F")
        )
        val text = SkinSpec.json.encodeToString(SkinSpec.serializer(), original)
        assertThat(SkinSpec.parse(text)).isEqualTo(original)
    }
}

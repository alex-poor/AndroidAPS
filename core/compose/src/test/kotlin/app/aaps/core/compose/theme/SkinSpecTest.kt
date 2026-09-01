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
    fun `a single-hue skin can satisfy every rule`() {
        // The Game Boy case, kept as a regression test because it nearly cost the rules their teeth.
        // Fitting bands into grounds chosen first makes a monochrome palette look impossible, and the
        // tempting conclusion is that the band-separation floor is too strict. It is not: solved
        // jointly, one hue clears the same 20 that a full-colour palette does. If a future change to
        // the floors breaks this, the palette is the thing to re-solve -- not the floor to lower.
        val spec = SkinSpec.parse(
            """
            {
              "formatVersion": 1,
              "id": "gameboy",
              "label": "Game Boy",
              "author": "alex",
              "description": "DMG monochrome. One hue, hard corners, bands separated by lightness.",
              "cornerRadius": 0,
              "dark": {
                "background": "#1B2300",
                "surface": "#222E00",
                "surface2": "#293800",
                "surface3": "#161B00",
                "bar": "#161B00",
                "hairline": "#22C5DB7A",
                "divider": "#1AC5DB7A",
                "controlFill": "#1AC5DB7A",
                "textPrimary": "#C5DB7A",
                "textSecondary": "#B5CA6B",
                "textTertiary": "#7B8E3C",
                "textOnSurfaceStrong": "#B7CD6D",
                "inRange": "#698023",
                "high": "#A4BA5B",
                "low": "#E8FD9A",
                "veryHigh": "#7DA300",
                "veryLow": "#FAFFE2",
                "iob": "#A4BA5B",
                "accent": "#C5DB7A",
                "accentOnLight": "#C5DB7A",
                "accentTint": "#26C5DB7A",
                "accentTintStrong": "#3DC5DB7A",
                "onAccent": "#1B2300"
              }
            }
            """.trimIndent()
        )
        val skin = spec.toSkin()
        assertWithMessage("a monochrome skin must remain possible")
            .that(SkinValidation.problems(skin)).isEmpty()
        // Single-look: no light palette, so the one ground serves both.
        assertThat(skin.light).isEqualTo(skin.dark)
        assertThat(skin.cornerRadius).isEqualTo(0.dp)
    }

    @Test
    fun `a single palette serves both grounds and inherits from the one it resembles`() {
        // A pale skin that names only its own colours must not pick up dark surfaces for everything
        // it left out — the reason `palette` exists rather than making light skins declare
        // themselves under `dark`.
        val spec = SkinSpec.parse("""{ "id": "pale", "label": "Pale", "palette": { "background": "#FFF1F7" } }""")
        val skin = spec.toSkin()
        assertThat(skin.light).isEqualTo(skin.dark)
        assertWithMessage("inherits the LIGHT default, not the dark one")
            .that(skin.dark.textPrimary).isEqualTo(AapsSkins.Default.light.textPrimary)
    }

    @Test
    fun `a dark single palette still inherits from the dark default`() {
        val skin = SkinSpec.parse("""{ "id": "d", "label": "D", "palette": { "background": "#101418" } }""").toSkin()
        assertThat(skin.dark.textPrimary).isEqualTo(AapsSkins.Default.dark.textPrimary)
    }

    @Test
    fun `the kawaii skin satisfies every rule`() {
        // A saturated palette is where text contrast gets hard, the opposite failure mode from the
        // Game Boy skin — worth pinning both so the rules are exercised from both ends. Neon only
        // clears contrast against a DARK ground, which is the constraint that shapes this palette.
        val spec = SkinSpec.parse(
            """
            {
              "formatVersion": 1,
              "id": "kawaii",
              "label": "Kawaii Neon",
              "author": "alex",
              "description": "Neon rainbow on deep purple, bubble font, very round. Font: Bubblegum Sans (OFL).",
              "cornerRadius": 28,
              "font": { "file": "cute.ttf", "singleWeight": true, "scale": 1.05 },
              "palette": {
                "background": "#140021",
                "surface": "#22093A",
                "surface2": "#2E0F4D",
                "surface3": "#180527",
                "bar": "#0D0016",
                "scrim": "#CC0D0016",
                "hairline": "#3300E5FF",
                "divider": "#26FF2D95",
                "controlFill": "#26FF2D95",
                "switchTrackOff": "#40B57FD6",
                "switchKnobOff": "#F0A6FF",
                "textPrimary": "#FFF0FB",
                "textSecondary": "#F0A6FF",
                "textTertiary": "#B57FD6",
                "textOnSurfaceStrong": "#FFE0F7",
                "inRange": "#3DFFC8",
                "high": "#FFE03D",
                "low": "#FF2D95",
                "veryHigh": "#FF9E1F",
                "veryLow": "#FF5C5C",
                "iob": "#FF7AD9",
                "accent": "#00E5FF",
                "accentOnLight": "#00E5FF",
                "accentTint": "#2600E5FF",
                "accentTintStrong": "#4000E5FF",
                "onAccent": "#140021"
              }
            }
            """.trimIndent()
        )
        val skin = spec.toSkin()
        assertWithMessage("kawaii palette").that(SkinValidation.problems(skin)).isEmpty()
        assertThat(skin.light).isEqualTo(skin.dark)
        assertThat(skin.cornerRadius).isEqualTo(28.dp)
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

package app.aaps.core.compose.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * The built-in skins, held to the same rules as any skin file.
 *
 * The legibility maths itself lives in [SkinValidation] in main, because a palette loaded off disk
 * has to be judged by it too. What this asserts is that the rules are wired up, that they bite when
 * a palette is bad, and that everything shipped in the app passes them.
 */
class SkinContrastTest {

    @Test
    fun `every built-in skin passes the rules the loader enforces on skin files`() {
        // Not a restatement of the checks — literally the same code path a downloaded skin goes
        // through, so the built-ins can never hold themselves to a lower bar than a stranger's file.
        AapsSkins.all.forEach { skin ->
            assertWithMessage("skin '${skin.id}'").that(SkinValidation.problems(skin)).isEmpty()
        }
    }

    @Test
    fun `the rules actually reject an unreadable palette`() {
        // A guard that never fires is indistinguishable from one that is broken.
        val invisible = AapsColors(textPrimary = AapsColors().background)
        assertThat(SkinValidation.problems(invisible)).isNotEmpty()
    }

    @Test
    fun `the rules reject a palette whose glucose bands look alike`() {
        val muddled = AapsColors(low = AapsColors().high)
        assertThat(SkinValidation.problems(muddled)).isNotEmpty()
    }

    @Test
    fun `contrast and difference maths match their reference values`() {
        assertThat(SkinValidation.contrast(Color.Black, Color.White)).isWithin(0.01).of(21.0)
        assertThat(SkinValidation.contrast(Color.White, Color.White)).isWithin(0.01).of(1.0)
        assertThat(SkinValidation.deltaE(Color.White, Color.White)).isWithin(0.01).of(0.0)
        // Amber vs green: nearly equal luminance, obviously different colours. The pair that proves
        // why band separation is measured perceptually rather than as a contrast ratio.
        val amber = Color(0xFFFFB84D)
        val green = Color(0xFF3ED598)
        assertThat(SkinValidation.contrast(amber, green)).isLessThan(1.2)
        assertThat(SkinValidation.deltaE(amber, green)).isGreaterThan(SkinValidation.MIN_DELTA_E)
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

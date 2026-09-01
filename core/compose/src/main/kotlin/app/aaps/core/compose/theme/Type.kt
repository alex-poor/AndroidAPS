package app.aaps.core.compose.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.R

/**
 * Hanken Grotesk is vendored as a single variable font (`res/font/hanken_grotesk.ttf`).
 * We derive each static weight via [FontVariation] (`wght` axis) — requires API 26+ (app minSdk is 31).
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun hanken(weight: FontWeight) =
    Font(
        R.font.hanken_grotesk,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
    )

val HankenGrotesk = FontFamily(
    hanken(FontWeight.Normal),     // 400
    hanken(FontWeight.Medium),     // 500
    hanken(FontWeight.SemiBold),   // 600
    hanken(FontWeight.Bold),       // 700
    hanken(FontWeight.ExtraBold)   // 800
)

/** Tabular figures for every numeric readout, so digits don't jitter as values change. */
val TabularNums = "tnum"

/**
 * The redesign type scale. Not all roles map onto M3's [Typography] names, so the app-specific
 * roles (hero BG value, big value, card value, uppercase label, …) live here.
 *
 * Read it through [AapsTheme.type], never as a constant: a skin supplies its own scale, and a style
 * captured outside composition would pin the screen to whatever font happened to be loaded first.
 */
@Immutable
data class AapsTextStyles(
    val hero: TextStyle,
    val bigValue: TextStyle,
    val cardValue: TextStyle,
    val title: TextStyle,
    val listTitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle
)

/**
 * Build the scale for a family.
 *
 * [singleWeight] is for skins whose font ships one weight — a pixel font, typically. Asking Compose
 * for ExtraBold from a one-weight family gets synthetic bold, which smears exactly the sharp edges
 * such a font exists to provide, so those skins flatten every role to [FontWeight.Normal] and lean
 * on size for hierarchy instead.
 */
fun aapsTextStyles(
    family: FontFamily = HankenGrotesk,
    scale: Float = 1f,
    singleWeight: Boolean = false
): AapsTextStyles {
    fun w(weight: FontWeight) = if (singleWeight) FontWeight.Normal else weight
    return AapsTextStyles(
        // Huge hero BG value, colored by glucose range at the call site.
        hero = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.ExtraBold),
            fontSize = (78 * scale).sp, lineHeight = (78 * scale).sp, letterSpacing = (-0.02).em,
            fontFeatureSettings = TabularNums
        ),
        // Big value (dose, %).
        bigValue = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.ExtraBold),
            fontSize = (48 * scale).sp, lineHeight = (50 * scale).sp, letterSpacing = (-0.02).em,
            fontFeatureSettings = TabularNums
        ),
        cardValue = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.ExtraBold),
            fontSize = (24 * scale).sp, lineHeight = (26 * scale).sp, letterSpacing = (-0.01).em,
            fontFeatureSettings = TabularNums
        ),
        // Screen / sheet title.
        title = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.ExtraBold),
            fontSize = (16 * scale).sp, lineHeight = (20 * scale).sp, letterSpacing = (-0.01).em
        ),
        listTitle = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Bold),
            fontSize = (14 * scale).sp, lineHeight = (18 * scale).sp
        ),
        body = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Medium),
            fontSize = (14 * scale).sp, lineHeight = (20 * scale).sp
        ),
        // Uppercase label, wide tracking.
        label = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Bold),
            fontSize = (11 * scale).sp, lineHeight = (13 * scale).sp, letterSpacing = 0.07.em
        ),
        caption = TextStyle(
            fontFamily = family, fontWeight = w(FontWeight.Normal),
            fontSize = (12 * scale).sp, lineHeight = (15 * scale).sp
        )
    )
}

/** The scale the redesign shipped with. */
val DefaultAapsTextStyles = aapsTextStyles()

/**
 * Baseline M3 [Typography] so any Material component (dialogs, switches, menus) picks up the skin's
 * font rather than staying on the platform default while everything around it changes.
 */
fun aapsM3Typography(family: FontFamily, singleWeight: Boolean = false): Typography {
    fun w(weight: FontWeight) = if (singleWeight) FontWeight.Normal else weight
    return Typography().run {
        copy(
            displayLarge = displayLarge.copy(fontFamily = family),
            displayMedium = displayMedium.copy(fontFamily = family),
            displaySmall = displaySmall.copy(fontFamily = family),
            headlineLarge = headlineLarge.copy(fontFamily = family),
            headlineMedium = headlineMedium.copy(fontFamily = family),
            headlineSmall = headlineSmall.copy(fontFamily = family),
            titleLarge = titleLarge.copy(fontFamily = family, fontWeight = w(FontWeight.Bold)),
            titleMedium = titleMedium.copy(fontFamily = family, fontWeight = w(FontWeight.Bold)),
            titleSmall = titleSmall.copy(fontFamily = family, fontWeight = w(FontWeight.SemiBold)),
            bodyLarge = bodyLarge.copy(fontFamily = family),
            bodyMedium = bodyMedium.copy(fontFamily = family),
            bodySmall = bodySmall.copy(fontFamily = family),
            labelLarge = labelLarge.copy(fontFamily = family, fontWeight = w(FontWeight.Bold)),
            labelMedium = labelMedium.copy(fontFamily = family, fontWeight = w(FontWeight.Bold)),
            labelSmall = labelSmall.copy(fontFamily = family, fontWeight = w(FontWeight.Bold))
        )
    }
}

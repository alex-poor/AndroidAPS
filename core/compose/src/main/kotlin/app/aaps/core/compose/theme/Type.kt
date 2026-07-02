package app.aaps.core.compose.theme

import androidx.compose.material3.Typography
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
 * roles (hero BG value, big value, card value, uppercase label, …) live in [AapsType].
 */
object AapsType {

    // Huge hero BG value (78sp / 800), colored by glucose range at the call site.
    val hero = TextStyle(
        fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold,
        fontSize = 78.sp, lineHeight = 78.sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = TabularNums
    )

    // Big value (dose, %) — 48sp / 800.
    val bigValue = TextStyle(
        fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp, lineHeight = 50.sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = TabularNums
    )

    // Card value — 24sp / 800.
    val cardValue = TextStyle(
        fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em,
        fontFeatureSettings = TabularNums
    )

    // Screen / sheet title — 16sp / 800.
    val title = TextStyle(
        fontFamily = HankenGrotesk, fontWeight = FontWeight.ExtraBold,
        fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = (-0.01).em
    )

    // List title — 14sp / 700.
    val listTitle = TextStyle(
        fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, lineHeight = 18.sp
    )

    // Body — 14sp / 500.
    val body = TextStyle(
        fontFamily = HankenGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp
    )

    // Uppercase label — 11sp / 700, wide tracking.
    val label = TextStyle(
        fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold,
        fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.07.em
    )

    // Caption — 12sp / 400.
    val caption = TextStyle(
        fontFamily = HankenGrotesk, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 15.sp
    )
}

/** Baseline M3 Typography so any Material component pulls Hanken by default. */
val AapsTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = HankenGrotesk),
        displayMedium = displayMedium.copy(fontFamily = HankenGrotesk),
        displaySmall = displaySmall.copy(fontFamily = HankenGrotesk),
        headlineLarge = headlineLarge.copy(fontFamily = HankenGrotesk),
        headlineMedium = headlineMedium.copy(fontFamily = HankenGrotesk),
        headlineSmall = headlineSmall.copy(fontFamily = HankenGrotesk),
        titleLarge = titleLarge.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold),
        titleSmall = titleSmall.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontFamily = HankenGrotesk),
        bodyMedium = bodyMedium.copy(fontFamily = HankenGrotesk),
        bodySmall = bodySmall.copy(fontFamily = HankenGrotesk),
        labelLarge = labelLarge.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold),
        labelMedium = labelMedium.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold),
        labelSmall = labelSmall.copy(fontFamily = HankenGrotesk, fontWeight = FontWeight.Bold)
    )
}

package app.aaps.core.compose.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Corner radii from the handoff "Shape & spacing" tokens.
 *
 * Read through [AapsTheme.shape], never as a constant — a skin supplies its own set, and a square
 * skin that still drew round cards would look broken rather than deliberate.
 */
@Immutable
data class AapsShapes(
    val hero: CornerBasedShape,        // hero / large card
    val card: CornerBasedShape,
    val cardSmall: CornerBasedShape,
    val pill: CornerBasedShape,        // chip / pill
    val button: CornerBasedShape,
    val iconButton: CornerBasedShape,
    val sheet: CornerBasedShape,       // bottom sheet, top corners only
    val extraSmall: CornerBasedShape
)

/**
 * Derive the whole set from one radius seed, the way the handoff's own numbers relate to each other.
 *
 * One value is the entire shape language: `aapsShapes(0.dp)` squares off every corner in the app,
 * including the pills, which is what a hard-edged skin needs and what a per-token file would make
 * needlessly laborious to express.
 */
fun aapsShapes(radius: Dp = 18.dp): AapsShapes {
    val r = if (radius < 0.dp) 0.dp else radius
    fun step(factor: Float) = RoundedCornerShape(r * factor)
    return AapsShapes(
        hero = step(1.33f),        // 24 at the default seed
        card = step(1f),           // 18
        cardSmall = step(0.78f),   // 14
        // A pill is fully rounded by definition — unless the skin has no curves at all, in which
        // case a stadium shape would be the one thing breaking the language.
        pill = if (r <= 0.dp) RoundedCornerShape(0.dp) else RoundedCornerShape(999.dp),
        button = step(0.89f),      // 16
        iconButton = step(0.67f),  // 12
        sheet = RoundedCornerShape(topStart = r * 1.44f, topEnd = r * 1.44f),  // 26
        extraSmall = step(0.44f)   // 8
    )
}

/** The shape set the redesign shipped with. */
val DefaultAapsShapes = aapsShapes()

/** M3 [Shapes] so Material components inherit the skin's radii instead of the platform defaults. */
fun aapsM3Shapes(shapes: AapsShapes): Shapes = Shapes(
    extraSmall = shapes.extraSmall,
    small = shapes.cardSmall,
    medium = shapes.card,
    large = shapes.hero,
    extraLarge = shapes.hero
)

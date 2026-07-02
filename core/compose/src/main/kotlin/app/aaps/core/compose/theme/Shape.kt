package app.aaps.core.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Corner radii from the handoff "Shape & spacing" tokens. */
object AapsShape {

    val hero = RoundedCornerShape(24.dp)     // hero / large card
    val card = RoundedCornerShape(18.dp)      // card (16–20)
    val cardSmall = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(999.dp)     // chip / pill
    val button = RoundedCornerShape(16.dp)    // 14–16
    val iconButton = RoundedCornerShape(12.dp)// 11–14
    val sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
}

/** M3 Shapes so Material components inherit the redesign radii. */
val AapsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = AapsShape.cardSmall,
    medium = AapsShape.card,
    large = AapsShape.hero,
    extraLarge = AapsShape.hero
)

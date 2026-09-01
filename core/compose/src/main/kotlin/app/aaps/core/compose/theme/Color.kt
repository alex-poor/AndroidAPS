package app.aaps.core.compose.theme

import androidx.compose.ui.graphics.Color

/**
 * AAPS redesign color tokens (dark theme).
 *
 * Three families, kept strictly separate — this separation is the core UX fix for
 * "unclear what's interactive":
 *  - [AapsPalette] surfaces / text  — neutral chrome, non-semantic.
 *  - Semantic (glucose / health)     — greens/ambers/reds, RESERVED for glucose & status. Never a control.
 *  - Accent (interactive / brand)    — the "is-tappable" signal. Anything the user can tap.
 *
 * Source of truth: design handoff "Design tokens" table.
 */
object AapsPalette {

    // Surfaces / chrome
    val background = Color(0xFF0E1116)   // screen background
    val surface = Color(0xFF141922)      // cards, sheets
    val surface2 = Color(0xFF171C24)     // nested cards
    val surface3 = Color(0xFF12161D)     // bottom sheet
    val bar = Color(0xFF10141B)          // bottom action / confirm bars
    val scrim = Color(0x99060810)        // dims the content behind a bottom sheet

    val hairline = Color(0x0FFFFFFF)     // rgba(255,255,255,0.06) card borders
    val divider = Color(0x0DFFFFFF)      // rgba(255,255,255,0.05) list separators
    val controlFill = Color(0x0FFFFFFF)  // rgba(255,255,255,0.06) icon buttons, inert chips

    // Text
    val textPrimary = Color(0xFFEDF0F4)  // headings, values
    val textSecondary = Color(0xFF98A2B2)// labels
    val textTertiary = Color(0xFF6A7482) // captions, hints
    val textOnSurfaceStrong = Color(0xFFC4CCD8) // list item titles

    // Toggle track / knob (Material Switch)
    val switchTrackOff = Color(0x1FFFFFFF) // rgba(255,255,255,0.12)
    val switchKnobOff = Color(0xFF8A93A3)
}

/** Semantic glucose / status colors — RESERVED. Never use for generic controls. */
object AapsSemantic {

    val inRange = Color(0xFF3ED598)    // green — in-range / good
    val high = Color(0xFFFFB84D)       // amber — high
    val low = Color(0xFFFF5C6C)        // red — low / urgent
    val veryLow = Color(0xFFB0341F)    // deep red
    val veryHigh = Color(0xFFD98200)   // deep amber
    val iob = Color(0xFFFF9AA2)        // soft red — IOB / insulin-reducing
}

/** Accent (interactive / brand) — the "is-tappable" signal. */
object AapsAccent {

    val accent = Color(0xFF6E8BFF)             // periwinkle / indigo
    val onLightSurface = Color(0xFFAEBEFF)     // accent text on light surface
    val tint = Color(0x1F6E8BFF)               // rgba(110,139,255,0.12) fills
    val tintStrong = Color(0x296E8BFF)         // rgba(110,139,255,0.16) fills
    val onAccent = Color(0xFF0B0E14)           // text / icon on accent buttons
}

/**
 * A semantic tone named where the *meaning* is known but the theme is not.
 *
 * Fragments, dialogs and other state builders run outside composition, so they cannot read
 * [LocalAapsColors] — historically they reached for [AapsSemantic] directly and baked one palette's
 * literal [Color] into their UI state, which is what made the design tokens unswappable. Those
 * builders now name a tone; the composable resolves it against the live theme with
 * [app.aaps.core.compose.theme.color].
 */
enum class AapsTone {

    InRange,
    High,
    Low,
    VeryLow,
    VeryHigh,

    /** The interactive / brand accent — for state that is a control, not a glucose reading. */
    Accent,

    /** No status to report (unread, disconnected, not applicable) — renders as tertiary text ink. */
    Neutral
}

package app.aaps.activities.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.ListRow
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.ToggleRow
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey

/**
 * Renders a plugin's preference screen with the app's design system, driven by the SAME AndroidX
 * `PreferenceScreen` the legacy fragment builds.
 *
 * WHY IT WALKS THE EXISTING TREE INSTEAD OF DECLARING ITS OWN. Every plugin builds its preferences
 * imperatively in `addPreferenceScreen()`, and that build applies the rules that decide what a user may
 * even see: simple mode, APS / NSClient / pump-control mode, and per-key `dependency`. Those are applied in
 * each `Adaptive*Preference` constructor as `isVisible` / `isEnabled`. Re-declaring the screens in Compose
 * would duplicate all of it and let the two drift — on a screen that sets max basal and max IOB, a drifted
 * visibility rule is a safety bug, not a cosmetic one. So the tree stays the source of truth for STRUCTURE
 * and VISIBILITY, and this file only decides how a row is drawn.
 *
 * Values are read and written through [Preferences] with the typed key looked up by
 * `preferences.get(keyString)`, so bounds (`min`/`max` on the numeric keys) come from the same declaration
 * the legacy validator used rather than being restated here.
 *
 * Anything this renderer does not recognise falls back to a row that hands the tap back to the underlying
 * `Preference` (`performClick()`), which runs its own dialog. That keeps exotic entries — intents, click
 * actions, list pickers with custom bodies — working exactly as before instead of silently doing nothing.
 */

/** One rendered line: either a section heading or a leaf preference. */
sealed interface PrefRow {
    data class Section(val title: String) : PrefRow
    data class Leaf(val preference: Preference) : PrefRow
}

/** Flatten a built [PreferenceScreen] into rows, honouring `isVisible` exactly as the legacy list does. */
fun flattenPreferences(group: PreferenceGroup): List<PrefRow> {
    val out = mutableListOf<PrefRow>()
    fun walk(g: PreferenceGroup, emitHeading: Boolean) {
        if (emitHeading && g is PreferenceCategory && g.isVisible) {
            val t = g.title?.toString().orEmpty()
            if (t.isNotBlank()) out += PrefRow.Section(t)
        }
        for (i in 0 until g.preferenceCount) {
            val p = g.getPreference(i)
            if (!p.isVisible) continue
            when (p) {
                is PreferenceCategory -> walk(p, emitHeading = true)
                // A nested PreferenceScreen is a navigation target in the legacy UI. Render its contents
                // inline under its own heading: these screens are short, and one flat scroll beats a
                // hierarchy the user has to remember the shape of.
                is PreferenceScreen   -> {
                    val t = p.title?.toString().orEmpty()
                    if (t.isNotBlank()) out += PrefRow.Section(t)
                    walk(p, emitHeading = false)
                }
                is PreferenceGroup    -> walk(p, emitHeading = false)
                else                  -> out += PrefRow.Leaf(p)
            }
        }
    }
    walk(group, emitHeading = true)
    return out
}

@Composable
fun PreferenceScreenCompose(
    rows: List<PrefRow>,
    preferences: Preferences,
    modifier: Modifier = Modifier
) {
    val colors = AapsTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
    ) {
        // Group consecutive leaves under their heading so each section becomes one card.
        var i = 0
        while (i < rows.size) {
            val row = rows[i]
            if (row is PrefRow.Section) {
                val leaves = mutableListOf<Preference>()
                var j = i + 1
                while (j < rows.size && rows[j] is PrefRow.Leaf) {
                    leaves += (rows[j] as PrefRow.Leaf).preference; j++
                }
                Text(row.title.uppercase(), style = AapsType.label, color = colors.textSecondary)
                if (leaves.isNotEmpty()) AapsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        leaves.forEach { PreferenceRow(it, preferences) }
                    }
                }
                i = j
            } else {
                val leaves = mutableListOf<Preference>()
                var j = i
                while (j < rows.size && rows[j] is PrefRow.Leaf) {
                    leaves += (rows[j] as PrefRow.Leaf).preference; j++
                }
                if (leaves.isNotEmpty()) AapsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        leaves.forEach { PreferenceRow(it, preferences) }
                    }
                }
                i = j
            }
        }
    }
}

@Composable
private fun PreferenceRow(pref: Preference, preferences: Preferences) {
    val keyString = pref.key ?: return
    val typed = remember(keyString) { runCatching { preferences.get(keyString) }.getOrNull() }
    val title = pref.title?.toString().orEmpty().ifBlank { keyString }
    val sub = pref.summary?.toString()?.takeIf { it.isNotBlank() }

    when (typed) {
        is BooleanPreferenceKey -> {
            var on by remember(keyString) { mutableStateOf(preferences.get(typed)) }
            ToggleRow(
                title = title, sub = sub, checked = on,
                onCheckedChange = {
                    // Route through the Preference's own change listener first: that is where AAPS hangs
                    // cross-key consequences (a toggle that reveals or hides others). Refusing the change is
                    // meaningful — respect it rather than writing anyway.
                    if (pref.callChangeListener(it)) { preferences.put(typed, it); on = it }
                }
            )
        }

        is DoublePreferenceKey  -> {
            var v by remember(keyString) { mutableStateOf(preferences.get(typed)) }
            NumberField(
                label = title,
                value = v,
                onValue = { nv ->
                    val c = nv.coerceIn(typed.min, typed.max)
                    if (pref.callChangeListener(c.toString())) { preferences.put(typed, c); v = c }
                },
                step = pickStep(typed.min, typed.max),
                min = typed.min, max = typed.max,
                decimals = if (typed.max - typed.min <= 20.0) 2 else 1
            )
            if (sub != null) Text(sub, style = AapsType.caption, color = AapsTheme.colors.textTertiary)
        }

        is IntPreferenceKey     -> {
            var v by remember(keyString) { mutableStateOf(preferences.get(typed)) }
            NumberField(
                label = title,
                value = v.toDouble(),
                onValue = { nv ->
                    val c = nv.toInt().coerceIn(typed.min, typed.max)
                    if (pref.callChangeListener(c.toString())) { preferences.put(typed, c); v = c }
                },
                step = 1.0, min = typed.min.toDouble(), max = typed.max.toDouble(), decimals = 0
            )
            if (sub != null) Text(sub, style = AapsType.caption, color = AapsTheme.colors.textTertiary)
        }

        is StringPreferenceKey  -> {
            // Strings are free-form and some are secrets (URLs, API tokens) with their own masked dialogs.
            // Show the row and hand the tap to the existing preference rather than inventing an editor.
            ListRow(title = title, sub = sub ?: preferences.get(typed), onClick = { pref.performClick() })
        }

        else                    ->
            // Unrecognised: click actions, intents, list pickers. The legacy dialog is still correct.
            ListRow(title = title, sub = sub, onClick = { pref.performClick() })
    }
}

/** A step that feels right across the very different ranges these keys span (0.05 U vs 500 mg/dL). */
private fun pickStep(min: Double, max: Double): Double {
    val span = max - min
    return when {
        span <= 2.0   -> 0.05
        span <= 20.0  -> 0.1
        span <= 200.0 -> 1.0
        else          -> 5.0
    }
}

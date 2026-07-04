package app.aaps.plugins.main.profile.compose

import androidx.compose.runtime.Immutable

enum class ProfileTab(val label: String) { BASAL("Basal"), ISF("ISF"), IC("Carb ratio"), TARGET("Target") }

/** One time-block row: a start time + formatted value (+ optional curve value for the basal chart). */
@Immutable
data class ProfileBlock(val time: String, val startSeconds: Int, val value: String, val curveValue: Double)

@Immutable
data class ProfileViewState(
    val loading: Boolean = true,
    val profileName: String = "",
    val dia: String = "",              // insulin duration (DIA) — the profile's 5th value
    val dailyBasal: String = "",
    val basal: List<ProfileBlock> = emptyList(),
    val isf: List<ProfileBlock> = emptyList(),
    val ic: List<ProfileBlock> = emptyList(),
    val target: List<ProfileBlock> = emptyList()
)

// ---------------------------------------------------------------------------------------------
// Editable profile state (Compose editor). Mirrors one JSON array per category (a PAIR for target).
// ---------------------------------------------------------------------------------------------

/**
 * One editable time-block row. [index] is the array index (0 is always 00:00 and fixed);
 * [startSeconds] is timeAsSeconds; [value1] is the primary value; [value2] the secondary (only
 * populated for the TARGET tab, where value1=low, value2=high). Non-null [value2] ⇔ paired category.
 */
@Immutable
data class EditableBlock(
    val index: Int,
    val startSeconds: Int,
    val timeLabel: String,
    val value1: Double,
    val value2: Double? = null
)

/** Numeric constraints + formatting for one category, copied from the legacy TimeListEdit call site. */
@Immutable
data class CategoryConstraints(
    val min1: Double,
    val max1: Double,
    val min2: Double = 0.0,
    val max2: Double = 0.0,
    val step: Double,
    val decimals: Int,
    val unitLabel: String,
    val isPair: Boolean = false
)

@Immutable
data class ProfileEditState(
    val loading: Boolean = true,
    // profile identity / meta
    val profileNames: List<String> = emptyList(),
    val selectedProfileIndex: Int = 0,
    val name: String = "",
    val dia: Double = 5.0,
    val diaMin: Double = 5.0,
    val diaMax: Double = 12.0,
    val mgdl: Boolean = true,
    // per-tab editable blocks
    val basal: List<EditableBlock> = emptyList(),
    val isf: List<EditableBlock> = emptyList(),
    val ic: List<EditableBlock> = emptyList(),
    val target: List<EditableBlock> = emptyList(),
    // per-category constraints/formatting (as the legacy TimeListEdit calls use them)
    val basalC: CategoryConstraints,
    val isfC: CategoryConstraints,
    val icC: CategoryConstraints,
    val targetC: CategoryConstraints,
    // basal daily total (formatted, e.g. "∑24.5 U")
    val dailyBasal: String = ""
)

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

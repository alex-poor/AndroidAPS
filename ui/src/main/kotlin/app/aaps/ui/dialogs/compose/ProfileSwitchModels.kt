package app.aaps.ui.dialogs.compose

import androidx.compose.runtime.Immutable

/** Illustrative "effect at N%" preview values (before → after), pre-formatted. */
@Immutable
data class ProfileEffect(
    val basalBefore: String = "",
    val basalAfter: String = "",
    val isfBefore: String = "",
    val isfAfter: String = "",
    val icBefore: String = "",
    val icAfter: String = ""
)

@Immutable
data class ProfileSwitchSheetState(
    val profiles: List<String> = emptyList(),
    val selectedProfile: String = "",
    val initialPercentage: Int = 100,
    val initialTimeshift: Int = 0
)

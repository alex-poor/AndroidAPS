package app.aaps.ui.activities.profilehelper

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.Stepper
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/** The five things a comparison slot can be filled with. Mirrors `ProfileHelperActivity.ProfileType`. */
enum class ProfileKind { MOTOL_DEFAULT, DPV_DEFAULT, CURRENT, AVAILABLE_PROFILE, PROFILE_SWITCH }

/** Editable state of ONE of the two comparison slots. */
data class ProfileSlot(
    val kind: ProfileKind = ProfileKind.MOTOL_DEFAULT,
    val age: Int = 15,
    val weight: Double = 0.0,
    val tdd: Double = 0.0,
    val basalPct: Double = 32.0,
    val availableIndex: Int = 0,
    val profileSwitchIndex: Int = 0
)

data class ProfileHelperState(
    val tab: Int = 0,
    val slots: List<ProfileSlot> = listOf(ProfileSlot(), ProfileSlot(kind = ProfileKind.CURRENT)),
    val kindLabels: List<String> = emptyList(),
    val availableProfiles: List<String> = emptyList(),
    val profileSwitches: List<String> = emptyList(),
    val currentProfileName: String = ""
) {

    val slot: ProfileSlot get() = slots[tab]
}

/**
 * Redesigned profile helper: build a reference profile from age/weight/TDD (Motol or DPV), or pick an
 * existing one, in each of two slots — then compare them side by side, or copy a generated one into
 * the local profile. Every calculation and both exits (compare / copy) still call the activity's
 * original `DefaultProfile`, `ProfileViewerDialog` and `addProfile` paths.
 */
@Composable
fun ProfileHelperScreen(
    state: ProfileHelperState,
    tddStatsView: View?,
    onTab: (Int) -> Unit,
    onSlotChange: (ProfileSlot) -> Unit,
    onCopyToLocal: () -> Unit,
    onCompare: () -> Unit,
    onBack: () -> Unit
) {
    val colors = AapsTheme.colors
    val slot = state.slot
    Column(
        Modifier.fillMaxSize().background(colors.background).verticalScroll(rememberScrollState()).padding(horizontal = AapsSpacing.screenH)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "←", style = AapsType.title, color = colors.textSecondary,
                modifier = Modifier.clip(AapsShape.iconButton).clickable(onClick = onBack).padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text("Profile helper", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(start = 6.dp))
        }

        SegmentedControl(
            options = listOf("Profile 1", "Profile 2"),
            selectedIndex = state.tab,
            onSelect = onTab,
            modifier = Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)
        )

        Text("SOURCE", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)
        ) {
            ProfileKind.entries.forEachIndexed { i, kind ->
                Chip(
                    label = state.kindLabels.getOrElse(i) { kind.name },
                    onClick = { onSlotChange(slot.copy(kind = kind)) },
                    selected = slot.kind == kind
                )
            }
        }

        when (slot.kind) {
            ProfileKind.MOTOL_DEFAULT, ProfileKind.DPV_DEFAULT -> DefaultProfileInputs(slot, tddStatsView, onSlotChange, onCopyToLocal)

            ProfileKind.CURRENT                                ->
                InfoCard("Current profile", state.currentProfileName)

            ProfileKind.AVAILABLE_PROFILE                      ->
                PickerCard("Profile", state.availableProfiles, slot.availableIndex) { onSlotChange(slot.copy(availableIndex = it)) }

            ProfileKind.PROFILE_SWITCH                         ->
                PickerCard("Profile switch", state.profileSwitches, slot.profileSwitchIndex) { onSlotChange(slot.copy(profileSwitchIndex = it)) }
        }

        PrimaryButton(
            label = "Compare profiles",
            onClick = onCompare,
            modifier = Modifier.fillMaxWidth().padding(top = AapsSpacing.sectionGap, bottom = AapsSpacing.sectionGap)
        )
    }
}

@Composable
private fun DefaultProfileInputs(
    slot: ProfileSlot,
    tddStatsView: View?,
    onSlotChange: (ProfileSlot) -> Unit,
    onCopyToLocal: () -> Unit
) {
    val colors = AapsTheme.colors
    AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
            Stepper(
                value = slot.age.toString(), caption = "Age (years)",
                onMinus = { onSlotChange(slot.copy(age = (slot.age - 1).coerceAtLeast(1))) },
                onPlus = { onSlotChange(slot.copy(age = (slot.age + 1).coerceAtMost(18))) }
            )
            // Motol takes EITHER weight or TDD; entering one hides the other, as the legacy rows did.
            if (slot.kind == ProfileKind.MOTOL_DEFAULT && slot.tdd == 0.0)
                Stepper(
                    value = "%.0f".format(slot.weight), caption = "Weight (kg)",
                    onMinus = { onSlotChange(slot.copy(weight = (slot.weight - 1).coerceAtLeast(0.0))) },
                    onPlus = { onSlotChange(slot.copy(weight = (slot.weight + 1).coerceAtMost(150.0))) }
                )
            if (slot.kind == ProfileKind.DPV_DEFAULT || slot.weight == 0.0)
                Stepper(
                    value = "%.0f".format(slot.tdd), caption = "TDD (U/day)",
                    onMinus = { onSlotChange(slot.copy(tdd = (slot.tdd - 1).coerceAtLeast(0.0))) },
                    onPlus = { onSlotChange(slot.copy(tdd = (slot.tdd + 1).coerceAtMost(200.0))) }
                )
            if (slot.kind == ProfileKind.DPV_DEFAULT)
                Stepper(
                    value = "%.0f%%".format(slot.basalPct), caption = "Basal % of TDD",
                    onMinus = { onSlotChange(slot.copy(basalPct = (slot.basalPct - 1).coerceAtLeast(32.0))) },
                    onPlus = { onSlotChange(slot.copy(basalPct = (slot.basalPct + 1).coerceAtMost(37.0))) }
                )
            Text(
                "Copy to local profile",
                style = AapsType.label,
                color = colors.accent,
                modifier = Modifier.clip(AapsShape.button).clickable(onClick = onCopyToLocal).padding(vertical = 10.dp)
            )
        }
    }

    // Recent TDD table, rendered by TddCalculator as a plain View.
    tddStatsView?.let {
        Text("YOUR RECENT TDD", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            AndroidView(factory = { _ -> it }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    val colors = AapsTheme.colors
    AapsCard(Modifier.fillMaxWidth()) {
        Column {
            Text(label, style = AapsType.label, color = colors.textSecondary)
            Text(value, style = AapsType.listTitle, color = colors.textPrimary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun PickerCard(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = AapsTheme.colors
    AapsCard(Modifier.fillMaxWidth()) {
        Column {
            Text(label, style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
            if (options.isEmpty())
                Text("None available", style = AapsType.body, color = colors.textTertiary)
            else
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)
                ) {
                    options.forEachIndexed { i, name ->
                        Chip(label = name, onClick = { onSelect(i) }, selected = i == selected)
                    }
                }
        }
    }
}

package app.aaps.plugins.main.profile.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Chip
import app.aaps.core.compose.components.NumberField
import app.aaps.core.compose.components.PrimaryButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme

/**
 * Callbacks the [ProfileEditor] fires back to the host ([app.aaps.plugins.main.profile.ProfileFragment]).
 * Every value/time/block mutation goes through here so the host can apply it via
 * [app.aaps.plugins.main.profile.ui.ProfileBlockOps] (byte-for-byte equivalent to the legacy editor),
 * run `doEdit()`, and rebuild the editor state.
 *
 * [tab] indexes the category: 0=Basal, 1=ISF, 2=Carb ratio (IC), 3=Target.
 */
interface ProfileEditorCallbacks {

    fun onSelectProfile(index: Int)
    fun onName(name: String)
    fun onDia(dia: Double)

    /** Set primary value (value1) of block [index] in category [tab]. */
    fun onValue1(tab: Int, index: Int, value: Double)

    /** Set secondary value (value2, target-high only) of block [index] in category [tab]. */
    fun onValue2(tab: Int, index: Int, value: Double)

    /** Change the start time (in seconds from midnight) of block [index] in category [tab]. */
    fun onTime(tab: Int, index: Int, timeAsSeconds: Int)

    /** Append a new block to category [tab] (== the legacy "final +"). */
    fun onAddBlock(tab: Int)

    /** Remove block [index] from category [tab]. */
    fun onRemoveBlock(tab: Int, index: Int)
}

private val TABS = listOf("Basal", "ISF", "Carb ratio", "Target")
private const val HOUR = 60 * 60

/**
 * Editable local-profile editor (Compose). SAFETY-CRITICAL: all array manipulation is delegated to
 * the host through [ProfileEditorCallbacks], which applies it via ProfileBlockOps identically to the
 * legacy TimeListEdit. This composable is pure presentation over [state].
 */
@Composable
fun ProfileEditor(
    state: ProfileEditState,
    callbacks: ProfileEditorCallbacks,
    onSave: () -> Unit,
    onManage: () -> Unit
) {
    val colors = AapsTheme.colors
    var tab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }

    val blocks = when (tab) {
        0 -> state.basal
        1 -> state.isf
        2 -> state.ic
        else -> state.target
    }
    val constraints = when (tab) {
        0 -> state.basalC
        1 -> state.isfC
        2 -> state.icC
        else -> state.targetC
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH)
    ) {
        // Header
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Edit profile", style = AapsTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("Manage", style = AapsTheme.type.listTitle, color = colors.accentOnLight,
                 modifier = Modifier.clip(AapsTheme.shape.pill).clickable(onClick = onManage).padding(horizontal = 12.dp, vertical = 8.dp))
        }

        // Profile selector (only when >1 profile)
        if (state.profileNames.size > 1) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.profileNames.forEachIndexed { i, n ->
                    Chip(n, onClick = { callbacks.onSelectProfile(i) }, selected = i == state.selectedProfileIndex)
                }
            }
        }

        // Name + DIA
        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NAME", style = AapsTheme.type.label, color = colors.textSecondary)
                    Box(
                        Modifier.fillMaxWidth().clip(AapsTheme.shape.cardSmall).background(colors.surface2)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        BasicTextField(
                            value = state.name,
                            onValueChange = callbacks::onName,
                            singleLine = true,
                            textStyle = AapsTheme.type.listTitle.copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                NumberField(
                    label = "Insulin duration (DIA)",
                    value = state.dia,
                    onValue = callbacks::onDia,
                    step = 0.1,
                    min = state.diaMin,
                    max = state.diaMax,
                    decimals = 1,
                    unit = "h",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Tabs
        SegmentedControl(
            TABS, tab, { tab = it },
            Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)
        )

        // Basal daily total
        if (tab == 0 && state.dailyBasal.isNotBlank()) {
            AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DAILY BASAL", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(state.dailyBasal, style = AapsTheme.type.listTitle, color = colors.textPrimary)
                }
            }
        }

        // Blocks
        AapsCard(Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap)) {
            Column {
                blocks.forEachIndexed { i, b ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    BlockRow(
                        block = b,
                        constraints = constraints,
                        removable = blocks.size > 1 && b.index != 0,
                        canPickTime = b.index != 0,
                        onValue1 = { callbacks.onValue1(tab, b.index, it) },
                        onValue2 = { callbacks.onValue2(tab, b.index, it) },
                        onTimeShift = { deltaHours ->
                            val prev = if (i > 0) blocks[i - 1].startSeconds else -HOUR
                            val next = if (i + 1 < blocks.size) blocks[i + 1].startSeconds else 24 * HOUR
                            val target = (b.startSeconds + deltaHours * HOUR).coerceIn(prev + HOUR, next - HOUR)
                            if (target != b.startSeconds) callbacks.onTime(tab, b.index, target)
                        },
                        onRemove = { callbacks.onRemoveBlock(tab, b.index) }
                    )
                }
                if (blocks.isEmpty()) Text("No data", style = AapsTheme.type.body, color = colors.textTertiary, modifier = Modifier.padding(vertical = 12.dp))
            }
        }

        // Add block
        Row(
            Modifier.fillMaxWidth().padding(bottom = AapsSpacing.sectionGap).clip(AapsTheme.shape.pill)
                .background(colors.accentTint).clickable { callbacks.onAddBlock(tab) }.padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.accentOnLight, modifier = Modifier.size(18.dp))
            Text("  Add block", style = AapsTheme.type.listTitle, color = colors.accentOnLight)
        }

        // Save
        PrimaryButton(
            "Save profile", onSave,
            Modifier.fillMaxWidth().padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun BlockRow(
    block: EditableBlock,
    constraints: CategoryConstraints,
    removable: Boolean,
    canPickTime: Boolean,
    onValue1: (Double) -> Unit,
    onValue2: (Double) -> Unit,
    onTimeShift: (Int) -> Unit,   // delta in hours (−1 / +1)
    onRemove: () -> Unit
) {
    val colors = AapsTheme.colors
    // Two lines per block: (1) the FROM-time control + remove, (2) the full-width value field(s).
    // Keeping the value field on its own line stops the "U/h" unit from wrapping/overlapping.
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("FROM", style = AapsTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(end = 10.dp))
            if (canPickTime) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TimeStep("−") { onTimeShift(-1) }
                    Text(
                        block.timeLabel, style = AapsTheme.type.listTitle, color = colors.textOnSurfaceStrong,
                        modifier = Modifier.clip(AapsTheme.shape.pill).background(colors.controlFill).padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    TimeStep("+") { onTimeShift(1) }
                }
            } else {
                Text(
                    block.timeLabel, style = AapsTheme.type.listTitle, color = colors.textTertiary,
                    modifier = Modifier.clip(AapsTheme.shape.pill).background(colors.controlFill).padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Box(Modifier.weight(1f))
            if (removable) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(colors.controlFill).clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "remove block", tint = colors.low, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (constraints.isPair) {
            NumberField(
                label = "Low", value = block.value1, onValue = onValue1,
                step = constraints.step, min = constraints.min1, max = constraints.max1,
                decimals = constraints.decimals, unit = constraints.unitLabel, modifier = Modifier.fillMaxWidth()
            )
            NumberField(
                label = "High", value = block.value2 ?: block.value1, onValue = onValue2,
                step = constraints.step, min = constraints.min2, max = constraints.max2,
                decimals = constraints.decimals, unit = constraints.unitLabel, modifier = Modifier.fillMaxWidth()
            )
        } else {
            NumberField(
                label = "", value = block.value1, onValue = onValue1,
                step = constraints.step, min = constraints.min1, max = constraints.max1,
                decimals = constraints.decimals, unit = constraints.unitLabel, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TimeStep(symbol: String, onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(colors.controlFill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, style = AapsTheme.type.listTitle, color = colors.textPrimary)
    }
}

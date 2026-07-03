package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.compose.components.ActionBarButton
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Dot
import app.aaps.core.compose.components.RoundIconButton
import app.aaps.core.compose.components.SegmentedControl
import app.aaps.core.compose.components.SheetSurface
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * The redesigned Home (Overview) screen. Stateless — driven by [state] + [actions]. The glucose
 * graph is injected via [graph] so the fragment can host the existing GraphView (AndroidView).
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    graph: @Composable () -> Unit
) {
    val colors = AapsTheme.colors
    var showDetails by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            // No Compose top bar here — the app's own toolbar/tab strip already sits above this fragment.
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AapsSpacing.screenH)
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
            ) {
                HeroCard(state, actions)
                if (state.supplies.isNotEmpty()) SuppliesStrip(state.supplies)
                GraphCard(state.graphRangeHours, actions.onRange, graph)
                DetailsHandle { showDetails = true }
                Box(Modifier.padding(bottom = 4.dp))
            }
            ActionBar(actions)
        }
        if (showDetails) DetailsSheet(state, onClose = { showDetails = false })
    }
}

@Composable
private fun HeroCard(state: HomeUiState, actions: HomeActions) {
    val colors = AapsTheme.colors
    val bgColor = state.bgColor.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified } ?: colors.textPrimary
    AapsCard(shape = AapsShape.hero) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // row 1 — loop pill (tap → Loop mode chooser) + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = buildString {
                        append(state.loopStateLabel.ifBlank { "Loop" })
                        if (state.loopSubLabel.isNotBlank()) append("  ${state.loopSubLabel}")
                    },
                    dotColor = state.loopColor.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified } ?: colors.inRange,
                    glow = state.looping,
                    labelColor = colors.textPrimary,
                    onClick = actions.onLoop
                )
                Text(state.timeAgo, style = AapsType.caption, color = colors.textTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            // row 2 — BG + inline trend (left) · eventual (right)
            Row(verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.bg, style = AapsType.bigValue.copy(fontSize = 56.sp, lineHeight = 56.sp), color = bgColor)
                    if (state.trendArrow.isNotBlank() || state.delta.isNotBlank())
                        Text(
                            "${state.trendArrow} ${state.delta}".trim(),
                            style = AapsType.listTitle.copy(fontWeight = FontWeight.ExtraBold),
                            color = bgColor,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                }
                if (state.eventualBg.isNotBlank())
                    Column(horizontalAlignment = Alignment.End) {
                        Text(state.eventualBg, style = AapsType.title, color = colors.textPrimary)
                        Text("EVENTUAL", style = AapsType.label, color = colors.textTertiary)
                    }
            }
            // row 3 — state line (current reading only): "<n above target> · <range>"
            if (state.stateLine.isNotBlank())
                Row {
                    Text(state.stateLine, style = AapsType.caption.copy(fontWeight = FontWeight.Bold), color = bgColor)
                    if (state.targetRange.isNotBlank())
                        Text(" · ${state.targetRange}", style = AapsType.caption.copy(fontWeight = FontWeight.Bold), color = colors.textSecondary)
                }
            // divider + stat row (IOB / COB / Basal)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f))
                    .height(1.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                HeroStat("IOB", state.iob.ifBlank { "--" }, Modifier.weight(1f), onClick = actions.onIob)
                HeroStat("COB", state.cob.ifBlank { "--" }, Modifier.weight(1f), onClick = actions.onCob)
                HeroStat("BASAL", state.basal.ifBlank { "--" }, Modifier.weight(1f), valueColor = colors.accent, sub = state.basalSub, onClick = actions.onBasal)
            }
        }
    }
}

@Composable
private fun HeroStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = AapsTheme.colors.textPrimary,
    sub: String = "",
    onClick: (() -> Unit)? = null
) {
    val colors = AapsTheme.colors
    Column(
        (if (onClick != null) modifier.clip(AapsShape.cardSmall).clickable(onClick = onClick) else modifier).padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(label, style = AapsType.label, color = colors.textSecondary)
        Text(value, style = AapsType.cardValue.copy(fontSize = 18.sp, lineHeight = 20.sp), color = valueColor, maxLines = 1)
        // sub kept readable (secondary color, real caption size) — was too small/dark to see before
        if (sub.isNotBlank()) Text(sub, style = AapsType.caption, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun SuppliesStrip(supplies: List<HomeUiState.Supply>) {
    // Spread the pills evenly across the viewport but keep each at its natural width, so a wide
    // value (e.g. Reservoir "250.00 U") gets the room it needs instead of wrapping onto a new line.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        supplies.forEach { s ->
            StatusPill(label = s.label, value = s.value, dotColor = s.dotColor)
        }
    }
}

@Composable
private fun GraphCard(rangeHours: Int, onRange: (Int) -> Unit, graph: @Composable () -> Unit) {
    val colors = AapsTheme.colors
    val ranges = listOf(6, 12, 24)
    val selected = ranges.indexOfFirst { it >= rangeHours }.let { if (it < 0) ranges.lastIndex else it }
    AapsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(AapsSpacing.cardPadSmall)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Glucose", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
                SegmentedControl(
                    options = ranges.map { "${it}h" },
                    selectedIndex = selected,
                    onSelect = { onRange(ranges[it]) }
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) { graph() }
        }
    }
}

@Composable
private fun DetailsHandle(onClick: () -> Unit) {
    val colors = AapsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AapsShape.pill)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ExpandLess, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.padding(end = 6.dp))
        Text("Details — status, sensitivity & graphs", style = AapsType.caption, color = colors.textTertiary)
    }
}

@Composable
private fun DetailsSheet(state: HomeUiState, onClose: () -> Unit) {
    val colors = AapsTheme.colors
    Box(Modifier.fillMaxSize()) {
        // scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0x99060810))
                .clickable(onClick = onClose)
        )
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            SheetSurface(title = "Details", onClose = onClose) {
                // Supplies & status
                if (state.supplies.isNotEmpty()) {
                    Text("SUPPLIES & STATUS", style = AapsType.label, color = colors.textSecondary)
                    AapsCard(Modifier.fillMaxWidth()) {
                        Column {
                            state.supplies.forEachIndexed { i, s ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Dot(s.dotColor, size = 9.dp)
                                    Text(s.label, style = AapsType.listTitle, color = colors.textOnSurfaceStrong, modifier = Modifier.weight(1f))
                                    Text(s.value, style = AapsType.listTitle, color = colors.textPrimary)
                                }
                            }
                        }
                    }
                }
                // Loop algorithm & sensitivity
                Text("LOOP & SENSITIVITY", style = AapsType.label, color = colors.textSecondary)
                AapsCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailRow("Algorithm", state.algorithmName.ifBlank { "—" })
                        DetailRow("Sensitivity", state.sensitivity.ifBlank { "—" })
                        DetailRow("Profile", state.profileName.ifBlank { "—" })
                        if (!state.tempTarget.isNullOrBlank()) DetailRow("Temp target", state.tempTarget)
                    }
                }
                Box(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = AapsTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = AapsType.listTitle, color = colors.textPrimary)
    }
}

@Composable
private fun ActionBar(actions: HomeActions) {
    val colors = AapsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.bar)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Bottom))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionBarButton("Carbs", Icons.Rounded.Restaurant, actions.onCarbs, Modifier.weight(1f))
        ActionBarButton(
            "Bolus", Icons.Rounded.WaterDrop, actions.onBolus, Modifier.weight(1f),
            container = colors.inRange.copy(alpha = 0.14f), content = colors.inRange
        )
        ActionBarButton("Wizard", Icons.Rounded.Calculate, actions.onWizard, Modifier.weight(1.4f), emphasized = true)
        MoreMenu(actions)
    }
}

@Composable
private fun MoreMenu(actions: HomeActions) {
    val colors = AapsTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(Icons.Rounded.Add, "More actions", onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Temp target", color = colors.textPrimary) },
                onClick = { expanded = false; actions.onTempTarget() }
            )
            DropdownMenuItem(
                text = { Text("Bolus wizard", color = colors.textPrimary) },
                onClick = { expanded = false; actions.onWizard() }
            )
            DropdownMenuItem(
                text = { Text("Calibrate CGM", color = colors.textPrimary) },
                onClick = { expanded = false; actions.onCalibration() }
            )
        }
    }
}

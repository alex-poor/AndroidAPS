package app.aaps.plugins.main.general.overview.compose

import app.aaps.core.compose.icons.AapsIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
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
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType
import app.aaps.core.compose.theme.color

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
    var showCarbs by remember { mutableStateOf(false) }
    var showInsulin by remember { mutableStateOf(false) }
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
                if (state.notifications.isNotEmpty()) AlertsCard(state.notifications, actions.onDismissAlert)
                HeroCard(state, actions, onCobClick = { showCarbs = true }, onIobClick = { showInsulin = true })
                if (state.supplies.isNotEmpty()) SuppliesStrip(state.supplies)
                GraphCard(state.graphRangeHours, actions.onRange, graph)
                DetailsHandle { showDetails = true }
                Box(Modifier.padding(bottom = 4.dp))
            }
            ActionBar(actions)
        }
        if (showDetails) DetailsSheet(state, onClose = { showDetails = false })
        if (showCarbs) CarbsUndoSheet(state.recentCarbs, actions.onDeleteCarb, onClose = { showCarbs = false })
        if (showInsulin) InsulinUndoSheet(state, actions.onDeleteInsulin, onClose = { showInsulin = false })
    }
}

/**
 * Active notifications. Urgent/normal alerts are the reason the loop may not be doing what the hero
 * says, so they sit above it. Tapping the button runs the notification's action and clears it — the
 * same behaviour as the legacy dismiss button.
 */
@Composable
private fun AlertsCard(alerts: List<HomeUiState.Alert>, onDismiss: (HomeUiState.Alert) -> Unit) {
    val colors = AapsTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.rowGapSmall)) {
        alerts.forEach { alert ->
            val tint = when (alert.level) {
                Notification.URGENT -> colors.low
                Notification.NORMAL -> colors.high
                Notification.LOW    -> colors.inRange
                else                -> colors.accent
            }
            AapsCard(shape = AapsShape.cardSmall) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .padding(end = 10.dp)
                            .size(width = 3.dp, height = 30.dp)
                            .clip(AapsShape.pill)
                            .background(tint)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(alert.text, style = AapsType.body, color = colors.textPrimary)
                        Text(alert.time, style = AapsType.caption, color = colors.textTertiary)
                    }
                    Text(
                        alert.buttonText,
                        style = AapsType.label,
                        color = tint,
                        modifier = Modifier
                            .clip(AapsShape.button)
                            .clickable { onDismiss(alert) }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroCard(state: HomeUiState, actions: HomeActions, onCobClick: () -> Unit, onIobClick: () -> Unit) {
    val colors = AapsTheme.colors
    val bgColor = state.bgTone?.color() ?: colors.textPrimary
    AapsCard(shape = AapsShape.hero) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // row 1 — loop pill (tap → Loop mode chooser) + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = buildString {
                        append(state.loopStateLabel.ifBlank { "Loop" })
                        if (state.loopSubLabel.isNotBlank()) append("  ${state.loopSubLabel}")
                    },
                    dotColor = state.loopTone?.color() ?: colors.inRange,
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
            // Shifted left by exactly the stat's own inset (see HeroStat) so the labels still line up
            // with the BG value above while their tap/ripple area keeps clear of the rounded corners.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).offset(x = -HeroStatInset)) {
                HeroStat("IOB", state.iob.ifBlank { "--" }, Modifier.weight(1f), onClick = onIobClick)
                HeroStat("COB", state.cob.ifBlank { "--" }, Modifier.weight(1f), onClick = onCobClick)
                HeroStat("BASAL", state.basal.ifBlank { "--" }, Modifier.weight(1f), valueColor = colors.accent, sub = state.basalSub, onClick = actions.onBasal)
            }
        }
    }
}

/**
 * Inset between a [HeroStat]'s clipped/clickable bounds and its content.
 *
 * `cardSmall` is a 14.dp radius, so at the top of the first line the corner curve cuts ~7.dp into the
 * row — enough to shave the top-left off the label's first glyph ("IOB" rendered as "iOB"). Padding
 * inside the clip keeps the text clear of the curve; the Row above cancels the indent with a matching
 * negative offset.
 */
private val HeroStatInset = 8.dp

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
        (if (onClick != null) modifier.clip(AapsShape.cardSmall).clickable(onClick = onClick) else modifier)
            .padding(horizontal = HeroStatInset, vertical = 4.dp),
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
    // Each pill is a 2-line tile (dot/ring + label on top, value below) so all of them — up to 4 after a
    // cannula change (Cannula + Sensor + Reservoir + Battery) — fit on ONE row without squashing.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        supplies.forEach { s -> SupplyCell(s, Modifier.weight(1f)) }
    }
}

/**
 * A supply as a compact 2-line tile: line 1 = indicator (a depleting COUNTDOWN ring when the supply
 * carries a life [HomeUiState.Supply.fraction], e.g. the sensor; else a plain dot) + label; line 2 =
 * value. Equal-width (weight) so 3–4 supplies share the row cleanly.
 */
@Composable
private fun SupplyCell(s: HomeUiState.Supply, modifier: Modifier) {
    val colors = AapsTheme.colors
    Column(
        modifier.clip(AapsShape.cardSmall).background(colors.controlFill).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val dot = s.dotTone.color()
            if (s.fraction != null) CountdownRing(s.fraction, dot, size = 12.dp) else Dot(dot, size = 8.dp)
            Text(s.label, style = AapsType.caption, color = colors.textSecondary, maxLines = 1)
        }
        Text(s.value, style = AapsType.listTitle, color = colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun CountdownRing(fraction: Float, color: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp = 14.dp) {
    val track = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.15f)
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.20f
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
        val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
        drawArc(track, -90f, 360f, false, topLeft = topLeft, size = arcSize, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawArc(
            color, -90f, 360f * fraction.coerceIn(0f, 1f), false,
            topLeft = topLeft, size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
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
        Icon(AapsIcons.ExpandLess, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.padding(end = 6.dp))
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
                .background(colors.scrim)
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
                                    Dot(s.dotTone.color(), size = 9.dp)
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
private fun CarbsUndoSheet(
    carbs: List<HomeUiState.CarbEntry>,
    onDelete: (HomeUiState.CarbEntry) -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    Box(Modifier.fillMaxSize()) {
        // scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(onClick = onClose)
        )
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            SheetSurface(title = "Recent carbs", onClose = onClose) {
                if (carbs.isEmpty()) {
                    Text("No carb entries in the last few hours.", style = AapsType.body, color = colors.textSecondary)
                } else {
                    Text("Remove a mistaken or duplicate entry — this asks you to confirm.", style = AapsType.caption, color = colors.textTertiary)
                    AapsCard(Modifier.fillMaxWidth()) {
                        Column {
                            carbs.forEachIndexed { i, c ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(c.grams, style = AapsType.listTitle, color = colors.textPrimary)
                                        Text(c.time, style = AapsType.caption, color = colors.textTertiary)
                                    }
                                    RoundIconButton(Icons.Rounded.Delete, "Remove ${c.grams}", onClick = { onDelete(c) })
                                }
                            }
                        }
                    }
                }
                Box(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * IOB detail + undo. Tapping IOB used to open a plain text dialog; it now also lists the boluses
 * behind that number so a dose the pump never actually delivered can be taken back out. That is the
 * only way to repair IOB from inside the app — the redesigned History is read-only — and it matters
 * because an unconfirmed bolus is deliberately recorded as delivered (over-stating IOB is the safe
 * side of that guess, but it still has to be correctable when the pump turns out to have been empty).
 */
@Composable
private fun InsulinUndoSheet(
    state: HomeUiState,
    onDelete: (HomeUiState.InsulinEntry) -> Unit,
    onClose: () -> Unit
) {
    val colors = AapsTheme.colors
    Box(Modifier.fillMaxSize()) {
        // scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(onClick = onClose)
        )
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            SheetSurface(title = "Insulin on board", onClose = onClose) {
                AapsCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailRow("Total", state.iob.ifBlank { "--" })
                        DetailRow("From boluses", state.iobBolus.ifBlank { "--" })
                        DetailRow("From basal", state.iobBasal.ifBlank { "--" })
                    }
                }
                if (state.recentInsulin.isEmpty()) {
                    Text("No boluses in the last few hours.", style = AapsType.body, color = colors.textSecondary)
                } else {
                    Text(
                        "Remove a dose the pump did not actually deliver — this asks you to confirm.",
                        style = AapsType.caption, color = colors.textTertiary
                    )
                    AapsCard(Modifier.fillMaxWidth()) {
                        Column {
                            state.recentInsulin.forEachIndexed { i, e ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(e.units, style = AapsType.listTitle, color = colors.textPrimary)
                                        Text(
                                            if (e.kind.isBlank()) e.time else "${e.time} · ${e.kind}",
                                            style = AapsType.caption, color = colors.textTertiary
                                        )
                                    }
                                    RoundIconButton(Icons.Rounded.Delete, "Remove ${e.units}", onClick = { onDelete(e) })
                                }
                            }
                        }
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
        // Two primary actions for a closed loop: announce Carbs, or bolus (the wizard-calculated dose).
        // A raw manual bolus (type-the-units, no calc) is the rare case — it lives in the "+" menu.
        ActionBarButton("Carbs", AapsIcons.Restaurant, actions.onCarbs, Modifier.weight(1f))
        ActionBarButton(
            "Bolus", AapsIcons.Vaccines, actions.onWizard, Modifier.weight(1.4f),
            container = colors.inRange, content = colors.onAccent
        )
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
            // Only actions that are NOT primary on the bottom bar (Carbs/Wizard live there).
            DropdownMenuItem(
                text = { Text("Bolus (manual)", color = colors.textPrimary) },
                onClick = { expanded = false; actions.onBolus() }
            )
            DropdownMenuItem(
                text = { Text("Bolus (record only)", color = colors.textPrimary) },
                onClick = { expanded = false; actions.onInsulinRecord() }
            )
            DropdownMenuItem(
                text = { Text("Temp target", color = colors.textPrimary) },
                onClick = { expanded = false; actions.onTempTarget() }
            )
            DropdownMenuItem(
                text = { Text("Calibrate CGM", color = colors.textPrimary) },
                onClick = { expanded = false; actions.onCalibration() }
            )
        }
    }
}

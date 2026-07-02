package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.compose.components.ActionBarButton
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.Dot
import app.aaps.core.compose.components.LoopRing
import app.aaps.core.compose.components.RoundIconButton
import app.aaps.core.compose.components.StatCard
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.components.TargetGauge
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
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        TopBar(actions)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AapsSpacing.screenH),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
        ) {
            HeroCard(state)
            if (state.supplies.isNotEmpty()) SuppliesStrip(state.supplies)
            StatRow(state, actions)
            GraphCard(graph)
            DetailsHandle(actions)
            Box(Modifier.padding(bottom = 4.dp))
        }
        ActionBar(actions)
    }
}

@Composable
private fun TopBar(actions: HomeActions) {
    val colors = AapsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.systemBars.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TopIcon(Icons.Rounded.Menu, "Menu", actions.onMore)
        Text("Overview", style = AapsType.title, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(start = 4.dp))
        TopIcon(Icons.Rounded.Notifications, "Notifications", actions.onMore)
        TopIcon(Icons.Rounded.Settings, "Settings", actions.onMore)
    }
}

@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Icon(icon, contentDescription = cd, tint = AapsTheme.colors.textSecondary, modifier = Modifier.padding(0.dp))
    }
}

@Composable
private fun HeroCard(state: HomeUiState) {
    val colors = AapsTheme.colors
    AapsCard(shape = AapsShape.hero) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // row 1 — loop pill + time
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(
                    label = buildString {
                        append(state.loopStateLabel.ifBlank { "Loop" })
                        if (state.loopSubLabel.isNotBlank()) append("  ${state.loopSubLabel}")
                    },
                    dotColor = state.loopColor.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified } ?: colors.inRange,
                    glow = state.looping,
                    labelColor = colors.textPrimary
                )
                Text(state.timeAgo, style = AapsType.caption, color = colors.textTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            }
            // row 2 — hero BG + trend + loop ring
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            state.bg,
                            style = AapsType.hero,
                            color = state.bgColor.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified } ?: colors.textPrimary
                        )
                        Text(state.units, style = AapsType.caption, color = colors.textSecondary, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    Text(
                        "${state.trendArrow}  ${state.delta}".trim(),
                        style = AapsType.listTitle,
                        color = state.bgColor.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified } ?: colors.textSecondary
                    )
                }
                if (state.eventualBg.isNotBlank())
                    LoopRing(
                        progress = state.ringProgress,
                        centerValue = state.eventualBg,
                        centerLabel = "eventual",
                        color = state.loopColor.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified } ?: colors.inRange
                    )
            }
            // row 3 — target gauge
            TargetGauge(
                fraction = state.gaugeFraction,
                lowLabel = state.gaugeLow,
                targetLabel = state.gaugeTarget,
                highLabel = state.gaugeHigh
            )
        }
    }
}

@Composable
private fun SuppliesStrip(supplies: List<HomeUiState.Supply>) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        supplies.forEach { StatusPill(label = it.label, value = it.value, dotColor = it.dotColor) }
    }
}

@Composable
private fun StatRow(state: HomeUiState, actions: HomeActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.rowGap), modifier = Modifier.fillMaxWidth()) {
        StatCard("IOB", state.iob.ifBlank { "--" }, Modifier.weight(1f), sub = state.iobSub, onClick = actions.onIob)
        StatCard("COB", state.cob.ifBlank { "--" }, Modifier.weight(1f), sub = state.cobSub, onClick = actions.onCob)
        StatCard("BASAL", state.basal.ifBlank { "--" }, Modifier.weight(1f), sub = state.basalSub, onClick = actions.onBasal)
    }
}

@Composable
private fun GraphCard(graph: @Composable () -> Unit) {
    val colors = AapsTheme.colors
    AapsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(AapsSpacing.cardPadSmall)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Glucose", style = AapsType.label, color = colors.textSecondary)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) { graph() }
        }
    }
}

@Composable
private fun DetailsHandle(actions: HomeActions) {
    val colors = AapsTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AapsShape.pill)
            .clickable(onClick = actions.onMore)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.ExpandLess, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.padding(end = 6.dp))
        Text("Details — status, sensitivity & graphs", style = AapsType.caption, color = colors.textTertiary)
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
        RoundIconButton(Icons.Rounded.Add, "More actions", actions.onMore)
    }
}

/** small helper: blank-guarded label */
private fun String.ifБlank(fallback: String) = ifBlank { fallback }

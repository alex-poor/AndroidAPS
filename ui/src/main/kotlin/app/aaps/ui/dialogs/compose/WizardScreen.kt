package app.aaps.ui.dialogs.compose

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import app.aaps.core.compose.components.AapsCard
import app.aaps.core.compose.components.HoldToConfirmButton
import app.aaps.core.compose.components.StatusPill
import app.aaps.core.compose.theme.AapsShape
import app.aaps.core.compose.theme.AapsSpacing
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.compose.theme.AapsType

/**
 * Redesigned Bolus/Carb Wizard. Two steps (Input → Confirm) in a single composable. Stateless w.r.t.
 * the dose math: [compute] runs the existing BolusWizard for the current [WizardInputs], [onDeliver]
 * runs the SAME confirm+constraint+delivery path as today. BG comes from CGM (no manual BG/profile
 * fields, per the design).
 */
@Composable
fun WizardScreen(
    compute: (WizardInputs) -> WizardResult,
    onDeliver: (WizardInputs) -> Unit,
    onCancel: () -> Unit,
    initialInputs: WizardInputs = WizardInputs(),
    quickChips: List<Int> = listOf(10, 20, 40, 60)
) {
    val colors = AapsTheme.colors
    var inputs by remember { mutableStateOf(initialInputs) }
    var confirming by remember { mutableStateOf(false) }
    val result = remember(inputs) { compute(inputs) }

    // fillMaxSize (not just width): the dialog window is MATCH_PARENT, but a wrap-height root lets the
    // scrolling content grow past the viewport and push the bottom action bar off-screen. Bounding the root
    // here is what makes the weight(1f) below able to reserve space for the bar.
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Single, consistent control: close (X) on the input step, back (←) on the confirm step.
            IconBtn(if (confirming) Icons.Rounded.ArrowBack else Icons.Rounded.Close, if (confirming) "Back" else "Close") {
                if (confirming) confirming = false else onCancel()
            }
            Text(
                if (confirming) "Confirm bolus" else "Bolus Wizard",
                style = AapsType.title, color = colors.textPrimary,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
        }

        AnimatedContent(
            targetState = confirming,
            transitionSpec = { fadeThrough() },
            label = "wizard-step",
            modifier = Modifier.weight(1f)
        ) { onConfirm ->
            if (!onConfirm) InputStep(inputs, result, quickChips, colors, onInputs = { inputs = it }, onContinue = { confirming = true })
            else ConfirmStep(inputs, result, colors, onDeliver = { onDeliver(inputs) }, onCancel = { confirming = false })
        }
    }
}

@Composable
private fun InputStep(
    inputs: WizardInputs,
    result: WizardResult,
    quickChips: List<Int>,
    colors: app.aaps.core.compose.theme.AapsColors,
    onInputs: (WizardInputs) -> Unit,
    onContinue: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        // weight(1f): the scrolling card list takes only the space LEFT OVER after the bottom action bar is
        // laid out, so the Continue/Bolus button is always on screen no matter how many cards are shown
        // (adding the pre-bolus card made an unweighted column overflow and hide the bar entirely).
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AapsSpacing.screenH)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
        ) {
            // BG context
            AapsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(result.bgFromText.ifBlank { "From CGM" }, style = AapsType.caption, color = colors.textTertiary)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(result.bgText, style = AapsType.cardValue, color = if (result.bgInRange) colors.inRange else colors.high)
                            Text(result.bgTrendArrow, style = AapsType.listTitle, color = if (result.bgInRange) colors.inRange else colors.high, modifier = Modifier.padding(bottom = 2.dp))
                        }
                    }
                    StatusPill(if (result.bgInRange) "in range" else "high", dotColor = if (result.bgInRange) colors.inRange else colors.high)
                }
            }

            // Carbs stepper + chips
            AapsCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CARBS", style = AapsType.label, color = colors.textSecondary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StepperButton(Icons.Rounded.Remove, "minus", colors.controlFill, colors.textPrimary) {
                            onInputs(inputs.copy(carbs = (inputs.carbs - 5).coerceAtLeast(0)))
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${inputs.carbs}", style = AapsType.bigValue, color = colors.textPrimary)
                            Text("grams", style = AapsType.caption, color = colors.textTertiary)
                        }
                        StepperButton(Icons.Rounded.Add, "plus", colors.accentTint, colors.accentOnLight) {
                            onInputs(inputs.copy(carbs = inputs.carbs + 5))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        quickChips.forEach { g ->
                            val active = inputs.carbs == g
                            Text(
                                "$g",
                                style = AapsType.listTitle,
                                color = if (active) colors.accentOnLight else colors.textSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(AapsShape.pill)
                                    .clickable { onInputs(inputs.copy(carbs = g)) }
                                    .background(if (active) colors.accentTintStrong else colors.controlFill)
                                    .padding(vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // Pre-bolus (stock "carb time"): deliver the bolus NOW, tell AAPS the carbs land in N minutes.
            // Only meaningful with carbs on board, so it follows the carbs card and hides at 0 g.
            if (inputs.carbs > 0) {
                AapsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("PRE-BOLUS", style = AapsType.label, color = colors.textSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StepperButton(Icons.Rounded.Remove, "minus", colors.controlFill, colors.textPrimary) {
                                onInputs(inputs.copy(carbTime = (inputs.carbTime - 5).coerceAtLeast(-60)))
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (inputs.carbTime > 0) "+${inputs.carbTime}" else "${inputs.carbTime}",
                                    style = AapsType.bigValue, color = colors.textPrimary
                                )
                                Text(
                                    when {
                                        inputs.carbTime > 0 -> "min until you eat"
                                        inputs.carbTime < 0 -> "min since you ate"
                                        else               -> "eating now"
                                    },
                                    style = AapsType.caption, color = colors.textTertiary
                                )
                            }
                            StepperButton(Icons.Rounded.Add, "plus", colors.accentTint, colors.accentOnLight) {
                                onInputs(inputs.copy(carbTime = (inputs.carbTime + 5).coerceAtMost(60)))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(0, 15, 20, 30).forEach { m ->
                                val active = inputs.carbTime == m
                                Text(
                                    if (m == 0) "now" else "+$m",
                                    style = AapsType.listTitle,
                                    color = if (active) colors.accentOnLight else colors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(AapsShape.pill)
                                        .clickable { onInputs(inputs.copy(carbTime = m)) }
                                        .background(if (active) colors.accentTintStrong else colors.controlFill)
                                        .padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                // Extended carbs. A slow (fat/protein) meal absorbs over HOURS; declaring that per-meal is the
                // only correct lever, because the model's tMaxG is global and drains every meal at one rate.
                // AAPS splits the entry into 15-min chunks that the APS/COB path already consumes.
                AapsCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("CARB ABSORPTION", style = AapsType.label, color = colors.textSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StepperButton(Icons.Rounded.Remove, "minus", colors.controlFill, colors.textPrimary) {
                                onInputs(inputs.copy(carbDurationHours = (inputs.carbDurationHours - 1).coerceAtLeast(0)))
                            }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    if (inputs.carbDurationHours == 0) "fast" else "${inputs.carbDurationHours} h",
                                    style = AapsType.bigValue, color = colors.textPrimary
                                )
                                Text(
                                    if (inputs.carbDurationHours == 0) "all at once" else "spread over",
                                    style = AapsType.caption, color = colors.textTertiary
                                )
                            }
                            StepperButton(Icons.Rounded.Add, "plus", colors.accentTint, colors.accentOnLight) {
                                onInputs(inputs.copy(carbDurationHours = (inputs.carbDurationHours + 1).coerceAtMost(8)))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(0, 2, 3, 4).forEach { h ->
                                val active = inputs.carbDurationHours == h
                                Text(
                                    if (h == 0) "fast" else "${h}h",
                                    style = AapsType.listTitle,
                                    color = if (active) colors.accentOnLight else colors.textSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(AapsShape.pill)
                                        .clickable { onInputs(inputs.copy(carbDurationHours = h)) }
                                        .background(if (active) colors.accentTintStrong else colors.controlFill)
                                        .padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Included in this dose
            AapsCard {
                Column {
                    Text("INCLUDED IN THIS DOSE", style = AapsType.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 4.dp))
                    FactorRow("Carbs", "${inputs.carbs} g", result.carbsInsulin, colors, base = true)
                    FactorRow("BG correction", "toward target", result.bgInsulin, colors, on = inputs.useBg, onToggle = { onInputs(inputs.copy(useBg = it)) })
                    FactorRow("Active insulin (IOB)", "reduces dose", result.iobInsulin, colors, on = inputs.useIob, softRed = true, onToggle = { onInputs(inputs.copy(useIob = it)) })
                    FactorRow("15-min trend", "glucose slope", result.trendInsulin, colors, on = inputs.useTrend, onToggle = { onInputs(inputs.copy(useTrend = it)) })
                    if (result.superBolusAvailable)
                        FactorRow("Superbolus", "2 h basal now", result.superBolusInsulin, colors, on = inputs.useSuperBolus, onToggle = { onInputs(inputs.copy(useSuperBolus = it)) })
                }
            }
        }

        // bottom bar
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.bar)
                .padding(horizontal = AapsSpacing.screenH, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Recommended", style = AapsType.label, color = colors.textSecondary)
                Text(result.totalText, style = AapsType.cardValue, color = colors.textPrimary)
            }
            val enabled = result.deliverable || result.carbsOnly
            Text(
                "Continue →",
                style = AapsType.title,
                color = if (enabled) colors.onAccent else colors.textTertiary,
                modifier = Modifier
                    .clip(AapsShape.button)
                    .background(if (enabled) colors.accent else colors.controlFill)
                    .then(if (enabled) Modifier.clickable(onClick = onContinue) else Modifier)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
private fun ConfirmStep(
    inputs: WizardInputs,
    result: WizardResult,
    colors: app.aaps.core.compose.theme.AapsColors,
    onDeliver: () -> Unit,
    onCancel: () -> Unit
) {
    // Scrollable for the same reason as InputStep: hold-to-deliver lives at the END of this column, so any
    // overflow (extra warning lines, pre-bolus note, small screen) would put the ONLY delivery control
    // off-screen. Scrolling keeps it reachable.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AapsSpacing.screenH)
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.sectionGap)
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(colors.accentTint),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.WaterDrop, contentDescription = null, tint = colors.accent, modifier = Modifier.size(30.dp)) }
        Text(result.totalText, style = AapsType.hero, color = colors.textPrimary)
        Text("insulin to deliver", style = AapsType.caption, color = colors.textTertiary)

        // breakdown
        AapsCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("HOW THIS WAS CALCULATED", style = AapsType.label, color = colors.textSecondary)
                BreakdownRow("Carbs", result.carbsInsulin, colors)
                BreakdownRow("BG correction", result.bgInsulin, colors)
                BreakdownRow("15-min trend", result.trendInsulin, colors)
                BreakdownRow("Active insulin (IOB)", result.iobInsulin, colors, softRed = true)
                if (result.superBolusAvailable) BreakdownRow("Superbolus", result.superBolusInsulin, colors)
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).background(colors.hairline).size(1.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Total (rounded)", style = AapsType.listTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(result.totalText, style = AapsType.listTitle, color = colors.textPrimary, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        // Pre-bolus is a timing decision, not a dose component — state it plainly on the confirm step so the
        // "deliver now, eat later" contract is explicit before the hold-to-confirm.
        if (inputs.carbs > 0 && inputs.carbDurationHours > 0)
            Text(
                "Extended carbs: ${inputs.carbs} g absorbing over ${inputs.carbDurationHours} h",
                style = AapsType.body, color = colors.accent, textAlign = TextAlign.Center
            )
        if (inputs.carbs > 0 && inputs.carbTime != 0)
            Text(
                if (inputs.carbTime > 0)
                    "Pre-bolus: delivering now — eat ${inputs.carbs} g in ${inputs.carbTime} min"
                else
                    "Carbs eaten ${-inputs.carbTime} min ago",
                style = AapsType.body, color = colors.accent, textAlign = TextAlign.Center
            )
        if (result.note.isNotBlank()) Text(result.note, style = AapsType.caption, color = colors.textTertiary)
        // Max-bolus cap — previously surfaced by the legacy confirm dialog we no longer show. Warn in red.
        if (result.cappedWarning.isNotBlank())
            Text(result.cappedWarning, style = AapsType.body, color = colors.high, textAlign = TextAlign.Center)

        HoldToConfirmButton(
            label = "Hold to deliver · ${result.totalText}",
            onConfirm = onDeliver,
            enabled = result.deliverable || result.carbsOnly
        )
        Text("Press and hold — no hidden gestures", style = AapsType.caption, color = colors.textTertiary)
        Text(
            "Cancel", style = AapsType.body, color = colors.textSecondary,
            modifier = Modifier.clip(AapsShape.pill).clickable(onClick = onCancel).padding(horizontal = 24.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun FactorRow(
    name: String,
    sub: String,
    contribution: String,
    colors: app.aaps.core.compose.theme.AapsColors,
    base: Boolean = false,
    on: Boolean = true,
    softRed: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = AapsType.listTitle, color = colors.textOnSurfaceStrong)
            Text(sub, style = AapsType.caption, color = colors.textTertiary)
        }
        Text(
            contribution,
            style = AapsType.listTitle,
            color = if (softRed) colors.iob else colors.textPrimary,
            modifier = Modifier.padding(end = 12.dp)
        )
        when {
            base            -> Text("BASE", style = AapsType.label, color = colors.textTertiary)
            onToggle != null -> Switch(
                checked = on, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedTrackColor = colors.controlFill,
                    uncheckedThumbColor = colors.textSecondary,
                    uncheckedBorderColor = colors.hairline
                )
            )
        }
    }
}

@Composable
private fun BreakdownRow(name: String, value: String, colors: app.aaps.core.compose.theme.AapsColors, softRed: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(name, style = AapsType.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = AapsType.body, color = if (softRed) colors.iob else colors.textPrimary)
    }
}

@Composable
private fun StepperButton(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = cd, tint = fg, modifier = Modifier.size(24.dp)) }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    Box(Modifier.clip(CircleShape).clickable(onClick = onClick).padding(8.dp)) {
        Icon(icon, contentDescription = cd, tint = AapsTheme.colors.textSecondary)
    }
}

private fun fadeThrough() =
    (androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut())

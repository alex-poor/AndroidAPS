package app.aaps.plugins.main.general.overview.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.compose.theme.AapsSemantic
import app.aaps.core.compose.theme.AapsTheme

/**
 * Design-review preview of the redesigned Home with representative placeholder data (matches the
 * handoff mockup `screenshots/02-home.png`). Open in Android Studio's Compose preview — this is not
 * used at runtime.
 */
@Preview(name = "Home", widthDp = 412, heightDp = 892, showBackground = true, backgroundColor = 0xFF0E1116)
@Composable
private fun HomeScreenPreview() {
    AapsTheme {
        HomeScreen(
            state = HomeUiState(
                loopStateLabel = "Closed loop",
                loopSubLabel = "· looping",
                loopColor = AapsSemantic.inRange,
                looping = true,
                bg = "6.4",
                bgColor = AapsSemantic.inRange,
                units = "mmol/L",
                trendArrow = "↗",
                delta = "+0.2",
                timeAgo = "2 min ago",
                eventualBg = "5.8",
                ringProgress = 0.8f,
                gaugeFraction = 0.42f,
                gaugeLow = "3.9",
                gaugeTarget = "target 5.5–7.0",
                gaugeHigh = "10.0",
                iob = "1.24 U",
                iobSub = "bolus + basal",
                cob = "18 g",
                basal = "120%",
                supplies = listOf(
                    HomeUiState.Supply("Cannula", "2d", AapsSemantic.inRange),
                    HomeUiState.Supply("Sensor", "6d", AapsSemantic.high),
                    HomeUiState.Supply("Reservoir", "88 U", AapsSemantic.inRange),
                    HomeUiState.Supply("Battery", "74%", AapsSemantic.inRange)
                ),
                ready = true
            ),
            actions = HomeActions(),
            graph = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(AapsTheme.colors.surface2),
                    contentAlignment = Alignment.Center
                ) { Text("Glucose graph", color = AapsTheme.colors.textTertiary) }
            }
        )
    }
}

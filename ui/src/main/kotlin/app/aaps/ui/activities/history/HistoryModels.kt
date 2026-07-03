package app.aaps.ui.activities.history

import androidx.compose.runtime.Immutable

enum class HistoryKind { BOLUS, SMB, CARBS, EVENT }

enum class HistoryFilter { ALL, BOLUS, CARBS, EVENTS }

@Immutable
data class HistoryItem(
    val timestamp: Long,
    val dayLabel: String,   // "Today" / "Yesterday" / date — precomputed for grouping
    val time: String,       // "12:30"
    val kind: HistoryKind,
    val title: String,
    val sub: String,
    val value: String
)

@Immutable
data class HistoryUiState(
    val loading: Boolean = true,
    val items: List<HistoryItem> = emptyList()
)

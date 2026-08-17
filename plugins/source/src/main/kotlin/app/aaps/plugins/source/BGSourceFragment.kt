package app.aaps.plugins.source

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNewBG
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.source.compose.BgRow
import app.aaps.plugins.source.compose.BgSourceScreen
import app.aaps.plugins.source.compose.BgSourceState
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * BG source tab. The RecyclerView + `ActionModeHelper` contextual delete bar is replaced by
 * [BgSourceScreen]; selection state lives in this fragment instead of the ActionMode, but removal is
 * unchanged — the same `OKDialog` confirmation and the same `persistenceLayer.invalidateGlucoseValue`
 * with `Action.BG_REMOVED` / `Sources.BgFragment`, so the audit trail is identical.
 */
class BGSourceFragment : DaggerFragment() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var profileUtil: ProfileUtil

    private val disposable = CompositeDisposable()
    private var millsToThePast = T.hours(36).msecs()

    private val state = mutableStateOf(BgSourceState())

    /** Raw readings behind the current [state], kept so delete can map a row id back to its GV. */
    private var loaded: List<GV> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    BgSourceScreen(
                        state = state.value,
                        onToggle = ::toggle,
                        onStartSelecting = ::startSelecting,
                        onCancelSelecting = { state.value = state.value.copy(selecting = false, selected = emptySet()) },
                        onDeleteSelected = ::removeSelected,
                        onLoadMore = ::loadMore
                    )
                }
            }
        }

    private fun trendSymbol(arrow: TrendArrow?): String = when (arrow) {
        TrendArrow.TRIPLE_UP, TrendArrow.DOUBLE_UP     -> "⇈"
        TrendArrow.SINGLE_UP                           -> "↑"
        TrendArrow.FORTY_FIVE_UP                       -> "↗"
        TrendArrow.FLAT                                -> "→"
        TrendArrow.FORTY_FIVE_DOWN                     -> "↘"
        TrendArrow.SINGLE_DOWN                         -> "↓"
        TrendArrow.TRIPLE_DOWN, TrendArrow.DOUBLE_DOWN -> "⇊"
        else                                           -> ""
    }

    private fun toggle(id: Long) {
        val selected = state.value.selected.toMutableSet()
        if (!selected.add(id)) selected.remove(id)
        state.value = state.value.copy(selected = selected)
    }

    private fun startSelecting(id: Long) {
        state.value = state.value.copy(selecting = true, selected = state.value.selected + id)
    }

    private fun loadMore() {
        // Guard against the end-of-list effect firing repeatedly before the wider query comes back.
        if (loaded.isEmpty()) return
        millsToThePast += T.hours(24).msecs()
        load()
    }

    private fun load() {
        val now = System.currentTimeMillis()
        disposable += persistenceLayer
            .getBgReadingsDataFromTime(now - millsToThePast, false)
            .observeOn(aapsSchedulers.main)
            .subscribe { list -> render(list) }
    }

    private fun render(list: List<GV>) {
        loaded = list
        val rows = list.mapIndexed { index, gv ->
            val newDay = index == 0 || !dateUtil.isSameDay(gv.timestamp, list[index - 1].timestamp)
            // The legacy list tinted a reading that landed < 20 s after the previous one; those are
            // almost always a duplicate broadcast rather than a real second reading.
            val tooClose = index > 0 && list[index - 1].timestamp - gv.timestamp < T.secs(20).msecs()
            BgRow(
                id = gv.id,
                timestamp = gv.timestamp,
                dayLabel = if (newDay) dateUtil.dateStringRelative(gv.timestamp, rh) else null,
                time = dateUtil.timeStringWithSeconds(gv.timestamp),
                value = profileUtil.fromMgdlToStringInUnits(gv.value),
                trend = trendSymbol(gv.trendArrow),
                fromNightscout = gv.ids.nightscoutId != null,
                valid = gv.isValid,
                tooClose = tooClose
            )
        }
        // Drop selections whose rows are no longer loaded, so the count cannot drift.
        val stillThere = rows.mapTo(HashSet()) { it.id }
        val selected = state.value.selected.intersect(stillThere)
        state.value = state.value.copy(rows = rows, selected = selected, selecting = state.value.selecting && selected.isNotEmpty())
    }

    override fun onResume() {
        super.onResume()
        load()
        disposable += rxBus
            .toObservable(EventNewBG::class.java)
            .observeOn(aapsSchedulers.io)
            .debounce(1L, TimeUnit.SECONDS)
            .subscribe({ load() }, fabricPrivacy::logException)
    }

    override fun onPause() {
        state.value = state.value.copy(selecting = false, selected = emptySet())
        disposable.clear()
        super.onPause()
    }

    private fun getConfirmationText(selected: List<GV>): String {
        if (selected.size == 1) {
            val glucoseValue = selected.first()
            return dateUtil.dateAndTimeString(glucoseValue.timestamp) + "\n" + profileUtil.fromMgdlToUnits(glucoseValue.value)
        }
        return rh.gs(app.aaps.core.ui.R.string.confirm_remove_multiple_items, selected.size)
    }

    @SuppressLint("CheckResult")
    private fun removeSelected() {
        val ids = state.value.selected
        val selected = loaded.filter { it.id in ids }
        if (selected.isEmpty()) return
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.removerecord), getConfirmationText(selected), {
                selected.forEach { glucoseValue ->
                    disposable += persistenceLayer.invalidateGlucoseValue(
                        glucoseValue.id, action = Action.BG_REMOVED,
                        source = Sources.BgFragment, note = null,
                        listValues = listOf(ValueWithUnit.Timestamp(glucoseValue.timestamp))
                    ).subscribe()
                }
                state.value = state.value.copy(selecting = false, selected = emptySet())
            })
        }
    }
}

package app.aaps.ui.activities

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.ui.activities.history.HistoryItem
import app.aaps.ui.activities.history.HistoryKind
import app.aaps.ui.activities.history.HistoryScreen
import app.aaps.ui.activities.history.HistoryUiState
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Redesigned History timeline. UI is Compose ([HistoryScreen]); a unified, day-grouped, read-only list
 * of boluses / carbs / therapy events over the last 14 days, merged from the persistence layer off the
 * main thread. (Edit/delete of individual treatments is a later pass — see the redesign notes.)
 */
class TreatmentsActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private val disposable = CompositeDisposable()
    private val historyState = mutableStateOf(HistoryUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(ComposeView(this).apply {
            setContent { AapsTheme { HistoryScreen(historyState.value, onBack = { finish() }) } }
        })
        disposable += Single.fromCallable { buildHistory() }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ historyState.value = it }, fabricPrivacy::logException)
    }

    private fun dayLabel(ts: Long, now: Long): String = when (dateUtil.dateString(ts)) {
        dateUtil.dateString(now)                    -> "Today"
        dateUtil.dateString(now - 86_400_000L)      -> "Yesterday"
        else                                        -> dateUtil.dateString(ts)
    }

    private fun eventTitle(type: Any): String =
        type.toString().replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

    private fun buildHistory(): HistoryUiState {
        val now = dateUtil.now()
        val from = now - 14L * 86_400_000L
        val items = mutableListOf<HistoryItem>()

        persistenceLayer.getBolusesFromTimeToTime(from, now, false).forEach { bs ->
            if (!bs.isValid || bs.type == BS.Type.PRIMING) return@forEach
            val smb = bs.type == BS.Type.SMB
            items += HistoryItem(
                bs.timestamp, dayLabel(bs.timestamp, now), dateUtil.timeString(bs.timestamp),
                if (smb) HistoryKind.SMB else HistoryKind.BOLUS,
                if (smb) "SMB" else "Bolus", bs.notes ?: "",
                rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bs.amount)
            )
        }
        persistenceLayer.getCarbsFromTimeToTimeExpanded(from, now, false).forEach { ca ->
            if (!ca.isValid) return@forEach
            items += HistoryItem(
                ca.timestamp, dayLabel(ca.timestamp, now), dateUtil.timeString(ca.timestamp),
                HistoryKind.CARBS, "Carbs", ca.notes ?: "", "${ca.amount.toInt()} g"
            )
        }
        persistenceLayer.getTherapyEventDataFromToTime(from, now).blockingGet().forEach { te ->
            if (!te.isValid) return@forEach
            items += HistoryItem(
                te.timestamp, dayLabel(te.timestamp, now), dateUtil.timeString(te.timestamp),
                HistoryKind.EVENT, eventTitle(te.type), te.note ?: "", ""
            )
        }
        items.sortByDescending { it.timestamp }
        return HistoryUiState(loading = false, items = items)
    }

    override fun onDestroy() {
        super.onDestroy()
        disposable.clear()
    }
}

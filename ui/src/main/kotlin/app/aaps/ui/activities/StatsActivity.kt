package app.aaps.ui.activities

import android.os.Bundle
import androidx.collection.LongSparseArray
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.stats.TIR
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.ui.R
import app.aaps.ui.activities.stats.StatsScreen
import app.aaps.ui.activities.stats.StatsUiState
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Locale
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Redesigned Statistics screen. UI is Compose ([StatsScreen]); TIR is computed from [TirCalculator],
 * TDD from [TddCalculator], and average/CV/GMI derived from the raw readings — all off the main thread.
 */
class StatsActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var tirCalculator: TirCalculator
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil

    private val disposable = CompositeDisposable()
    private val statsState = mutableStateOf(StatsUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = rh.gs(R.string.statistics)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        setContentView(ComposeView(this).apply {
            setContent { AapsTheme { StatsScreen(statsState.value, onRange = ::compute) } }
        })
        compute(7)
    }

    private fun compute(days: Int) {
        statsState.value = statsState.value.copy(loading = true, rangeDays = days)
        disposable += Single.fromCallable { buildStats(days) }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ statsState.value = it }, fabricPrivacy::logException)
    }

    private fun agg(arr: LongSparseArray<TIR>?): IntArray {
        val out = intArrayOf(0, 0, 0, 0) // below, inRange, above, count
        if (arr == null) return out
        for (i in 0 until arr.size()) {
            val t = arr.valueAt(i); out[0] += t.below; out[1] += t.inRange; out[2] += t.above; out[3] += t.count
        }
        return out
    }

    private fun buildStats(days: Int): StatsUiState {
        val units = profileFunction.getUnits()
        val now = dateUtil.now()
        val from = now - days * 86_400_000L
        val values = persistenceLayer.getBgReadingsDataFromTimeToTime(from, now, true).map { it.value }.filter { it > 0 }
        val avgMgdl = if (values.isNotEmpty()) values.average() else 0.0
        val sd = if (values.size > 1) sqrt(values.sumOf { (it - avgMgdl) * (it - avgMgdl) } / values.size) else 0.0
        val cv = if (avgMgdl > 0) sd / avgMgdl * 100 else 0.0
        val gmi = if (avgMgdl > 0) 3.31 + 0.02392 * avgMgdl else 0.0

        val lowMark = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewLowMark), units)
        val highMark = profileUtil.convertToMgdl(preferences.get(UnitDoubleKey.OverviewHighMark), units)
        val main = agg(tirCalculator.calculate(days.toLong(), lowMark, highMark))
        val ext = agg(tirCalculator.calculate(days.toLong(), 54.0, 250.0)) // 3.0 / 13.9 mmol clinical extremes
        val count = main[3].coerceAtLeast(1)
        fun pct(x: Int) = x.toDouble() / count * 100.0
        val vLow = ext[0]; val vHigh = ext[2]
        val low = (main[0] - vLow).coerceAtLeast(0); val high = (main[2] - vHigh).coerceAtLeast(0)

        val avgTdd = tddCalculator.averageTDD(tddCalculator.calculate(days.toLong(), true))?.data
        val tddU = avgTdd?.let { if (it.totalAmount > 0) it.totalAmount else it.basalAmount + it.bolusAmount }

        return StatsUiState(
            loading = false,
            rangeDays = days,
            veryLow = pct(vLow), low = pct(low), inRange = pct(main[1]), high = pct(high), veryHigh = pct(vHigh),
            gmi = if (gmi > 0) String.format(Locale.getDefault(), "%.1f%%", gmi) else "--",
            avgGlucose = if (avgMgdl > 0) profileUtil.fromMgdlToStringInUnits(avgMgdl) else "--",
            avgGlucoseUnit = if (units == GlucoseUnit.MMOL) "mmol/L" else "mg/dL",
            cv = if (cv > 0) String.format(Locale.getDefault(), "%.0f%%", cv) else "--",
            cvGood = cv in 0.1..36.0,
            avgTdd = tddU?.let { String.format(Locale.getDefault(), "%.1f U", it) } ?: "--",
            carbsPerDay = avgTdd?.carbs?.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%.0f g", it) } ?: "--"
        )
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
    }
}

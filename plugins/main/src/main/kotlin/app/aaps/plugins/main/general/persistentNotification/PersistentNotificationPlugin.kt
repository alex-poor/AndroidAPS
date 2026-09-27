package app.aaps.plugins.main.general.persistentNotification

import android.app.NotificationManager
import android.content.Context
import android.graphics.Paint
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.ProcessedTbrEbData
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationHolder
import app.aaps.core.interfaces.overview.LastBgData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAutosensCalculationFinished
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.IconsProvider
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.generateCOBString
import app.aaps.core.objects.extensions.round
import app.aaps.core.objects.extensions.toStringShort
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.TimeUnit
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("PrivatePropertyName", "DEPRECATION")
@Singleton
class PersistentNotificationPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val aapsSchedulers: AapsSchedulers,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val fabricPrivacy: FabricPrivacy,
    private val activePlugins: ActivePlugin,
    private val iobCobCalculator: IobCobCalculator,
    private val processedTbrEbData: ProcessedTbrEbData,
    private val rxBus: RxBus,
    private val context: Context,
    private val notificationHolder: NotificationHolder,
    private val dummyServiceHelper: DummyServiceHelper,
    private val iconsProvider: IconsProvider,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val config: Config,
    private val decimalFormatter: DecimalFormatter,
    private val preferences: Preferences,
    private val persistenceLayer: PersistenceLayer,
    private val lastBgData: LastBgData,
    private val trendCalculator: TrendCalculator,
    private val dateUtil: DateUtil
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .neverVisible(true)
        .pluginName(R.string.ongoingnotificaction)
        .enableByDefault(true)
        .alwaysEnabled(true)
        .showInList { false }
        .description(R.string.description_persistent_notification),
    aapsLogger, rh
) {

    private val disposable = CompositeDisposable()

    /** One pending re-render for when the shown reading turns stale; replaced on every update. */
    private var staleRefresh: Disposable? = null

    /** Resolves its palette from the device configuration, so it is built once and not per update. */
    private val chart by lazy { BgNotificationChart(context) }

    override fun onStart() {
        super.onStart()
        notificationHolder.createNotificationChannel()
        // One debounced stream. Each rebuild costs IOB/COB, a DB query, two bitmaps and a Binder round-trip
        // near the 1 MB cap, and one BG that leads to a temp fires several of these events within a second
        // (and EventPreferenceChange fires for every preference write). Coalesce them.
        disposable += Observable.merge(
            listOf(
                rxBus.toObservable(EventRefreshOverview::class.java),
                rxBus.toObservable(EventInitializationChanged::class.java),
                rxBus.toObservable(EventAutosensCalculationFinished::class.java),
                rxBus.toObservable(EventPreferenceChange::class.java)
            )
        )
            .debounce(UPDATE_DEBOUNCE_MS, TimeUnit.MILLISECONDS, aapsSchedulers.io)
            .subscribe({ triggerNotificationUpdate() }, fabricPrivacy::logException)
    }

    override fun onStop() {
        disposable.clear()
        staleRefresh?.dispose()
        dummyServiceHelper.stopService(context)
        super.onStop()
    }

    private fun triggerNotificationUpdate() {
        updateNotification()
        dummyServiceHelper.startService(context)
        scheduleStaleRefresh()
    }

    /**
     * Nothing else re-renders the notification when the CGM goes silent — every trigger is a calculation,
     * and without new data none runs — so the strike-through and the "old data" title would never appear
     * and the last reading would sit there looking live. Re-render once when it crosses the stale line.
     */
    private fun scheduleStaleRefresh() {
        staleRefresh?.dispose()
        val lastBg = lastBgData.lastBg() ?: return
        val delay = lastBg.timestamp + STALE_AFTER_MS - dateUtil.now()
        if (delay <= 0) return
        staleRefresh = Observable.timer(delay, TimeUnit.MILLISECONDS, aapsSchedulers.io)
            .subscribe({ updateNotification() }, fabricPrivacy::logException)
    }

    private fun updateNotification() {
        if (!config.appInitialized) return
        val pump = activePlugins.activePump
        var line1: String?
        var line2: String? = null
        var line3: String? = null
        var tempBasalText: String? = null
        var avgDeltaText: String? = null
        val profileValid = profileFunction.isProfileValid("Notification")
        if (profileValid) {
            val lastBG = iobCobCalculator.ads.lastBg()
            val glucoseStatus = glucoseStatusProvider.glucoseStatusData
            if (lastBG != null) {
                line1 = profileUtil.fromMgdlToStringInUnits(lastBG.recalculated)
                // The bucketed reading carries no arrow on the averaged 1-min path (Libre 3) or with
                // smoothing, which printed "??" here; the custom views already use the calculator.
                val arrowSymbol = (trendCalculator.getTrendArrow(iobCobCalculator.ads) ?: lastBG.trendArrow).symbol
                if (glucoseStatus != null) {
                    // Android bundles this notification with AAPS's alarms, and a bundled child is
                    // rendered as ONE ellipsized line built from title + text - the custom views
                    // below are not drawn in that state. So the title carries only what has to
                    // survive truncation: value, trend, delta. The rest moved to line2.
                    line1 += "  " + arrowSymbol +
                        "  Δ" + profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.delta)
                    avgDeltaText = "avgΔ" + profileUtil.fromMgdlToSignedStringInUnits(glucoseStatus.shortAvgDelta)
                } else {
                    line1 += " " +
                        rh.gs(R.string.old_data) +
                        " "
                }
            } else {
                line1 = rh.gs(app.aaps.core.ui.R.string.missed_bg_readings)
            }
            val activeTemp = processedTbrEbData.getTempBasalIncludingConvertedExtended(System.currentTimeMillis())
            if (activeTemp != null) {
                tempBasalText = activeTemp.toStringShort(rh)
            }
            //IOB
            val bolusIob = iobCobCalculator.calculateIobFromBolus().round()
            val basalIob = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
            val cobString = iobCobCalculator.getCobInfo("PersistentNotificationPlugin").generateCOBString(decimalFormatter)
            line2 = rh.gs(app.aaps.core.ui.R.string.treatments_iob_label_string) + " " +
                rh.gs(app.aaps.core.ui.R.string.format_insulin_units, (bolusIob.iob + basalIob.basaliob)) + " " +
                rh.gs(app.aaps.core.ui.R.string.cob) + ": " + cobString
            // Displaced from the title so it stays short enough to read when bundled.
            listOfNotNull(tempBasalText, avgDeltaText).forEach { line2 += "  " + it }
            line3 = rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, pump.baseBasalRate) + " - " + profileFunction.getProfileName()
        } else {
            line1 = rh.gs(app.aaps.core.ui.R.string.no_profile_set)
        }
        val builder = NotificationCompat.Builder(context, notificationHolder.channelID)
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)
        builder.setCategory(NotificationCompat.CATEGORY_STATUS)
        builder.setSmallIcon(iconsProvider.getNotificationIcon())
        builder.setContentTitle(line1)
        // Stamp the READING's time, not the post time: SystemUI then ages the header ("12m") on its own,
        // so a silent CGM is visible even between re-renders.
        lastBgData.lastBg()?.let { builder.setWhen(it.timestamp).setShowWhen(true) }
        if (line2 != null) builder.setContentText(line2)
        if (line3 != null) builder.setSubText(line3)
        // The text above is still the notification's content: Android Auto, wearables and TalkBack
        // read it, and SystemUI falls back to it if it declines to inflate the custom views.
        val decorated = profileValid && applyGlucoseViews(builder, listOfNotNull(line2, line3).joinToString("  \u00b7  "))
        // The app icon is redundant beside the chart, and its bitmap competes for the same 1 MB
        // Binder budget the custom views spend.
        if (!decorated) builder.setLargeIcon(rh.decodeResource(iconsProvider.getIcon()))
        builder.setContentIntent(notificationHolder.openAppIntent(context))
        val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = builder.build()
        mNotificationManager.notify(notificationHolder.notificationID, notification)
        notificationHolder.notification = notification
    }

    /**
     * Attaches the lock-screen readout: the glucose value at a size that survives being glanced at,
     * a trend arrow, and the last 3 h of glucose as a chart.
     *
     * This exists because the plain-text notification put BG, delta, average delta and the temp
     * basal into one contentTitle, which the lock screen then ellipsized mid-string. Those numbers
     * are not lost - they moved to the expanded view's status row, where there is room for them.
     *
     * @return true if the custom views were attached, false if there is nothing to draw.
     */
    private fun applyGlucoseViews(builder: NotificationCompat.Builder, statusText: String): Boolean {
        val lastBg = lastBgData.lastBg() ?: return false
        val lowMark = preferences.get(UnitDoubleKey.OverviewLowMark)
        val highMark = preferences.get(UnitDoubleKey.OverviewHighMark)
        // One authority for the colour: the number and the trace are tinted by the same call, so
        // they cannot disagree about whether the reading is in range.
        val color = chart.colorFor(profileUtil.fromMgdlToUnits(lastBg.recalculated), lowMark, highMark)
        val arrow = trendCalculator.getTrendArrow(iobCobCalculator.ads)?.notificationIcon()
        val delta = glucoseStatusProvider.glucoseStatusData
            ?.let { "\u0394 " + profileUtil.fromMgdlToSignedStringInUnits(it.delta) }
            ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

        val collapsed = RemoteViews(context.packageName, R.layout.notification_bg_collapsed)
        val expanded = RemoteViews(context.packageName, R.layout.notification_bg_expanded)

        for (views in listOf(collapsed, expanded)) {
            views.setTextViewText(R.id.notification_bg, profileUtil.fromMgdlToStringInUnits(lastBg.recalculated))
            views.setTextColor(R.id.notification_bg, color)
            // Struck through once the reading is stale, as the widget and Overview both do.
            views.setInt(
                R.id.notification_bg, "setPaintFlags",
                if (lastBgData.isActualBg()) Paint.ANTI_ALIAS_FLAG
                else Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
            )
            arrow?.let { views.setImageViewResource(R.id.notification_arrow, it) }
            views.setViewVisibility(R.id.notification_arrow, if (arrow != null) View.VISIBLE else View.GONE)
            views.setInt(R.id.notification_arrow, "setColorFilter", color)
            views.setTextViewText(R.id.notification_delta, delta)
            views.setTextViewText(R.id.notification_age, dateUtil.minOrSecAgo(rh, lastBg.timestamp))
        }
        expanded.setTextViewText(R.id.notification_status, statusText)

        val to = dateUtil.now()
        val from = to - CHART_WINDOW
        val readings = recentReadings(from, to)
        chart.render(BgNotificationChart.COLLAPSED_W, BgNotificationChart.COLLAPSED_H, readings, from, to, lowMark, highMark, withMarks = false)
            ?.let { collapsed.setImageViewBitmap(R.id.notification_chart, it) }
        chart.render(BgNotificationChart.EXPANDED_W, BgNotificationChart.EXPANDED_H, readings, from, to, lowMark, highMark, withMarks = true)
            ?.let { expanded.setImageViewBitmap(R.id.notification_chart, it) }

        builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        builder.setCustomContentView(collapsed)
        builder.setCustomBigContentView(expanded)
        return true
    }

    /**
     * The last 3 h of CGM in display units.
     *
     * Read from the database rather than from OverviewData, whose series are only refreshed while
     * the Overview is on screen - which is exactly when the notification does not matter.
     */
    private fun recentReadings(from: Long, to: Long): List<ChartReading> =
        persistenceLayer.getBgReadingsDataFromTimeToTime(from, to, true)
            .filter { it.isValid }
            // xDrip broadcasts each reading more than once, so the table holds near-duplicates.
            // One point per 5-minute bucket keeps the trace from doubling back on itself.
            .associateBy { it.timestamp / T.mins(5).msecs() }
            .values
            .map { ChartReading(it.timestamp, profileUtil.fromMgdlToUnits(it.value)) }

    companion object {

        /** How much history the chart shows. Long enough to read a meal, short enough to stay legible. */
        private val CHART_WINDOW = T.hours(3).msecs()

        private const val UPDATE_DEBOUNCE_MS = 2_000L

        /** Matches the 9-minute "actual BG" line the widget and Overview strike through at. */
        private val STALE_AFTER_MS = T.mins(9).plus(T.secs(15)).msecs()
    }
}

/**
 * Trend arrows that survive being drawn in a notification.
 *
 * core:objects' arrows fill with `?attr/defaultTextColor`. The home-screen widget supplies that
 * attribute via android:theme on its root, but a notification is inflated in SystemUI's theme where
 * it does not exist, so the vector draws transparent - and setColorFilter cannot tint transparent
 * pixels away. These are the same paths with a solid fill, so the tint takes.
 */
private fun TrendArrow.notificationIcon(): Int? = when (this) {
    TrendArrow.TRIPLE_DOWN     -> R.drawable.notification_arrow_invalid
    TrendArrow.DOUBLE_DOWN     -> R.drawable.notification_arrow_doubledown
    TrendArrow.SINGLE_DOWN     -> R.drawable.notification_arrow_singledown
    TrendArrow.FORTY_FIVE_DOWN -> R.drawable.notification_arrow_fortyfivedown
    TrendArrow.FLAT            -> R.drawable.notification_arrow_flat
    TrendArrow.FORTY_FIVE_UP   -> R.drawable.notification_arrow_fortyfiveup
    TrendArrow.SINGLE_UP       -> R.drawable.notification_arrow_singleup
    TrendArrow.DOUBLE_UP       -> R.drawable.notification_arrow_doubleup
    TrendArrow.TRIPLE_UP       -> R.drawable.notification_arrow_invalid
    // No trend to show is better than a question mark taking the arrow's slot.
    TrendArrow.NONE            -> null
}

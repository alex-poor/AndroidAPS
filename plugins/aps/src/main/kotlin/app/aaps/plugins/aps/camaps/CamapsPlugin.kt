package app.aaps.plugins.aps.camaps

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.TT
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.MidnightTime
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.target
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.hovorka.GlucoseEstimator
import app.aaps.plugins.aps.hovorka.HovorkaEkf
import app.aaps.plugins.aps.hovorka.HovorkaImmBank
import app.aaps.plugins.aps.hovorka.HovorkaModel
import app.aaps.plugins.aps.hovorka.HovorkaParams
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * CamAPS FX replication — a SECOND, separate APS algorithm, selectable alongside HovorkaMPC.
 *
 * Built deliberately as its own plugin rather than as flags on the existing one. The fork's controller
 * started as a replication and diverged: the decoded zone-bounded reference trajectory was replaced by an
 * unbounded exponential, and six containment guards accumulated afterwards. Over 30 real days that stack
 * overrides the model on 95% of ticks, so toggling any one guard measures nothing — each is absorbed by
 * the others. Only two whole configurations can be compared, and only if both can run on the same person.
 * That is what this is for.
 *
 * Control path, per report/algorithm-spec.md:
 *   §3 estimator   — the IMM bank (CamAPS's own), 8 Hovorka submodels spanning carb absorption, mixed by
 *                    Bayesian posterior. Rebuilt each tick from a trailing window, so no persisted filter
 *                    state can silently corrupt.
 *   §4/§5 control  — [CamapsMpc]: zone-BOUNDED reference trajectory, 180-min horizon BIR sequence, and
 *                    the GetBIR output law flooring at 0.7x the PLANNED horizon mean.
 *
 * Not carried over from HovorkaMPC, by design: descent guard, high-correction floor, current-glucose
 * damper, IOB-divergence detector, site guard, SMB. CamAPS is basal-only (TBR), and its safety machinery
 * is the trajectory bound plus the BIR floor — nothing else. A hard hypo suspend is kept inside
 * [CamapsMpc]; see its class doc for why that one deviation is deliberate.
 *
 * NOT YET IMPLEMENTED: §6 TDD adaptation. The operating point is the profile basal. That is the honest
 * starting position — the fork's own TDD layer is what drove its anchor to 55% of profile — and it should
 * be added only once this configuration has been observed.
 *
 * SAFETY POSTURE. TBR-only; AAPS's maxBasal/maxIOB constraints still apply through Loop, as does the
 * objectives/closed-loop gating. Not clinically validated. Never enable by default.
 */
@Singleton
class CamapsPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val rxBus: RxBus,
    private val profileFunction: ProfileFunction,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val persistenceLayer: PersistenceLayer,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val tddCalculator: TddCalculator,
    private val apsResultProvider: Provider<APSResult>
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .pluginName(R.string.camaps_replica_name)
        .shortName(R.string.camaps_replica_shortname)
        .description(R.string.camaps_replica_description),
    aapsLogger, rh
), APS {

    override val algorithm = APSResult.Algorithm.UNKNOWN
    override var lastAPSResult: APSResult? = null
    override var lastAPSRun: Long = 0

    override fun isEnabled() = isEnabled(PluginType.APS)
    override fun getGlucoseStatusData(allowOldData: Boolean): GlucoseStatus? =
        glucoseStatusCalculatorSMB.getGlucoseStatusData(allowOldData)

    override fun configuration(): JSONObject = JSONObject()
    override fun applyConfiguration(configuration: JSONObject) {}
    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "camaps_replica_settings"
            title = rh.gs(R.string.camaps_replica_name)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.CamapsTddAdaptation,
                    summary = R.string.camaps_tdd_adaptation_summary, title = R.string.camaps_tdd_adaptation_title
                )
            )
        }
    }

    /**
     * §7 `MPC::AdjustTDDbasedOnCGM`, gated on [BooleanKey.CamapsTddAdaptation] and DEFAULT OFF.
     *
     * Returns the multiplicative correction for today, or 1.0 when the feature is off or the window is too
     * thin to mean anything. Recomputed once per LOCAL day — `now / DAY_MS` is a UTC day index, which on
     * this fork once made a "daily" value roll over at local noon, so the offset is applied here.
     *
     * The window is the binary's: 23 hours ending at 08:00. Before 08:00 that window has not closed, so
     * the previous day's is used rather than a partial one.
     *
     * ⚠️ On this patient's own 30 days the hypo override fires on 18 of 29 days and makes 15 of them ask
     * for LESS insulin at a mean glucose of 7.2 against a 5.8 target — see [CamapsTddAdapter]. That is why
     * the pref defaults to false, and why turning it on is a therapy decision and not a tuning step.
     */
    private fun tddAdaptationFactor(targetMmol: Double, now: Long): Double {
        if (!preferences.get(BooleanKey.CamapsTddAdaptation)) return 1.0
        val tz = java.util.TimeZone.getDefault()
        val dayKey = (now + tz.getOffset(now)) / 86_400_000L
        if (dayKey != tddCacheDay) {
            val midnight = MidnightTime.calc(now)
            var end = midnight + CamapsTddAdapter.WINDOW_END_HOUR * 3_600_000L
            if (end > now) end -= 86_400_000L                       // 08:00 has not passed yet today
            val start = end - CamapsTddAdapter.WINDOW_H * 3_600_000L
            val bg = persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, true)
                .map { it.value / MGDL_PER_MMOL }
            tddCacheFactor = if (bg.size < CamapsTddAdapter.MIN_ENTRIES) 1.0
                             else CamapsTddAdapter.factor(bg, targetMmol)
            tddCacheDay = dayKey
            aapsLogger.debug(LTag.APS, "CamAPS TDD adaptation: ${bg.size} readings -> x%.3f".format(tddCacheFactor))
        }
        return tddCacheFactor
    }

    private var tddCacheDay = Long.MIN_VALUE
    private var tddCacheFactor = 1.0

    /**
     * Mean of the 48 half-hourly basal rates, i.e. the same array `MPC::MaximumPersonalRange` averages
     * (`this+0x18`, 48 floats, divided by 48.0). Sampled on the half hour from the profile rather than
     * read from a stored array, which is equivalent for a step profile.
     */
    /** Minutes between the two most recent CGM samples, or NaN with fewer than two (CamapsMpc §6.5). */
    private fun cgmGapMinutes(now: Long): Double {
        val bg = persistenceLayer.getBgReadingsDataFromTimeToTime(now - 6 * 3_600_000L, now, true)
            .sortedByDescending { it.timestamp }
        return if (bg.size < 2) Double.NaN else (bg[0].timestamp - bg[1].timestamp) / 60_000.0
    }

    private fun meanProfileBasalUhr(profile: Profile): Double {
        val midnight = MidnightTime.calc(dateUtil.now())
        var sum = 0.0
        for (i in 0 until 48) sum += profile.getBasal(midnight + i * 30 * 60_000L)
        return sum / 48.0
    }

    private var cachedModel: HovorkaModel? = null
    private var cachedKey = ""

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        lastAPSResult = null
        if (!isEnabled()) return
        val profile = profileFunction.getProfile() ?: return
        val glucoseStatus = glucoseStatusCalculatorSMB.getGlucoseStatusData(false) ?: return
        val now = dateUtil.now()

        val profileTargetMmol = profile.getTargetMgdl() / MGDL_PER_MMOL
        val tempTargetMgdl = persistenceLayer.getTemporaryTargetActiveAt(now)?.target()
        val controlTargetMmol = (tempTargetMgdl ?: profile.getTargetMgdl()) / MGDL_PER_MMOL
        val weightKg = preferences.get(DoubleKey.HovorkaBodyWeight)
        // §7 AdjustTDDbasedOnCGM scales the operating point. Default OFF, so this is 1.0 unless the user
        // has turned it on; see [tddAdaptationFactor] for why it is off.
        val tddFactor = tddAdaptationFactor(controlTargetMmol, now)
        val nominalUhr = profile.getBasal() * tddFactor
        val nominalMuMin = nominalUhr * 1000.0 / 60.0
        val isfMgdl = profile.getProfileIsfMgdl()
        val icGPerU = profile.getIc()
        val maxBasalUhr = min(preferences.get(DoubleKey.ApsMaxBasal), MAX_BASAL_ABS_CAP)

        // CamAPS's own model: 8 submodels with the decoded two-compartment gut, the bioavailability
        // state and the EGP form of SubModel1::EndoBalance. Rebuilt each tick, so no filter state can
        // silently persist. See report/camaps-model-spec.md §23.
        val isfMmol = isfMgdl / MGDL_PER_MMOL

        val est = estimateState(weightKg, isfMmol, nominalUhr, now)
        val (rolloutModel, rolloutState) = est.best()              // the winning submodel
        // §5 MPC::MaximumPersonalRange -- the controller's own ceiling. Decoded, not fitted; see
        // CamapsMpc.maximumPersonalRange. Needs TDD, the 24h MEAN basal and the CURRENT block, which
        // differ on any profile with real dawn variation, so all three are passed rather than folded.
        val tddU = (tddCalculator.calculateDaily(-24, 0)?.let { if (it.totalAmount > 0.0) it.totalAmount else it.basalAmount + it.bolusAmount }
            ?: (meanProfileBasalUhr(profile) * 24.0)) * tddFactor   // no history yet: basal-only fallback
        val maxRateUhr = CamapsMpc.maximumPersonalRange(
            cgmMmol = glucoseStatus.glucose / MGDL_PER_MMOL,
            tddU = tddU,
            meanBasal = meanProfileBasalUhr(profile) * tddFactor,
            basalNow = nominalUhr
        )
        // §5 MPC::ModifyRateGlucoseRate keys on GetSlope over two windows, taking the more NEGATIVE.
        // shortAvgDelta/longAvgDelta are the nearest equivalents AAPS already computes; both are
        // mg/dL per 5 min, so x12 for per-hour and /18 for mmol/L.
        val slopeMmolPerH = min(glucoseStatus.shortAvgDelta, glucoseStatus.longAvgDelta) *
            12.0 / MGDL_PER_MMOL
        val decision = CamapsMpc(
            rolloutModel, targetMmol = controlTargetMmol,
            nominalBasalMuPerMin = nominalMuMin,
            maxBasalMuPerMin = maxBasalUhr * 1000.0 / 60.0,
            maxRateMuPerMin = maxRateUhr * 1000.0 / 60.0,
            observedSlopeMmolPerH = slopeMmolPerH,
            // §6.5: the gap between the two most recent CGM samples
            cgmGapMin = cgmGapMinutes(now),
            smoothedBasalMuPerMin = meanProfileBasalUhr(profile) * 1000.0 / 60.0,
            // §6.6 ModifyExercise: suspend entirely at or below 8.0 mmol/L during or around exercise.
            exercising = exercisingAt(now),
            // §5 ModifyRateGlucoseLevel relaxes the suspend threshold by 0.2 mmol/L within an hour of a
            // meal (GetMeal's window is 60 min), on the reasoning that carbs are on the way.
            mealWithinLastHour = persistenceLayer
                .getCarbsFromTimeToTimeExpanded(now - 3_600_000L, now, true).any { it.amount > 0.0 }
        ).decide(rolloutState)

        var rateUhr = max(0.0, min(maxBasalUhr, round(decision.basalUPerHr * 100.0) / 100.0))
        val rawCgmMmol = glucoseStatus.glucose / MGDL_PER_MMOL
        if (rawCgmMmol <= RAW_HYPO_SUSPEND_MMOL) rateUhr = 0.0    // backstop on the UNSMOOTHED sensor value

        // DISPLAY ONLY — AAPS's predictive alarms key off eventualBG, and the model rollout is known to be
        // unreliable in both directions, so the mass-balance identity is reported instead. It touches
        // nothing in the control path above; this controller does not consume IOB or COB.
        val iobNow = iobCobCalculator.calculateIobFromBolus().iob + iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().basaliob
        val cobG = iobCobCalculator.getCobInfo("CamapsEventual").displayCob ?: 0.0
        val eventualDisplay = (rawCgmMmol - iobNow * isfMmol + (if (icGPerU > 0) cobG * isfMmol / icGPerU else 0.0))
            .coerceIn(1.5, 40.0)

        val reasonStr = "${decision.reason} | est.G=%.1f | display eventual %.1f (IOB %.1f, COB %.0f)"
            .format(est.glucoseMmol(), eventualDisplay, iobNow, cobG)
        val rt = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = false,
            timestamp = now,
            bg = glucoseStatus.glucose,
            targetBG = controlTargetMmol * MGDL_PER_MMOL,
            eventualBG = eventualDisplay * MGDL_PER_MMOL,
            reason = StringBuilder(reasonStr),
            duration = TBR_DURATION_MIN,
            rate = rateUhr
        )
        val result = apsResultProvider.get().with(rt)
        result.glucoseStatus = glucoseStatus
        lastAPSResult = result
        lastAPSRun = now
        rxBus.send(EventAPSCalculationFinished())
        aapsLogger.debug(LTag.APS, "CamAPS-replica -> $rateUhr U/hr / $TBR_DURATION_MIN min | $reasonStr")
    }

    /**
     * §6.6's exercise window, as far as AAPS can know it.
     *
     * The binary tests `GetExercise(now, …, exerciseRateModifyBackPeriod)` OR
     * `GetExerciseEarly(now + exerciseRateModifyForwardPeriod, …)`. Both periods are runtime values held
     * in the patient record and were never recovered from the image, so neither is transcribed here.
     *
     * What is knowable: AAPS records exercise as a temporary target with reason ACTIVITY, so "exercising
     * now" is that target being active. The FORWARD term is not implementable at all — AAPS has no notion
     * of exercise that has not started — so this replica can only ever be less conservative than the real
     * controller around the start of a session, never more. [EXERCISE_LOOKBACK_MIN] is our own choice, not
     * a decoded constant: a session that has just ended still has insulin-sensitising effect, and 30 min
     * is short enough not to strand the loop at profile basal for hours after a walk.
     */
    private fun exercisingAt(now: Long): Boolean =
        persistenceLayer.getTemporaryTargetActiveAt(now)?.reason == TT.Reason.ACTIVITY ||
            persistenceLayer.getTemporaryTargetActiveAt(now - EXERCISE_LOOKBACK_MIN * 60_000L)
                ?.reason == TT.Reason.ACTIVITY

    /**
     * Replay the trailing [WINDOW_H] hours through CamAPS's own estimator. Stateless: rebuilt every tick,
     * so a bad filter state cannot persist across ticks.
     *
     * [CamapsEstimator] is the decoded structure — 8 submodels differing in the two gut time constants
     * (`tMaxG1s`/`tMaxG2s`) and an insulin-sensitivity multiplier (`multWktInsIni`), initial covariance
     * scaled by `multWini`, mode transitions on `halfTimeTran`, a submodel prior selected by the meal size
     * class (carbohydrate against body weight), and bioavailability as an estimated state clamped to
     * `fLimits`.
     *
     * Validated on 30 days of this patient's own data, forecast RMSE against actual future CGM
     * (hovorka-mpc/CamapsRealFit.kt), against what the loop runs today:
     * ```
     *   Hovorka + IMM bank   30m 1.812   60m 3.022   120m 4.421
     *   Hovorka + single EKF 30m 1.637   60m 2.791   120m 4.453
     *   CamAPS estimator     30m 1.280   60m 2.129   120m 3.422     <- 22%/24%/23% better
     * ```
     * An earlier one-compartment reduction of the same glucose equation was 44-87% WORSE than Hovorka at
     * 120 minutes. The difference is the two-compartment gut: `tMaxG1s` into `tMaxG2s` (21.88 into 140 for
     * submodel 0) is a fast fill and a long slow release, which collapsing to a single time constant
     * cannot represent.
     */
    private fun estimateState(weightKg: Double, isfMmol: Double, nominalUhr: Double,
                              now: Long): CamapsEstimator {
        val start = now - WINDOW_H * 3_600_000L
        val bg = persistenceLayer.getBgReadingsDataFromTimeToTime(start, now, true).sortedBy { it.timestamp }
        val boluses = persistenceLayer.getBolusesFromTimeToTime(start, now, true)
        val carbs = persistenceLayer.getCarbsFromTimeToTimeExpanded(start, now, true)
        val tbrs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(start, now, true)
        val profile = profileFunction.getProfile()!!
        val est = CamapsEstimator(weightKg, isfMmol, nominalUhr,
            egpHalfMuPerL = EGP_HALF_MU_PER_L, qFlux = Q_FLUX, qBio = Q_BIO)
        fun minOfTs(ts: Long) = ((ts - start) / 60000L).toInt()
        val bolusAt = HashMap<Int, Double>(); boluses.forEach { bolusAt.merge(minOfTs(it.timestamp), it.amount, Double::plus) }
        val carbAt = HashMap<Int, Double>(); carbs.forEach { carbAt.merge(minOfTs(it.timestamp), it.amount, Double::plus) }
        fun basalUhrAt(ts: Long): Double {
            val tb = tbrs.lastOrNull { it.timestamp <= ts && ts < it.timestamp + it.duration }
            val base = profile.getBasal(ts)
            return when { tb == null -> base; tb.isAbsolute -> tb.rate; else -> base * tb.rate / 100.0 }
        }
        var bgIdx = 0
        for (m in 0 until ((now - start) / 60000L).toInt()) {
            carbAt[m]?.let { est.meal(it) }
            bolusAt[m]?.let { est.bolus(it) }
            est.predict(basalUhrAt(start + m * 60000L) * 1000.0 / 60.0, 1.0)
            while (bgIdx < bg.size && minOfTs(bg[bgIdx].timestamp) <= m) { est.update(bg[bgIdx].value / MGDL_PER_MMOL); bgIdx++ }
        }
        return est
    }

    companion object {
        const val MGDL_PER_MMOL = 18.0
        const val WINDOW_H = 6L

        /**
         * Look-back for §6.6's exercise window, minutes. OURS, not the binary's — see [exercisingAt].
         * `exerciseRateModifyBackPeriod` (0x8d620) is a runtime value and was not recovered.
         */
        const val EXERCISE_LOOKBACK_MIN = 30L
        const val TBR_DURATION_MIN = 30
        const val TMAXG_MIN = 90.0
        const val RAW_HYPO_SUSPEND_MMOL = 3.9
        const val MAX_BASAL_ABS_CAP = 5.0

        /**
         * The three parameters of [CamapsEstimator] whose values the binary does not expose, fitted
         * against 30 days of this patient's own data on forecast RMSE vs actual future CGM
         * (hovorka-mpc/CamapsRealFit.kt), NOT against the binary's commanded rates:
         * ```
         *   egpHalf   30m     60m    120m
         *      80   1.349   2.251   3.620
         *     200   1.296   2.158   3.466
         *     400   1.280   2.129   3.422     <- chosen; past here the gain is under 1%
         *     800   1.272   2.115   3.401
         * ```
         * `egpHalf` is `SubModel1::EndoBalance`'s `this+0x234`, a per-submodel field in the binary, so
         * fitting it per patient is what CamAPS itself does with it. At 400 mU/L the exponential is nearly
         * flat over the physiological insulin range, i.e. this patient's data want weak counter-regulation.
         */
        /**
         * `EndoBalance`'s `this+0x234`: the insulin concentration in mU/L that halves endogenous glucose
         * production. This is the replica's ONLY brake on a fall — the decoded plant keeps `F01` constant
         * and has no renal clearance (§2), so nothing else in it is glucose-dependent.
         *
         * Was 400, fitted on 120-min forecast RMSE, at which the exponential is flat across any insulin a
         * person actually reaches: halving EGP would need +400 mU/L over basal. Its consequence showed up
         * as post-meal forecasts running to 0.00 mmol/L, which then dominated the cost and made the replica
         * suspend where the real controller delivers basal.
         *
         * Re-fitted against the binary over the 826-point reference, at the shipped cost weights:
         * ```
         *   egpHalf   level   trend  low+fall  post-meal  recovery   unsafe
         *     400     0.198   0.168     0.163      0.418     0.273        0
         *     200     0.200   0.170     0.162      0.388     0.270        0
         *     100     0.203   0.170     0.154      0.342     0.263        0
         *      50     0.203   0.158     0.144      0.295     0.258        0    <- shipped
         *      25     0.196   0.174     0.126      0.288     0.271        2
         * ```
         * 50 is the best point with no safety cell and cuts the post-meal arm 29%. It costs 1.1% of 30-min
         * and 2.6% of 120-min forecast accuracy on this patient's own 30 days (1.477 -> 1.494,
         * 5.568 -> 5.711) — a fraction of what it buys.
         */
        const val EGP_HALF_MU_PER_L = 50.0

        /**
         * Process noise on the unmodelled-flux state — the parameter that decides how fast the filter
         * re-learns after glucose stops moving, and therefore the controller's whole trend behaviour.
         *
         * Re-fitted after two corrections. (1) The covariance update in [CamapsEstimator] was wrong
         * (`P -= K (h S) K^T` instead of `P -= K S K^T`), which left P asymmetric and reduced in one
         * column only; while that stood, sweeping this constant barely changed anything and the earlier
         * value was chosen against a filter that could not use it. (2) The `recovery` probe family did
         * not exist, so nothing measured a time constant — 45 minutes of falling glucose followed by a
         * flat hold, which the binary recovers from inside 30 minutes.
         *
         * With both fixed, this constant is the lever. Over the 826-point reference:
         * ```
         *   qFlux   trend  lowfall   meal  recovery   unsafe
         *   1e-3    0.285    0.339  0.351     0.647        3
         *   1e-2    0.226    0.348  0.412     0.418        1
         *   3e-2    0.247    0.348  0.444     0.331        0
         *   1e-1    0.250    0.348  0.459     0.313        0
         * ```
         * 3e-2 is chosen: it removes the last cell where the real controller suspends and this one does
         * not, and cuts recovery error 21%. It costs 1.7% of 30-min and 3.6% of 120-min forecast accuracy
         * on 30 days of this user's own CGM (1.452 -> 1.477, 5.372 -> 5.568). Closing a suspend gap is
         * worth more than 1.7% of forecast RMSE.
         */
        const val Q_FLUX = 3e-2

        /** Process noise on the bioavailability state. Fitted as above; neutral against 0 once flux is on. */
        const val Q_BIO = 1e-5
    }
}

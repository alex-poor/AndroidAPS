package app.aaps.plugins.aps.camaps

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
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
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.target
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
    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {}

    /**
     * Mean of the 48 half-hourly basal rates, i.e. the same array `MPC::MaximumPersonalRange` averages
     * (`this+0x18`, 48 floats, divided by 48.0). Sampled on the half hour from the profile rather than
     * read from a stored array, which is equivalent for a step profile.
     */
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
        val nominalUhr = profile.getBasal()                       // §6 TDD adaptation not implemented
        val nominalMuMin = nominalUhr * 1000.0 / 60.0
        val isfMgdl = profile.getProfileIsfMgdl()
        val icGPerU = profile.getIc()
        val maxBasalUhr = min(preferences.get(DoubleKey.ApsMaxBasal), MAX_BASAL_ABS_CAP)

        // model identified from the titrated profile, anchored so nominal basal holds the profile target
        val key = "%.1f/%.1f/%.2f/%.4f/%.2f".format(weightKg, isfMgdl, icGPerU, nominalUhr, profileTargetMmol)
        if (key != cachedKey) {
            cachedModel = HovorkaModel(HovorkaParams.personalize(
                weightKg, isfMgdl, icGPerU, nominalUhr, profileTargetMmol, tMaxGmin = TMAXG_MIN))
            cachedKey = key
        }
        val model = cachedModel ?: return

        val est = estimateState(model, nominalMuMin, now)
        val rolloutModel = est.rolloutModel() ?: model            // IMM: roll out with the winning submodel
        // §5 MPC::MaximumPersonalRange -- the controller's own ceiling. Decoded, not fitted; see
        // CamapsMpc.maximumPersonalRange. Needs TDD, the 24h MEAN basal and the CURRENT block, which
        // differ on any profile with real dawn variation, so all three are passed rather than folded.
        val tddU = tddCalculator.calculateDaily(-24, 0)?.let { if (it.totalAmount > 0.0) it.totalAmount else it.basalAmount + it.bolusAmount }
            ?: (meanProfileBasalUhr(profile) * 24.0)          // no history yet: basal-only fallback
        val maxRateUhr = CamapsMpc.maximumPersonalRange(
            cgmMmol = glucoseStatus.glucose / MGDL_PER_MMOL,
            tddU = tddU,
            meanBasal = meanProfileBasalUhr(profile),
            basalNow = profile.getBasal(now)
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
            // §5 ModifyRateGlucoseLevel relaxes the suspend threshold by 0.2 mmol/L within an hour of a
            // meal (GetMeal's window is 60 min), on the reasoning that carbs are on the way.
            mealWithinLastHour = persistenceLayer
                .getCarbsFromTimeToTimeExpanded(now - 3_600_000L, now, true).any { it.amount > 0.0 }
        ).decide(est.x)

        var rateUhr = max(0.0, min(maxBasalUhr, round(decision.basalUPerHr * 100.0) / 100.0))
        val rawCgmMmol = glucoseStatus.glucose / MGDL_PER_MMOL
        if (rawCgmMmol <= RAW_HYPO_SUSPEND_MMOL) rateUhr = 0.0    // backstop on the UNSMOOTHED sensor value

        // DISPLAY ONLY — AAPS's predictive alarms key off eventualBG, and the model rollout is known to be
        // unreliable in both directions, so the mass-balance identity is reported instead. It touches
        // nothing in the control path above; this controller does not consume IOB or COB.
        val isfMmol = isfMgdl / MGDL_PER_MMOL
        val iobNow = iobCobCalculator.calculateIobFromBolus().iob + iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().basaliob
        val cobG = iobCobCalculator.getCobInfo("CamapsEventual").displayCob ?: 0.0
        val eventualDisplay = (rawCgmMmol - iobNow * isfMmol + (if (icGPerU > 0) cobG * isfMmol / icGPerU else 0.0))
            .coerceIn(1.5, 40.0)

        val reasonStr = "${decision.reason} | est.G=%.1f | display eventual %.1f (IOB %.1f, COB %.0f)"
            .format(model.glucoseMmol(est.x), eventualDisplay, iobNow, cobG)
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
     * Replay the trailing [WINDOW_H] hours. Stateless: rebuilt every tick.
     *
     * Uses an EKF over [EgpFluxModel] — Hovorka plus one unmodelled-glucose-flux state — rather than the
     * IMM bank, because without that state the forecast discards almost all of an observed trend and the
     * controller cannot predict the hypo that should make it suspend. Measured: glucose 6.5 falling
     * 3.0 mmol/L/h forecasts to 6.09 at +60 min with the bank, and to 4.58 with the flux state.
     *
     * Scored against 627 sanity-verified points from the real binary, in units of profile basal:
     * ```
     *   config                          level  trend  lowfall  ceiling   points the real one
     *                                                                    suspends and we do not
     *   IMM bank                        0.160  0.490    0.190    0.273        11
     *   single EKF, flux FROZEN (q=0)   0.160  0.490    0.190    0.273        11   <- control
     *   + flux q = 0.03                 0.162  0.412    0.162    0.213         2
     *   + flux q = 0.10                 0.198  0.363    0.215    0.148         0
     * ```
     * The frozen control is bit-identical to the IMM bank, so the whole difference is the flux state and
     * not the estimator swap. The probes contain no meals, which is why the bank's carb-absorption
     * submodels contribute nothing to them — on real data with meals they may well, so this is a
     * deliberate trade that has NOT been validated on real history yet.
     *
     * [DIST_PROCESS_VAR] is set to 0.10: it removes all 11 points where the real controller suspends and
     * this one does not, for 0.04 x basal of level accuracy. That asymmetry is the right way round, but
     * it is a judgement made against synthetic probes and should be re-derived from the user's own
     * history before this is trusted.
     */
    private fun estimateState(model: HovorkaModel, nominalMuMin: Double, now: Long): GlucoseEstimator {
        val start = now - WINDOW_H * 3_600_000L
        val bg = persistenceLayer.getBgReadingsDataFromTimeToTime(start, now, true).sortedBy { it.timestamp }
        val boluses = persistenceLayer.getBolusesFromTimeToTime(start, now, true)
        val carbs = persistenceLayer.getCarbsFromTimeToTimeExpanded(start, now, true)
        val tbrs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(start, now, true)
        val profile = profileFunction.getProfile()!!
        val fluxModel = EgpFluxModel(model.p)
        val est: GlucoseEstimator = HovorkaEkf(
            fluxModel, fluxModel.steadyState(nominalMuMin),
            unclampedState = EgpFluxModel.DIST,
            distInitVar = DIST_INIT_VAR, distProcessNoiseVar = DIST_PROCESS_VAR)
        fun minOf(ts: Long) = ((ts - start) / 60000L).toInt()
        val bolusAt = HashMap<Int, Double>(); boluses.forEach { bolusAt.merge(minOf(it.timestamp), it.amount, Double::plus) }
        val carbAt = HashMap<Int, Double>(); carbs.forEach { carbAt.merge(minOf(it.timestamp), it.amount, Double::plus) }
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
            while (bgIdx < bg.size && minOf(bg[bgIdx].timestamp) <= m) { est.update(bg[bgIdx].value / MGDL_PER_MMOL); bgIdx++ }
        }
        return est
    }

    companion object {
        const val MGDL_PER_MMOL = 18.0
        const val WINDOW_H = 6L
        const val TBR_DURATION_MIN = 30
        const val TMAXG_MIN = 90.0
        const val RAW_HYPO_SUSPEND_MMOL = 3.9
        const val MAX_BASAL_ABS_CAP = 5.0

        /** Prior variance on the unmodelled-flux state: sd 0.1 mmol/min. */
        const val DIST_INIT_VAR = 0.01

        /** Random-walk process variance for the flux state. See [estimateState] for the sweep. */
        const val DIST_PROCESS_VAR = 0.10
    }
}

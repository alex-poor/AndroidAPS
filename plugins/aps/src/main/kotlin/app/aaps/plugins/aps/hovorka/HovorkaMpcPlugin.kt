package app.aaps.plugins.aps.hovorka

import android.content.Context
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.APS
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.plugins.aps.openAPSSMB.GlucoseStatusCalculatorSMB
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.target
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.plugins.aps.R
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Hovorka nonlinear-MPC dosing plugin (EXPERIMENTAL, in-silico validated only).
 *
 * Wraps the control laws in report/algorithm-spec.md implemented over a
 * published Hovorka model: EKF state estimate from CGM history, then a receding-horizon MPC tracking
 * the decoded exponential reference trajectory. Basal-modulating (TBR) controller.
 *
 * Statelessness by design: re-running from history
 * each tick: invoke() replays the last [WINDOW_H] hours of BG/insulin/carb history through the EKF to
 * estimate the current physiological state, then decides. No persisted filter state to corrupt.
 *
 * SAFETY: in-silico validated (hovorka-mpc/, cohort mean TIR ~76%, no severe hypo) — NOT clinically
 * validated. TBR-only. Never enable by default. Constraints (maxBasal/maxIOB) still applied by Loop.
 */
@Singleton
class HovorkaMpcPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val rxBus: RxBus,
    private val profileFunction: ProfileFunction,
    private val glucoseStatusCalculatorSMB: GlucoseStatusCalculatorSMB,
    private val persistenceLayer: PersistenceLayer,
    private val constraintsChecker: ConstraintsChecker,
    private val iobCobCalculator: IobCobCalculator,
    private val dateUtil: DateUtil,
    private val preferences: Preferences,
    private val apsResultProvider: Provider<APSResult>
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.APS)
        .fragmentClass("app.aaps.plugins.aps.compose.AlgorithmFragment")
        .pluginName(R.string.hovorka_mpc_name)
        .shortName(R.string.hovorka_mpc_shortname)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN)
        .description(R.string.hovorka_mpc_description),
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
            key = "hovorka_mpc_settings"
            title = rh.gs(R.string.hovorka_mpc_name)
            initialExpandedChildrenCount = 0
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.HovorkaBodyWeight,
                    dialogMessage = R.string.hovorka_body_weight_summary, title = R.string.hovorka_body_weight_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.HovorkaTddAdaptation,
                    summary = R.string.hovorka_tdd_adaptation_summary, title = R.string.hovorka_tdd_adaptation_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.HovorkaImmBank,
                    summary = R.string.hovorka_imm_bank_summary, title = R.string.hovorka_imm_bank_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.HovorkaEnableSmb,
                    summary = R.string.hovorka_enable_smb_summary, title = R.string.hovorka_enable_smb_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.HovorkaMealDetection,
                    summary = R.string.hovorka_meal_detection_summary, title = R.string.hovorka_meal_detection_title
                )
            )
        }
    }

    override fun invoke(initiator: String, tempBasalFallback: Boolean) {
        aapsLogger.debug(LTag.APS, "HovorkaMPC invoke from $initiator")
        lastAPSResult = null
        if (!isEnabled()) return
        val profile = profileFunction.getProfile() ?: return
        val glucoseStatus = glucoseStatusCalculatorSMB.getGlucoseStatusData(false) ?: return

        val now = dateUtil.now()
        // The PROFILE target reflects the patient's true physiology (basal holds this at steady state),
        // so the model is always personalised/anchored to it. A temp target only shifts the CONTROL
        // setpoint (2b) — it must NOT distort model identification.
        val profileTargetMmol = profile.getTargetMgdl() / MGDL_PER_MMOL
        val tempTargetMgdl = persistenceLayer.getTemporaryTargetActiveAt(now)?.target()
        val controlTargetMmol = (tempTargetMgdl ?: profile.getTargetMgdl()) / MGDL_PER_MMOL
        val bodyWeightKg = preferences.get(DoubleKey.HovorkaBodyWeight)
        val basalUhr = profile.getBasal()
        // 2a: personalise the model from the user's titrated ISF (mg/dL/U) + IC (g/U), anchored to
        // their basal/profile-target — instead of population weight-only params. Cached (calibration heavy).
        val isfMgdl = profile.getProfileIsfMgdl()
        val icGPerU = profile.getIc()
        val model = personalizedModel(bodyWeightKg, isfMgdl, icGPerU, basalUhr, profileTargetMmol)
        val maxBasalUhr = constraintsChecker.getMaxBasalAllowed(profile).value()
        val maxBasalMuMin = maxBasalUhr * 1000.0 / 60.0
        // 2d: adapt the OPERATING basal (the MPC's nominal / floor centre) from recent daily outcomes if
        // enabled. The MODEL above stays anchored to the PROFILE basal — 2d moves only the operating point.
        val operatingBasalUhr = adaptedOperatingBasalUhr(profile, bodyWeightKg, profileTargetMmol, maxBasalUhr, now)
        val nominalBasalMuMin = operatingBasalUhr * 1000.0 / 60.0    // U/hr -> mU/min

        // --- replay the recent history through the EKF to estimate current state ---
        val ekf = try {
            estimateState(model, nominalBasalMuMin, now)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "HovorkaMPC state estimation failed", e)
            return
        }

        // 3b SMB gating. This plugin's SMB is controlled by its OWN pref (HovorkaEnableSmb, default OFF),
        // which is an explicit opt-in. We deliberately do NOT use constraintsChecker.isSMBModeEnabled() here:
        // that bundles the Objectives Objective-8 staged-unlock gate (an educational block, not a safety
        // mechanism) AND oref's own SMB settings (irrelevant to this APS but would otherwise gate it). We
        // keep the ONE genuine safety constraint from that bundle — closed-loop-allowed (no autonomous bolus
        // in open loop) — and preserve the real dosing limits below (per-tick maxIOB HEADROOM + pump/pref
        // maxBolus, so an SMB can never push IOB past maxIOB) and both hypo suspends inside the MPC.
        val smbAllowed = preferences.get(BooleanKey.HovorkaEnableSmb) &&
            constraintsChecker.isClosedLoopAllowed().value()
        val iobNow = iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile).iob
        val maxSmbU = if (smbAllowed) {
            val maxIob = constraintsChecker.getMaxIOBAllowed().value()
            val maxBolus = constraintsChecker.getMaxBolusAllowed().value()
            max(0.0, min(min(maxBolus, SMB_ABS_CAP_U), maxIob - iobNow))
        } else 0.0

        // 3a: if the estimator identifies a regime (IMM), roll the MPC out with that model
        // (ModelIMM1::PredictForOptimise); the single EKF returns null → the personalised model is used.
        val rolloutModel = ekf.rolloutModel() ?: model
        val mpc = HovorkaMpc(
            rolloutModel, targetMmol = controlTargetMmol,
            nominalBasalMuPerMin = nominalBasalMuMin, maxBasalMuPerMin = maxBasalMuMin,
            enableSmb = smbAllowed, maxSmbU = maxSmbU
        )
        val decision = mpc.decide(ekf.x)
        var rateUhr = max(0.0, min(maxBasalUhr, round(decision.basalUPerHr * 100.0) / 100.0))
        // SAFETY backstop on the RAW sensor value: the EKF est.G can lag ~1 mmol/L high on a fast
        // excursion (drop/rise), so gate the enacted rate on the latest CGM directly — at/below the
        // hypo-suspend threshold force 0 U/hr regardless of the model decision. Mirrors the est.G
        // hard suspend inside HovorkaMpc.decide(); this one uses the un-smoothed sensor value.
        val rawCgmMmol = glucoseStatus.glucose / MGDL_PER_MMOL
        val rawHypoSuspend = rawCgmMmol <= HYPO_SUSPEND_MMOL
        if (rawHypoSuspend) rateUhr = 0.0
        // 3b: the SMB inherits BOTH hypo backstops — the est.G suspend inside decide() (already zeroes
        // decision.smbU) AND this raw-CGM suspend. Round to 2 dp; a dropped bolus fails closed downstream.
        val smbU = if (rawHypoSuspend) 0.0 else round(decision.smbU * 100.0) / 100.0
        // Prototype #1: mass-balance FLOOR on the reported projection. The nonlinear model rollout
        // (decision.eventualMmol) can crater far below what insulin/carb mass balance allows — missing
        // counter-regulation and renal loss at the post-meal peak make a carb-MATCHED bolus mis-project a
        // deep hypo (observed eventualBG 1.2 on a normal dinner; verified across all calibrations in
        // hovorka-mpc/). Floor the reported value at the standard bolus-wizard identity
        //   eventual = currentBG − IOB·ISF + COB·(ISF/IC)
        // which reads ~current for a matched dose. This is DISPLAY + safety-reporting only — the TBR control
        // law is unchanged (TBR-only, near-minimal already, so the crater's basal effect was minor and safe).
        val isfMmol = isfMgdl / MGDL_PER_MMOL
        val cobG = iobCobCalculator.getCobInfo("HovorkaEventual").displayCob ?: 0.0
        val carbRiseMmol = if (icGPerU > 0.0) cobG * isfMmol / icGPerU else 0.0
        val eventualLinearMmol = (rawCgmMmol - iobNow * isfMmol + carbRiseMmol).coerceIn(EVENTUAL_MIN_MMOL, EVENTUAL_MAX_MMOL)
        val reportedEventualMmol = max(decision.eventualMmol, eventualLinearMmol)
        val mbNote = " | eventualMB=%.1f (IOB %.1f, COB %.0f)".format(eventualLinearMmol, iobNow, cobG)
        val ttNote = if (tempTargetMgdl != null) " | TT=%.0f mg/dL".format(tempTargetMgdl) else ""
        val hypoNote = if (rawHypoSuspend) " | CGM-HYPO-SUSPEND %.1f≤%.1f".format(rawCgmMmol, HYPO_SUSPEND_MMOL) else ""
        val reasonStr = "HovorkaMPC | est.G=%.1f mmol/L%s%s%s | %s".format(model.glucoseMmol(ekf.x), ttNote, hypoNote, mbNote, decision.reason)

        // Build an oref-shaped RT so AAPS can persist/display it (toDb requires algorithm SMB/AMA + RT).
        // SMB rides on RT.units (+ deliverAt) → DetermineBasalResult.smb → commandQueue bolus (BOLUS_SMB).
        val rt = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = false,
            timestamp = now,
            bg = glucoseStatus.glucose,
            targetBG = controlTargetMmol * MGDL_PER_MMOL,
            // eventualBG = the MPC's forward projection under its optimised plan, FLOORED at the mass-balance
            // identity (Prototype #1) so it can't report an unphysical post-meal crater.
            eventualBG = reportedEventualMmol * MGDL_PER_MMOL,
            reason = StringBuilder(reasonStr),
            duration = TBR_DURATION_MIN,
            rate = rateUhr,
            units = if (smbU > 0.0) smbU else null,
            deliverAt = if (smbU > 0.0) now else null
        )
        val result = apsResultProvider.get().with(rt)
        result.glucoseStatus = glucoseStatus
        lastAPSResult = result
        lastAPSRun = now
        rxBus.send(EventAPSCalculationFinished())
        aapsLogger.debug(LTag.APS, "HovorkaMPC -> $rateUhr U/hr / $TBR_DURATION_MIN min${if (smbU > 0.0) " + SMB ${smbU}U" else ""} | $reasonStr")
    }

    // Personalisation is expensive (many steady-state solves) but only depends on profile block values,
    // which change rarely — memoise on a rounded key so it recomputes only when they actually change.
    private var cachedModel: HovorkaModel? = null
    private var cachedKey: String = ""

    private fun personalizedModel(w: Double, isfMgdl: Double, icGPerU: Double, basalUhr: Double, targetMmol: Double): HovorkaModel {
        val key = "%.1f/%.1f/%.2f/%.4f/%.2f".format(w, isfMgdl, icGPerU, basalUhr, targetMmol)
        if (key != cachedKey || cachedModel == null) {
            cachedModel = HovorkaModel(HovorkaParams.personalize(w, isfMgdl, icGPerU, basalUhr, targetMmol))
            cachedKey = key
            aapsLogger.debug(LTag.APS, "HovorkaMPC personalised: W=$w ISF=$isfMgdl IC=$icGPerU basal=$basalUhr target=$targetMmol")
        }
        return cachedModel!!
    }

    // 2d adaptive-gain cache: the adapted operating basal is a DAILY quantity, so recompute once per day
    // (like the 2a calibration cache). Stateless across restarts — reconstructed from persisted history.
    private var adaptCacheDay = -1L
    private var adaptCacheBasalUhr = 0.0

    /**
     * 2d: adapt the operating basal from the last [ADAPT_DAYS] completed days of outcomes. Rebuilds a fresh
     * [TddAdapter] from the PROFILE basal and folds each past day (mean enacted basal + glucose summary) —
     * stateless (no new schema; survives restarts), bounded, and self-healing toward the profile if the
     * profile is later fixed. Returns the profile basal unchanged when the feature is off or history is thin.
     */
    private fun adaptedOperatingBasalUhr(profile: Profile, weightKg: Double, targetMmol: Double, maxBasalUhr: Double, now: Long): Double {
        val profileBasalUhr = profile.getBasal()
        if (!preferences.get(BooleanKey.HovorkaTddAdaptation)) return profileBasalUhr
        val dayKey = now / DAY_MS
        if (dayKey == adaptCacheDay) return adaptCacheBasalUhr
        val adapter = TddAdapter(weightKg, profileBasalUhr, targetMmol = targetMmol, maxBasalUhr = maxBasalUhr)
        var folded = 0
        for (d in ADAPT_DAYS downTo 1) {                          // oldest completed day first
            val dayStart = now - d * DAY_MS
            val dayEnd = dayStart + DAY_MS
            val bg = persistenceLayer.getBgReadingsDataFromTimeToTime(dayStart, dayEnd, true)
            if (bg.size < 96) continue                            // need ~8 h of CGM to trust the day
            val gsMmol = bg.map { it.value / MGDL_PER_MMOL }
            val meanG = gsMmol.average()
            val minG = gsMmol.min()
            val tbrFrac = gsMmol.count { it < 3.9 }.toDouble() / gsMmol.size
            aapsLogger.debug(LTag.APS, "HovorkaMPC 2d " + adapter.endOfDay(meanEnactedBasalUhr(profile, dayStart, dayEnd), meanG, tbrFrac, minG))
            folded++
        }
        adaptCacheBasalUhr = if (folded > 0) adapter.operatingBasalUhr else profileBasalUhr
        adaptCacheDay = dayKey
        aapsLogger.debug(LTag.APS, "HovorkaMPC 2d operating basal: profile=%.3f → adapted=%.3f U/hr (%d days)".format(profileBasalUhr, adaptCacheBasalUhr, folded))
        return adaptCacheBasalUhr
    }

    /** Time-weighted mean enacted basal (U/hr) over [start,end), honouring active temp basals. */
    private fun meanEnactedBasalUhr(profile: Profile, start: Long, end: Long): Double {
        val tbrs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(start - 3_600_000L, end, true)
        var sum = 0.0; var n = 0; var t = start
        while (t < end) {
            val tb = tbrs.lastOrNull { it.timestamp <= t && t < it.timestamp + it.duration }
            val base = profile.getBasal(t)
            sum += when { tb == null -> base; tb.isAbsolute -> tb.rate; else -> base * tb.rate / 100.0 }
            n++; t += 30 * 60_000L                                 // 30-min buckets
        }
        return if (n > 0) sum / n else profile.getBasal(start)
    }

    /**
     * Replay BG/insulin/carb history over WINDOW_H hours to estimate current Hovorka state.
     * 3a: the estimator is a single EKF by default, or the 8-submodel IMM bank when [BooleanKey.HovorkaImmBank]
     * is on. Both implement [GlucoseEstimator], so the replay below is identical either way. The IMM
     * identifies the patient's absorption regime (fast↔slow carbs) each tick; on its own that's ~parity with
     * the EKF on top of 2a (it earns its keep via meal detection + SMB, 3b), so it ships OFF by default.
     */
    private fun estimateState(model: HovorkaModel, nominalBasalMuMin: Double, now: Long): GlucoseEstimator {
        val start = now - WINDOW_H * 3_600_000L
        val bg = persistenceLayer.getBgReadingsDataFromTimeToTime(start, now, true).sortedBy { it.timestamp }
        val boluses = persistenceLayer.getBolusesFromTimeToTime(start, now, true)
        val carbs = persistenceLayer.getCarbsFromTimeToTimeExpanded(start, now, true)
        val tbrs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(start, now, true)

        val profile = profileFunction.getProfile()!!
        val ekf: GlucoseEstimator =
            if (preferences.get(BooleanKey.HovorkaImmBank)) HovorkaImmBank(model.p, nominalBasalMuMin)
            else HovorkaEkf(model, model.steadyState(nominalBasalMuMin))
        // Bayesian unannounced-meal detector (off by default): reconstructed each tick from history like the
        // rest of the estimator (stateless). Infers carbs from the innovation and injects them so SMB/MPC
        // react; all its output still rides the SMB gates + both hypo suspends. Most useful with SMB enabled.
        val detector = if (preferences.get(BooleanKey.HovorkaMealDetection))
            HovorkaMealDetector(preferences.get(DoubleKey.HovorkaBodyWeight)) else null
        val tz = java.util.TimeZone.getDefault()
        fun minuteOfDay(ts: Long): Int = (((ts + tz.getOffset(ts)) / 60000L) % 1440L).toInt()
        var lastCarbMin = -100000
        // index events by minute offset from start
        fun minOf(ts: Long) = ((ts - start) / 60000L).toInt()
        val bolusAt = HashMap<Int, Double>()
        boluses.forEach { bolusAt.merge(minOf(it.timestamp), it.amount, Double::plus) }
        val carbAt = HashMap<Int, Double>()
        carbs.forEach { carbAt.merge(minOf(it.timestamp), it.amount, Double::plus) }
        // resolve absolute basal (U/hr) at an absolute time, honouring active temp basals
        fun basalUhrAt(ts: Long): Double {
            val tb = tbrs.lastOrNull { it.timestamp <= ts && ts < it.timestamp + it.duration }
            val base = profile.getBasal(ts)
            return when {
                tb == null -> base
                tb.isAbsolute -> tb.rate
                else -> base * tb.rate / 100.0
            }
        }
        val totalMin = ((now - start) / 60000L).toInt()
        var bgIdx = 0
        for (m in 0 until totalMin) {
            carbAt[m]?.let { ekf.meal(it); lastCarbMin = m }              // announced carbs → suppress detector
            bolusAt[m]?.let { ekf.bolus(it) }                             // U bolus -> SC insulin comp
            val uMuMin = basalUhrAt(start + m * 60000L) * 1000.0 / 60.0
            ekf.predict(uMuMin, 1.0)
            // apply any CGM reading landing in this minute
            while (bgIdx < bg.size && minOf(bg[bgIdx].timestamp) <= m) {
                val gMeas = bg[bgIdx].value / MGDL_PER_MMOL
                val priorG = ekf.glucoseMmol()                           // one-step prediction, pre-update
                ekf.update(gMeas)
                if (detector != null) {                                  // recover unannounced carbs from the innovation
                    val announcedActive = (m - lastCarbMin) in 0..90
                    val dCho = detector.update(gMeas - priorG, gMeas, minuteOfDay(bg[bgIdx].timestamp), announcedActive)
                    if (dCho > 0.0) ekf.meal(dCho)
                }
                bgIdx++
            }
        }
        return ekf
    }

    companion object {
        const val MGDL_PER_MMOL = 18.0
        const val WINDOW_H = 6L
        const val TBR_DURATION_MIN = 30
        const val DAY_MS = 86_400_000L
        const val ADAPT_DAYS = 7                 // 2d: trailing window folded into the operating-basal gain
        const val HYPO_SUSPEND_MMOL = 3.9        // at/below this RAW CGM value, force a hard 0 U/hr suspend
        const val SMB_ABS_CAP_U = 1.5            // 3b: absolute per-tick microbolus cap (further bounded by maxIOB/maxBolus)
        const val EVENTUAL_MIN_MMOL = 1.5        // #1: sanity clamp on the mass-balance eventual (bad COB/IOB)
        const val EVENTUAL_MAX_MMOL = 30.0
    }
}

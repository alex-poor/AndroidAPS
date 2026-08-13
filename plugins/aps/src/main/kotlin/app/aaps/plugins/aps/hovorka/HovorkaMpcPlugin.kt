package app.aaps.plugins.aps.hovorka

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
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
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAPSCalculationFinished
import app.aaps.core.interfaces.rx.events.EventNewNotification
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.data.time.T
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
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
import kotlin.math.roundToInt

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
            // SAFETY LIMITS. These two keys belong to the OpenAPS-SMB plugin, which is disabled whenever
            // HovorkaMPC is the active APS — so AAPS never renders its preference screen and the values were
            // unreachable in the UI, while HovorkaMPC still enforced them. Surface them here: they are the
            // ceiling on everything this plugin can do, so they must be visible and editable where the
            // algorithm that obeys them lives.
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.ApsMaxBasal,
                    dialogMessage = R.string.hovorka_max_basal_summary, title = R.string.hovorka_max_basal_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.ApsSmbMaxIob,
                    dialogMessage = R.string.hovorka_max_iob_summary, title = R.string.hovorka_max_iob_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.HovorkaBodyWeight,
                    dialogMessage = R.string.hovorka_body_weight_summary, title = R.string.hovorka_body_weight_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.HovorkaCarbAbsorptionMin,
                    dialogMessage = R.string.hovorka_carb_absorption_summary, title = R.string.hovorka_carb_absorption_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.HovorkaCorrectionTauMin,
                    dialogMessage = R.string.hovorka_correction_tau_summary, title = R.string.hovorka_correction_tau_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.HovorkaCarbAbsorptionByTod,
                    summary = R.string.hovorka_carb_absorption_by_tod_summary, title = R.string.hovorka_carb_absorption_by_tod_title
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
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.HovorkaParamId,
                    summary = R.string.hovorka_param_id_summary, title = R.string.hovorka_param_id_title
                )
            )
            addPreference(
                AdaptiveSwitchPreference(
                    ctx = context, booleanKey = BooleanKey.HovorkaSiteChangeGuard,
                    summary = R.string.hovorka_site_change_guard_summary, title = R.string.hovorka_site_change_guard_title
                )
            )
            addPreference(
                AdaptiveDoublePreference(
                    ctx = context, doubleKey = DoubleKey.HovorkaSiteChangeGuardH,
                    dialogMessage = R.string.hovorka_site_change_guard_h_summary, title = R.string.hovorka_site_change_guard_h_title
                )
            )
            // Read-only transparency block: the last re-tune's timestamp, whether it changed anything, and what.
            addPreference(
                Preference(context).apply {
                    title = rh.gs(R.string.hovorka_param_id_status_title)
                    summary = preferences.get(StringKey.HovorkaParamIdStatus).ifEmpty { rh.gs(R.string.hovorka_param_id_status_none) }
                    isSelectable = false
                    isPersistent = false
                }
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
        // carb absorption time-to-peak (min) is a physiological property the profile ISF/IC do NOT capture;
        // default 40 (fast), raise for slow (fat/protein) meals so the controller expects a longer rise.
        // TIME-OF-DAY carb absorption (2026-07-25). Carb absorption is not a constant — it varies across the
        // day, and a single value cannot fit all three meals. Measured over this user's trailing 21 days
        // (54 meals, time from carb entry to the post-dip glucose peak):
        //     06-11h  n=12  peak @128 min   -> tMaxG ~ 90
        //     11-16h  n=17  peak @188 min   -> tMaxG ~120
        //     16-24h  n=21  peak @261 min   -> tMaxG ~150
        // Dinner absorbs more than twice as slowly as breakfast. With a single tMaxG=90 the controller expects
        // dinner carbs ~2h before they arrive, so it suspends through the early insulin-driven DIP (73% of
        // dinners, 16/22, dip below their starting glucose) and is then caught flat-footed by the late wave
        // (2026-07-25: 9.4 -> 6.2 at +60min -> 12.5 at +180min on a correctly-dosed 110g meal).
        // The evening multiplier is applied to whatever the user's base pref is, so the pref still works as
        // the master control; setting HovorkaCarbAbsorptionByTod=false restores the old single-value behaviour.
        val carbAbsorptionBase = preferences.get(DoubleKey.HovorkaCarbAbsorptionMin)
        val carbAbsorptionMin =
            if (preferences.get(BooleanKey.HovorkaCarbAbsorptionByTod))
                carbAbsorptionBase * carbAbsorptionTodFactor(dateUtil.now())
            else carbAbsorptionBase
        val baseModel = personalizedModel(bodyWeightKg, isfMgdl, icGPerU, basalUhr, profileTargetMmol, carbAbsorptionMin)
        // 4: re-identify structural params (insulin sensitivity / EGP / carb-absorption) from recent history —
        // a daily background re-tune, stateless from the personalise() prior so the model is never more than one
        // bounded step from the profile. OFF by default; surfaces its result to the UI (reason + settings + a
        // notification on any change). The basal the user actually takes is unaffected — this only re-tunes the
        // MODEL the MPC rolls out on.
        val model = reIdentifiedModel(baseModel, bodyWeightKg, isfMgdl, now)
        // maxBasal: getMaxBasalAllowed() is supplied by the OpenAPS-SMB plugin, which is DISABLED whenever
        // HovorkaMPC is the active APS — so the constraint returns unbounded and the user's setting was
        // silently dead here (the same defect already fixed for maxIOB below). Worse, because that plugin is
        // disabled AAPS never RENDERS its preference screen, so neither value was reachable in the UI at all:
        // on 2026-07-26 the user went looking for "maximum basal" and found only "minimal request change".
        // Read the pref directly, and take whichever bound is tighter if a real constraint does exist.
        val maxBasalUhr = min(
            constraintsChecker.getMaxBasalAllowed(profile).value(),
            preferences.get(DoubleKey.ApsMaxBasal)
        )
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
        //
        // 2026-07-22 SAFETY REWRITE. The original gate was `enableSmb && maxSmbU > 0 && !hypoSuspended &&
        // g0 > target` — i.e. a microbolus whenever glucose exceeded 7.0 and the 60-min rollout stayed above
        // 8.5. That has no notion of carbs, of time since a meal, or of whether glucose is even RISING, and
        // the smbFraction=0.5 "converge over ticks" design re-fires against an unchanged prediction when
        // glucose is FLAT. Overnight 2026-07-21/22 it delivered 13 SMBs = 2.1U at BG 7.1-8.7, fasting, and
        // drove 8.2 -> 3.3 mmol/L (2.1U x ISF 2.5 = 5.3 predicted vs 4.9 observed drop: the SMBs ARE the hypo).
        // Bolus authority is irreversible and must be reserved for genuine meal excursions; a flat 8.4 at 2am
        // is basal's job. Four structural guards, ALL must hold:
        //   (a) real high   - glucose meaningfully above target (in HovorkaMpc: g0 > target + smbMinHighMmol)
        //   (b) fed state   - carbs on board, or a meal within SMB_MEAL_WINDOW_MIN (no fasting SMB)
        //   (c) rising      - never bolus a flat or falling glucose
        //   (d) no stacking - cumulative SMB over SMB_STACK_WINDOW_MIN capped at SMB_STACK_CAP_U
        // --- POST-SITE-CHANGE GUARD (2026-08-12): is a fresh cannula still opening up? ---
        // Keyed on CANNULA_CHANGE specifically. The user registers changes through the Prime/Fill
        // dialog, which writes CANNULA_CHANGE and INSULIN_CHANGE one second apart (verified in the
        // DB: every change carries both, with the site in the note — "cannula: right flank"). Keying
        // on CANNULA_CHANGE therefore catches that workflow, while a tubing/reservoir-only fill —
        // which writes INSULIN_CHANGE ALONE and does not disturb absorption — correctly does NOT arm
        // the guard. Do not "helpfully" widen this to INSULIN_CHANGE.
        val siteGuardOn = preferences.get(BooleanKey.HovorkaSiteChangeGuard)
        val lastSiteChangeMs = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)?.timestamp
        val siteAgeH = lastSiteChangeMs?.let { (now - it) / T.hours(1).msecs().toDouble() }
        val siteGuardWindowH = preferences.get(DoubleKey.HovorkaSiteChangeGuardH)
        // Fails OPEN: with no CANNULA_CHANGE ever recorded, siteAgeH is null and the guard stays off
        // rather than clamping forever on a database that simply has no site history.
        val siteGuardActive = siteGuardOn && siteAgeH != null && siteAgeH < siteGuardWindowH

        val cobNowG = iobCobCalculator.getCobInfo("HovorkaSmbGate").displayCob ?: 0.0
        val recentMealMs = now - T.mins(SMB_MEAL_WINDOW_MIN).msecs()
        val hasRecentMeal = persistenceLayer.getCarbsFromTimeToTimeExpanded(recentMealMs, now, false).any { it.amount > 0.0 }
        // (e) POST-HYPO LOCKOUT. 2026-07-21 showed the fed-state test is not enough on its own: a 2.7 mmol/L
        // hypo at 19:52 was rescued with 25g at 20:05/20:10, and those RESCUE carbs are indistinguishable from
        // a meal by COB alone — so the loop was free to microbolus the recovery away (2.1U from 21:12) and put
        // glucose back on the floor at 03:12. Carbs eaten to escape a low must never license a bolus. After any
        // reading at/below SMB_POST_HYPO_MMOL, block SMB for SMB_POST_HYPO_LOCKOUT_MIN.
        val hypoLookbackMs = now - T.mins(SMB_POST_HYPO_LOCKOUT_MIN).msecs()
        val recentHypo = persistenceLayer.getBgReadingsDataFromTimeToTime(hypoLookbackMs, now, false)
            .any { it.value / MGDL_PER_MMOL <= SMB_POST_HYPO_MMOL }
        val fedState = cobNowG > 0.0 || hasRecentMeal
        val rising = glucoseStatus.delta > 0.0
        // IOB is needed by the UNANNOUNCED-RISE release below, so it is computed here rather than after the
        // gates (it was previously read a few lines further down; the value is identical).
        val iobNow = iobCobCalculator.calculateFromTreatmentsAndTemps(now, profile).iob
        // --- UNANNOUNCED-RISE release (2026-07-25) ---
        // (b) fedState and (e) recentHypo are both time-based and glucose-BLIND, and together they can lock
        // out bolus authority during a genuine, unfed, unopposed climb. Live 2026-07-25 17:37: BG 12.0 and
        // rising, IOB 0.2, COB 0 — basal already ramped to 1.6 U/hr and losing — but SMB was blocked because
        // no carbs had been announced in 180 min AND a 3.6 reading at 16:27 had armed the 4-hour post-hypo
        // lockout. Both guards were right in their own terms and jointly wrong: you are demonstrably not in
        // a hypo when you are at 12 and climbing with no insulin on board.
        //
        // Release BOTH gates only on the conjunction of: clearly high, genuinely rising, and essentially NO
        // insulin on board (so this cannot stack onto an existing correction), plus a minimum separation from
        // the last low so an immediate rebound still cannot license a bolus. Every other SMB guard — the
        // stacking cap, maxIOB/maxBolus, the raw-CGM hypo suspend, the descent guard — still applies on top.
        //
        // Swept over the trailing 21 days of this user's own data, this releases on 8 ticks, NONE of which was
        // followed by a reading below 3.9 within 2h. Widening any threshold (BG < 10, IOB > 0.5, gap < 60 min)
        // re-admits the post-hypo rebound case this is deliberately built to exclude.
        // minOf, not maxOf: we want the age of the MOST RECENT low. maxOfOrNull returns the age of the
        // OLDEST reading in the window, so with two lows (say 2h ago and 20 min ago) it would report 2h
        // and release the gate 20 minutes after a fresh low. Bug shipped 2026-07-26, fixed 2026-07-27.
        val minsSinceHypo = persistenceLayer
            .getBgReadingsDataFromTimeToTime(now - T.mins(SMB_POST_HYPO_LOCKOUT_MIN).msecs(), now, false)
            .filter { it.value / MGDL_PER_MMOL <= SMB_POST_HYPO_MMOL }
            .minOfOrNull { (now - it.timestamp) / 60000.0 } ?: Double.MAX_VALUE
        // POST-HYPO LOCKOUT, now glucose-aware (2026-07-27). The flat 4-hour block was too blunt: its
        // purpose is to stop the loop bolusing away rescue carbs, which only applies while glucose is
        // still low or near it. Measured over 30 days, on ticks where the lockout was ACTIVE:
        //     time since low     all ticks -> went <4.0 in 2h     ...but at BG >= 10
        //       0-60 min            46%                              0%  (n=11, too thin to trust)
        //      60-120 min           17%                              7%
        //     120-180 min           15%                             10%
        //     180-240 min           11%                              0%
        // Baseline for ticks with NO recent low at BG >= 10 is 3%. So being high two hours after a 3.9 is
        // barely more dangerous than being high normally, while the first hour genuinely is (46%).
        // Keep the first hour absolute; after that release on glucose. A threshold sweep over
        // (45/60/90/120 min) x (BG 9/10/11) gave 4-6% everywhere, so the exact cut is not delicate —
        // 60 min / BG 10 is the middle of a flat region, not a fitted edge. IOB was tested as an extra
        // gate and showed no monotonic relationship (0%, 20%, 3%, 0% across bands), i.e. noise.
        val postHypoReleased = minsSinceHypo >= POST_HYPO_RELEASE_MIN &&
            (glucoseStatus.glucose / MGDL_PER_MMOL) >= POST_HYPO_RELEASE_BG_MMOL
        // The IOB cap this used to carry (<= 0.5U) was a crude proxy for "there is not already enough
        // insulin working", and it almost never coincided with a real high — so the release rarely fired.
        // Measured over 30 days on unfed rising highs (COB=0, BG>=10), raw IOB does not predict a
        // subsequent low at all (4%/10%/19%/9%/11% across bands — no trend), but the mass-balance
        // eventual does, cleanly:
        //     eventualMB 0-7  -> 23% went <4.0 in 3h
        //     eventualMB 7-9  -> 16%
        //     eventualMB 9-11 ->  4%
        //     eventualMB 11+  ->  7%
        // That is the honest gate: MB already credits every unit of IOB, so a HIGH MB means the insulin
        // on board is genuinely insufficient regardless of the raw number. Swapping the cap for
        // MB >= 9 releases 111 ticks instead of 38 at a LOWER low rate (5% vs 8%).
        val mbForRelease = (glucoseStatus.glucose / MGDL_PER_MMOL) - iobNow * (isfMgdl / MGDL_PER_MMOL)
        val unannouncedRise = (glucoseStatus.glucose / MGDL_PER_MMOL) >= UNANNOUNCED_RISE_MIN_BG_MMOL &&
            rising &&
            mbForRelease >= UNANNOUNCED_RISE_MIN_MB_MMOL &&
            cobNowG <= 0.0 &&
            minsSinceHypo >= UNANNOUNCED_RISE_MIN_SINCE_HYPO_MIN
        // (d) rolling-window stacking cap: how much SMB has already gone in recently. Individually-trivial
        // microboluses summed to a full correction bolus with nothing tracking the aggregate.
        val smbRecentU = persistenceLayer
            .getBolusesFromTimeToTime(now - T.mins(SMB_STACK_WINDOW_MIN).msecs(), now, false)
            .filter { it.type == BS.Type.SMB }
            .sumOf { it.amount }
        val stackHeadroomU = max(0.0, SMB_STACK_CAP_U - smbRecentU)
        // `unannouncedRise` releases ONLY the two glucose-blind gates (fedState, recentHypo). `rising`, the
        // stacking cap, the closed-loop check and the SMB pref all still bind — as do maxIOB/maxBolus, the
        // raw-CGM hypo suspend and the descent guard further down.
        // (f) POST-SITE-CHANGE. An SMB is irreversible, and during the first hours on a fresh site the
        // insulin it delivers is not absorbed on schedule — it lands later, all at once. This is the
        // one window where "clearly high and rising" is an unreliable licence to bolus, because the
        // high is caused by insulin NOT ARRIVING and more insulin cannot fix that. Basal is
        // cancellable and still ramps (bounded by the cap below); bolus authority stands down.
        val smbAllowed = preferences.get(BooleanKey.HovorkaEnableSmb) &&
            constraintsChecker.isClosedLoopAllowed().value() &&
            rising && stackHeadroomU > 0.0 && !siteGuardActive &&
            ((fedState && (!recentHypo || postHypoReleased)) || unannouncedRise)
        val maxSmbU = if (smbAllowed) {
            // getMaxIOBAllowed() is supplied by the OpenAPS-SMB plugin, which is DISABLED whenever HovorkaMPC is
            // the active APS — so the constraint returns unbounded and the "SMB can never push IOB past maxIOB"
            // guarantee above was silently dead (a real hypo followed on 2026-07-08: SMBs stacked past any
            // ceiling into a meal bolus). Re-apply the user's Max-IOB pref DIRECTLY as the ceiling so it holds
            // regardless of which plugin owns the constraint. min() keeps whichever bound is tighter.
            val maxIob = min(constraintsChecker.getMaxIOBAllowed().value(), preferences.get(DoubleKey.ApsSmbMaxIob))
            val maxBolus = constraintsChecker.getMaxBolusAllowed().value()
            // stackHeadroomU folds the rolling-window cap in as just another ceiling, so the tightest of
            // {maxBolus, per-tick cap, maxIOB headroom, rolling-window headroom} wins.
            max(0.0, min(min(min(maxBolus, SMB_ABS_CAP_U), maxIob - iobNow), stackHeadroomU))
        } else 0.0

        // 3a: if the estimator identifies a regime (IMM), roll the MPC out with that model
        // (ModelIMM1::PredictForOptimise); the single EKF returns null → the personalised model is used.
        val rolloutModel = ekf.rolloutModel() ?: model
        val mpc = HovorkaMpc(
            rolloutModel, targetMmol = controlTargetMmol,
            nominalBasalMuPerMin = nominalBasalMuMin, maxBasalMuPerMin = maxBasalMuMin,
            refTauFastMin = preferences.get(DoubleKey.HovorkaCorrectionTauMin),
            // in closed loop honour a model-requested full suspend (post-bolus) instead of the anti-spam floor
            allowFullSuspend = constraintsChecker.isClosedLoopAllowed().value(),
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
        var smbU = if (rawHypoSuspend) 0.0 else round(decision.smbU * 100.0) / 100.0
        // Report the multi-hour eventual from the IOB/COB mass-balance identity (bolus-wizard):
        //   eventual = currentBG − IOB·ISF + COB·(ISF/IC)
        // and NOT the Hovorka rollout (decision.eventualMmol). As a standalone multi-hour prediction the
        // nonlinear rollout is unreliable in BOTH directions: it craters far too LOW after a carb-matched
        // meal bolus (the original eventualBG=1.2 bug — no counter-regulation, renal loss at the peak), and
        // it over-recovers far too HIGH during a genuine fall (it assumes EGP pulls glucose back to target
        // once basal suspends — it masked a real hypo as "eventual 5.7" at BG 3.1 with 1.0U IOB, 2026-07-05).
        // Mass balance is correct in both regimes, so neither max() (hides real lows) nor min() (reinstates
        // the crater) works — report MB directly. The model still drives ALL dosing (basal + SMB); this is
        // the DISPLAY / predictive-alarm value only. AAPS's own predictive-low alerts key off eventualBG, so
        // an honest MB here restores that warning. The model's rollout stays visible in the reason string.
        val isfMmol = isfMgdl / MGDL_PER_MMOL
        val cobG = iobCobCalculator.getCobInfo("HovorkaEventual").displayCob ?: 0.0
        val carbRiseMmol = if (icGPerU > 0.0) cobG * isfMmol / icGPerU else 0.0
        val eventualLinearMmol = (rawCgmMmol - iobNow * isfMmol + carbRiseMmol).coerceIn(EVENTUAL_MIN_MMOL, EVENTUAL_MAX_MMOL)
        val reportedEventualMmol = eventualLinearMmol
        val mbNote = " | eventualMB=%.1f (IOB %.1f, COB %.0f)".format(eventualLinearMmol, iobNow, cobG)
        // SAFETY — high-glucose correction floor (2026-07-07). The Hovorka rollout can turn over-optimistic:
        // it may predict glucose gliding to target on existing IOB and command u*=0 basal while glucose is
        // actually high and RISING (observed: est.G 14.3→16.7 climbing, model u*=0, loop delivering nothing —
        // the more-sensitive ISF made the model over-credit ~2U IOB as a 6 mmol/L drop that wasn't happening).
        // Guard on the RELIABLE mass-balance eventual: it already credits every unit of IOB/COB, so an eventual
        // STILL above target means insulin is genuinely still needed — a correction here cannot cause a hypo.
        // Ramp extra basal above nominal to clear the residual excess over HIGH_CORRECTION_HORIZON_H, capped at
        // maxBasal. Only ever RAISES the rate — never touches the model's own higher dose, the current-BG
        // damper, the graduated floor, or any suspend (all of which act at/below target, where this never fires).
        // The `rawCgm > target` gate was as loose as the old SMB one: it fired at est.G 7.2 (2/2 firings on
        // 2026-07-16 were at 7.2-7.4), partly because a NEGATIVE IOB — routine after the loop runs below-profile
        // basal — inflates the mass-balance eventual (eventual = G - IOB*ISF, so IOB<0 pushes it UP) and
        // manufactures a fictional excess. Worse, raising basal there partly undoes the current-BG damper, which
        // is the hypo protection near target. Require a GENUINE high, and never credit negative IOB as excess.
        var corrNote = ""
        // --- IOB DIVERGENCE DETECTOR (2026-07-26) ---
        // Every guard here reasons from `eventual = BG - IOB*ISF`, which assumes booked IOB WILL act. When it
        // does not — a failed site, a bad cartridge, degraded insulin — that assumption inverts the loop's
        // behaviour precisely when it matters: the phantom pull-down keeps `eventual` near target, the
        // correction floor sees no excess, and the loop goes quiet at a high it should be attacking.
        //   2026-07-26 08:22  BG 14.9, IOB 3.7 -> eventual 7.0 (target 7.0) -> rate 0.00 for 30 min
        //   2026-07-25 12:17  BG 14.6, IOB 4.1 -> eventual 6.8               -> rate 0.00
        //   2026-07-07 14:32  BG 20.1, IOB 5.2 -> eventual 7.0               -> rate 0.00
        // In each case the booked insulin measurably was not working (2026-07-26 breakfast: dosing was
        // correct at 5.51U against a 5.29U carb requirement, yet ~2.8U never acted).
        //
        // The detector is purely OBSERVATIONAL: if glucose has RISEN over the last half hour while the model
        // insisted it was heading down, the booked IOB is not doing what the model claims, whatever the
        // arithmetic says. Discount it, so the correction floor sees the excess that is really there.
        //
        // Deliberately narrow. Fitted over the user's trailing 21 days it fires on 3.5% of decisions, covers
        // every one of the bad episodes (incl. 2026-07-25 and -26), and only 3% of firings were followed by a
        // sub-3.9 reading within 2h — versus 33-46% for the descent guard's trigger, i.e. it selects a very
        // different and much safer population. Gates:
        //   - glucose genuinely HIGH (>= DIVERGENCE_MIN_BG_MMOL). Below that a discount would RAISE dosing on
        //     a near-target glucose, which is the hypo direction — at BG 8.6 a 50% discount reads MB 2.8 and
        //     would suppress instead of correct. The high gate is what makes this safe.
        //   - a real RISE over the window, not noise
        //   - the model predicted a FALL throughout that window (mass balance below glucose at every tick)
        //   - meaningful IOB booked, or there is nothing to discount
        // It only ever RAISES the eventual (never lowers it), so it can only ever ADD correction at a high;
        // it cannot suppress a suspend, and every hypo backstop downstream still binds.
        // POST-SITE-CHANGE (2026-08-12): stand the divergence detector down inside the guard window.
        // Its signature — glucose RISING while booked IOB says it should be falling — is exactly what a
        // fresh, non-absorbing site produces, and it is the single most likely thing to fire here. But
        // its response is to DISCOUNT IOB and add correction, i.e. dose harder into a site that is not
        // delivering. That is precisely the 2026-08-10 sequence: the loop pushed to 4.03 U/h and the
        // following 6h bottomed at 2.2 mmol/L. The detector was written for a site that has FAILED and
        // will not recover; a fresh site is late, not dead, and the two need opposite responses.
        val divergence = !siteGuardActive && detectIobDivergence(now, rawCgmMmol, iobNow)
        val iobCreditFactor = if (divergence) DIVERGENCE_IOB_CREDIT else 1.0
        val iobForCorrection = max(0.0, iobNow) * iobCreditFactor   // negative IOB = behind on basal, NOT a glucose excess
        val eventualForCorrection = (rawCgmMmol - iobForCorrection * isfMmol + carbRiseMmol)
            .coerceIn(EVENTUAL_MIN_MMOL, EVENTUAL_MAX_MMOL)
        val divNote = if (divergence)
            " | IOB-DIVERGENCE (BG rose %.1f→%.1f while model predicted a fall; crediting %.0f%% of %.1fU)"
                .format(rawCgmMmol - DIVERGENCE_MIN_RISE_MMOL, rawCgmMmol, 100.0 * DIVERGENCE_IOB_CREDIT, iobNow)
        else ""
        // NOTE (2026-07-25): an unconditional "observed high AND rising" bypass of the mass-balance gate was
        // TRIED here and REJECTED — in-silico it added lows in all four cohort scenarios (worst-min 3.1→2.6)
        // for a ≤0.1 mmol/L peak gain. Firing on a high alone cannot distinguish LATE insulin (the common case,
        // where extra dosing stacks into a hypo) from MISSING insulin (the rare real failure). The stalled-meal
        // detector below adds the missing discriminator — TIME — and is gated far more tightly.
        if (!rawHypoSuspend && rawCgmMmol > controlTargetMmol + HIGH_CORRECTION_MIN_HIGH_MMOL && isfMmol > 0.0 &&
            eventualForCorrection > controlTargetMmol + HIGH_CORRECTION_MARGIN_MMOL) {
            val excessUnits = (eventualForCorrection - controlTargetMmol) / isfMmol
            // HIGH-CORR SMB (2026-07-08): at a genuine high the model rollout goes over-optimistic (predicts a
            // phantom fall on existing IOB → u*=0 and its OWN 3b SMB gate declines), so the only thing dosing
            // was this basal floor — arithmetically right but slow. Front-load a fraction of the RELIABLE
            // mass-balance excess as an immediate microbolus so highs clear fast, and let basal clear the rest.
            // Safe: (a) excessUnits is IOB-aware (eventual already subtracts IOB·ISF), so it self-tapers to 0 as
            // IOB accumulates — booked IOB is intrinsically capped at "just enough to reach target", cannot run
            // away; (b) only fires clearly-above-target, never when hypo-suspended; (c) bounded by maxSmbU
            // (maxBolus / abs cap / maxIOB headroom); (d) closed-loop only. Does NOT touch mild-above-target or
            // meal dosing, so it cannot deepen the post-meal lows the way a global ISF cut would.
            // COB gate (2026-07-08): only front-load a correction SMB for a genuine high NOT explained by
            // pending carbs. When real COB is on board the excess is meal-driven — the user's meal bolus and
            // the model's own 3b meal-SMB own that; front-loading here stacked ~1U into a 9.2U dinner bolus and
            // caused a hypo. Basal (cancellable, self-limiting) still ramps for COB; this fast/irreversible path
            // stands down. maxSmbU already honours the real maxIOB ceiling as a second, general backstop.
            // u*-GATE (2026-07-13, overnight hypo→2.2): only front-load an SMB when the MODEL itself is passive
            // (u* at/below nominal) — the exact case this was built for (model over-optimistic → basal alone
            // under-doses). When the model is ALREADY ramping basal to correct (u* > nominal), the cancellable
            // basal carries it; adding an irreversible SMB on top double-doses. Last night the SMB fired every
            // tick alongside u* up to 1.31 U/hr (~291%) on a real rise → ~2U of stacked microbolus → a 5h tail
            // that cratered to 2.2 hours after the loop had already suspended. `rateUhr` here is still the model
            // basal (the floor below hasn't raised it yet). Also require the excess to be CLEARLY above target
            // (not last-mile chasing a predicted ~8 down to 7) — the reversible basal floor handles small highs.
            val modelBasalUhr = rateUhr
            var hcSmbU = 0.0
            if (smbAllowed && maxSmbU > 0.0 && cobG < HIGH_CORR_SMB_MAX_COB_G &&
                modelBasalUhr <= operatingBasalUhr &&
                eventualLinearMmol > controlTargetMmol + HIGH_CORR_SMB_MARGIN_MMOL) {
                hcSmbU = round(min(maxSmbU, HIGH_CORR_SMB_FRACTION * excessUnits) * 100.0) / 100.0
                if (hcSmbU < HIGH_CORR_SMB_MIN_U) hcSmbU = 0.0
            }
            // basal clears only the excess the front-loaded SMB does NOT cover (no double-dosing this tick)
            val residualExcess = max(0.0, excessUnits - hcSmbU)
            val floorUhr = (operatingBasalUhr + residualExcess / HIGH_CORRECTION_HORIZON_H).coerceAtMost(maxBasalUhr)
            if (floorUhr > rateUhr) {
                corrNote = " | HIGH-CORR %.2f→%.2f U/hr (MB %.1f>target %.1f)".format(rateUhr, floorUhr, eventualForCorrection, controlTargetMmol)
                rateUhr = round(floorUhr * 100.0) / 100.0
            }
            if (hcSmbU > smbU) {
                corrNote += " | HIGH-CORR-SMB %.2fU".format(hcSmbU)
                smbU = hcSmbU
            }
        }
        // --- DESCENT GUARD (2026-07-25) — the mirror of the HIGH-CORR floor, and the fix for the 2.8 ---
        // The rollout is unreliable in BOTH directions, and the correction floor above only ever listens to
        // the mass-balance eventual on the UPSIDE. Nothing listened on the downside, so the same over-optimism
        // that withholds insulin at a high also KEEPS DOSING into a fall:
        //   2026-07-25 15:12  BG 7.0 falling   rollout eventual 8.2   eventualMB 4.1  -> still dosed
        //   2026-07-25 15:47  BG 4.7 falling   rollout eventual 7.4   eventualMB 2.9  -> still dosed
        //   2026-07-25 16:12  BG 3.0           rollout eventual 7.0   eventualMB 2.1  -> nadir 2.8
        // The rollout predicted a 7.0 landing all the way down to BG 3.0 (it assumes EGP recovers glucose once
        // basal drops, so it never forecasts a low). The raw-CGM hypo suspend is a backstop that only fires at
        // 3.9 — far too late to prevent the fall, since insulin already delivered cannot be recalled.
        //
        // eventualMB is the RELIABLE number here (it credits every unit of IOB/COB) and it is already computed
        // above for display. Measured over 21 days of this user's own decisions it is strongly predictive:
        //   eventualMB < 4.5 -> a reading below 3.9 within 2h in 33-46% of cases (vs 3% when MB > 8)
        // and the loop was still delivering insulin in 19-44% of those ticks.
        //
        // So: taper basal toward zero as eventualMB falls below DESCENT_GUARD_MMOL, reaching a full suspend at
        // DESCENT_SUSPEND_MMOL. This only ever REMOVES insulin, so unlike every dosing change it cannot cause
        // a hypo — the worst case is a temporarily lower basal that the next tick restores. Cost measured on
        // the real data: ~1.1U withheld over 21 days where no low actually followed (~0.05U/day).
        // Placed AFTER the correction floor so it has the final say: withholding beats correcting.
        // RAW-BG ARM (2026-07-25, same night). The first version of this guard tapered on eventualMB ALONE and
        // was far too weak in practice: live at 23:22, BG 4.8 and falling steadily (6.4 -> 4.8 over 40 min),
        // eventualMB 4.2 sat only 30% into the 4.5->3.5 taper, so it kept 70% of basal and still delivered
        // 0.24 U/hr into a fall. That repeats the exact defect diagnosed in HIGH-CORR this morning — guarding
        // on a PREDICTION with no floor on the OBSERVED value. No forecast should license dosing at 4.8 and
        // dropping. So take the STRONGER of two independent tapers:
        //   - the mass-balance arm (predictive: catches a fall before glucose is low), and
        //   - a raw-CGM arm on the sensor value itself (reactive: cannot be talked out of it by a forecast).
        // Whichever demands the bigger cut wins. Still only ever REMOVES insulin.
        val mbBelow = eventualLinearMmol < DESCENT_GUARD_MMOL
        val bgBelow = rawCgmMmol < DESCENT_BG_GUARD_MMOL
        if (isfMmol > 0.0 && rateUhr > 0.0 && (mbBelow || bgBelow)) {
            val span = (DESCENT_GUARD_MMOL - DESCENT_SUSPEND_MMOL).coerceAtLeast(0.1)
            // 1.0 at/above the guard threshold, 0.0 at/below the suspend threshold
            val mbKeep = ((eventualLinearMmol - DESCENT_SUSPEND_MMOL) / span).coerceIn(0.0, 1.0)
            // raw-CGM taper: full basal at DESCENT_BG_GUARD_MMOL, zero by the hypo-suspend threshold
            val bgSpan = (DESCENT_BG_GUARD_MMOL - HYPO_SUSPEND_MMOL).coerceAtLeast(0.1)
            val bgKeep = ((rawCgmMmol - HYPO_SUSPEND_MMOL) / bgSpan).coerceIn(0.0, 1.0)
            val keepFrac = min(mbKeep, bgKeep)          // the stricter arm wins
            val guarded = round(rateUhr * keepFrac * 100.0) / 100.0
            if (guarded < rateUhr) {
                val arm = if (bgKeep < mbKeep) "BG %.1f".format(rawCgmMmol) else "MB %.1f".format(eventualLinearMmol)
                corrNote += " | DESCENT-GUARD %.2f→%.2f U/hr (%s)".format(rateUhr, guarded, arm)
                rateUhr = guarded
            }
            // an irreversible microbolus must never survive a predicted low
            if (smbU > 0.0) {
                corrNote += " | DESCENT-GUARD-SMB %.2f→0".format(smbU)
                smbU = 0.0
            }
        }
        // NOTE (2026-07-25): a STALLED-MEAL rescue (dose more when a meal bolus >=90 min old is not acting)
        // was built here and REMOVED. In-silico it was a perfect no-op in every cohort scenario, and the real
        // data then explained why it must be: in all four of this user's >16 episodes the booked insulin DID
        // eventually work (20.1 -> 6.8 within 3h), and TWO of the four ended at 3.9 and 2.8. The insulin was
        // late, not missing, and the totals were already excessive — so adding more at the peak deepens the
        // crash that follows. This is the fourth rejected variant of "dose harder at a high"; the post-meal
        // peak is a TIMING problem (pre-bolus lead time, site absorption) and is not safely fixable here.
        // --- POST-SITE-CHANGE, note only (cap REMOVED 2026-08-13) ---
        // The basal cap that used to live here has been deleted. Three reasons, all from the data:
        //  (a) WRONG TIMESCALE. The published impaired window is DAYS (time-to-peak insulin 110 min on
        //      day 1 vs 56 min on day 4, Hildebrandt 1991), not 6 hours. On 2026-08-13 a 6h window
        //      armed at 07:25 would have expired at 13:25 -- twelve minutes after the lunch bolus and
        //      before the entire excursion it was built to catch.
        //  (b) WRONG ACTOR. Replayed over that day it would have withheld 0.00 U of the 10.60 U
        //      delivered: the insulin that mattered was the user's own meal and correction boluses,
        //      which this plugin does not and should not govern.
        //  (c) IT COULD BACKFIRE. If the site opens on cumulative volume rather than elapsed time --
        //      not settled either way -- withholding basal PROLONGS the impaired window.
        // What survives is the pair of actions that are correct under every reading of the mechanism,
        // both applied above: SMB suppression and the divergence stand-down. Both withhold AGGRESSION
        // rather than basal, so widening their window is safe where capping basal was not.
        var siteNote = ""
        if (siteGuardActive)
            siteNote = " | SITE-GUARD armed (cannula %.1fh old, <%.0fh: no SMB, divergence off)"
                .format(siteAgeH ?: 0.0, siteGuardWindowH)
        val ttNote = if (tempTargetMgdl != null) " | TT=%.0f mg/dL".format(tempTargetMgdl) else ""
        val hypoNote = if (rawHypoSuspend) " | CGM-HYPO-SUSPEND %.1f≤%.1f".format(rawCgmMmol, HYPO_SUSPEND_MMOL) else ""
        val reIdNote = if (reIdReasonShort.isNotEmpty()) " | $reIdReasonShort" else ""
        val todNote = if (carbAbsorptionMin != carbAbsorptionBase) " | tMaxG=%.0f (ToD)".format(carbAbsorptionMin) else ""
        val reasonStr = "HovorkaMPC | est.G=%.1f mmol/L%s%s%s%s%s%s%s%s | %s".format(model.glucoseMmol(ekf.x), ttNote, hypoNote, mbNote, divNote, corrNote, siteNote, todNote, reIdNote, decision.reason)

        // Build an oref-shaped RT so AAPS can persist/display it (toDb requires algorithm SMB/AMA + RT).
        // SMB rides on RT.units (+ deliverAt) → DetermineBasalResult.smb → commandQueue bolus (BOLUS_SMB).
        val rt = RT(
            algorithm = APSResult.Algorithm.SMB,
            runningDynamicIsf = false,
            timestamp = now,
            bg = glucoseStatus.glucose,
            targetBG = controlTargetMmol * MGDL_PER_MMOL,
            // eventualBG = IOB/COB mass-balance projection (see above) — honest in both the post-meal-crater
            // and the genuine-fall cases, unlike the model rollout. Drives display + AAPS predictive alarms.
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

    /**
     * Time-of-day multiplier on the carb-absorption time constant (2026-07-25).
     *
     * Keyed on the hour of the MOST RECENT MEAL rather than the current time: a dinner eaten at 18:30 is
     * still absorbing at 22:00, and it is the meal's own absorption profile that matters, not the clock.
     * Falls back to the current hour when there is no recent meal (nothing is absorbing, so the value only
     * affects the model's forward expectation).
     *
     * Multipliers are relative to a 90-minute base (the value this user runs), derived from the measured
     * time-to-peak per block: 06-11h 128min (x1.0), 11-16h 188min (x1.33), 16-24h 261min (x1.67).
     * Overnight shares the evening figure — too few night meals (n=0) to measure, and a late dinner is the
     * likeliest thing still absorbing then.
     */
    private fun carbAbsorptionTodFactor(now: Long): Double {
        val lastMealMs = persistenceLayer
            .getCarbsFromTimeToTimeExpanded(now - T.hours(CARB_TOD_MEAL_LOOKBACK_H).msecs(), now, false)
            .filter { it.amount > 0.0 }
            .maxOfOrNull { it.timestamp }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = lastMealMs ?: now }
        return when (cal.get(java.util.Calendar.HOUR_OF_DAY)) {
            in 6..10  -> CARB_TOD_MORNING_MUL
            in 11..15 -> CARB_TOD_MIDDAY_MUL
            else      -> CARB_TOD_EVENING_MUL
        }
    }

    /**
     * IOB DIVERGENCE (2026-07-26): has glucose RISEN over the last [DIVERGENCE_WINDOW_MIN] minutes while the
     * booked IOB says it should have been falling? If so the insulin on board is not acting and its credit in
     * the mass-balance eventual is fictional.
     *
     * Uses only CGM history + current IOB — no model state — so it cannot be talked out of it by the same
     * rollout that is already wrong. The "model predicted a fall" test is evaluated directly from mass
     * balance at each historical reading (BG - IOB*ISF < BG, i.e. positive IOB), which is what the guards
     * downstream actually consume.
     */
    private fun detectIobDivergence(now: Long, rawCgmMmol: Double, iobNow: Double): Boolean {
        if (rawCgmMmol < DIVERGENCE_MIN_BG_MMOL) return false      // only at a genuine high — see call site
        if (iobNow < DIVERGENCE_MIN_IOB_U) return false            // nothing meaningful to discount
        val readings = persistenceLayer
            .getBgReadingsDataFromTimeToTime(now - T.mins(DIVERGENCE_WINDOW_MIN).msecs(), now, false)
            .sortedBy { it.timestamp }
        if (readings.size < DIVERGENCE_MIN_READINGS) return false  // not enough history to judge
        val oldest = readings.first().value / MGDL_PER_MMOL
        // a real rise across the window, not sensor noise
        if (rawCgmMmol - oldest < DIVERGENCE_MIN_RISE_MMOL) return false
        // ...and monotone enough that this is a sustained climb rather than a spike either side of a dip
        val midpoint = readings[readings.size / 2].value / MGDL_PER_MMOL
        return midpoint > oldest && rawCgmMmol > midpoint
    }

    private fun personalizedModel(w: Double, isfMgdl: Double, icGPerU: Double, basalUhr: Double, targetMmol: Double, carbAbsorptionMin: Double): HovorkaModel {
        val key = "%.1f/%.1f/%.2f/%.4f/%.2f/%.1f".format(w, isfMgdl, icGPerU, basalUhr, targetMmol, carbAbsorptionMin)
        if (key != cachedKey || cachedModel == null) {
            cachedModel = HovorkaModel(HovorkaParams.personalize(w, isfMgdl, icGPerU, basalUhr, targetMmol, tMaxGmin = carbAbsorptionMin))
            cachedKey = key
            aapsLogger.debug(LTag.APS, "HovorkaMPC personalised: W=$w ISF=$isfMgdl IC=$icGPerU basal=$basalUhr target=$targetMmol tMaxG=$carbAbsorptionMin")
        }
        return cachedModel!!
    }

    // 4 re-identification: daily cache (like 2d). Stateless — recomputed from a trailing window each day, ALWAYS
    // from the personalise() prior (no compounding), so the re-tuned model can never be more than one bounded
    // HovorkaParamId step away from the profile.
    private var reIdCacheDay = -1L
    private var reIdCacheModel: HovorkaModel? = null
    private var reIdReasonShort = ""      // short note appended to the MPC reason (loop tab)

    /**
     * 4: re-identify SI / EGP / carb-absorption from the last [RE_ID_DAYS] days of the user's own logs and, if
     * the data clearly shows drift, return a re-tuned model; otherwise return [base] unchanged. Runs once per day
     * (cached). Persists a human-readable status for the settings screen and notifies the user on any change.
     */
    private fun reIdentifiedModel(base: HovorkaModel, weightKg: Double, profileIsfMgdl: Double, now: Long): HovorkaModel {
        if (!preferences.get(BooleanKey.HovorkaParamId)) { reIdReasonShort = ""; return base }
        val dayKey = now / DAY_MS
        if (dayKey == reIdCacheDay && reIdCacheModel != null) return reIdCacheModel!!
        reIdCacheDay = dayKey
        val samples = try {
            reconstructIdSamples(now)
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "HovorkaMPC 4 re-ID reconstruction failed", e); emptyList()
        }
        if (samples.size < RE_ID_MIN_SAMPLES) {
            reIdCacheModel = base; reIdReasonShort = "re-ID: waiting for data"
            return base
        }
        val res = HovorkaParamId(base.p).identify(samples)
        val model = if (res.accepted) HovorkaModel(res.params) else base
        reIdCacheModel = model
        publishReIdResult(res, profileIsfMgdl, now)
        return model
    }

    /** Build the 5-min IdSample history (CGM + enacted basal + boluses + carbs) over the re-ID window. */
    private fun reconstructIdSamples(now: Long): List<IdSample> {
        val start = now - RE_ID_DAYS * DAY_MS
        val bg = persistenceLayer.getBgReadingsDataFromTimeToTime(start, now, true).sortedBy { it.timestamp }
        if (bg.isEmpty()) return emptyList()
        val boluses = persistenceLayer.getBolusesFromTimeToTime(start, now, true)
        val carbs = persistenceLayer.getCarbsFromTimeToTimeExpanded(start, now, true)
        val tbrs = persistenceLayer.getTemporaryBasalsStartingFromTimeToTime(start, now, true)
        val profile = profileFunction.getProfile() ?: return emptyList()
        fun basalUhrAt(ts: Long): Double {
            val tb = tbrs.lastOrNull { it.timestamp <= ts && ts < it.timestamp + it.duration }
            val baseB = profile.getBasal(ts)
            return when { tb == null -> baseB; tb.isAbsolute -> tb.rate; else -> baseB * tb.rate / 100.0 }
        }
        val stepMs = 5 * 60_000L
        val out = ArrayList<IdSample>()
        var bgIdx = 0
        var lastCgm = bg.first().value / MGDL_PER_MMOL       // carried forward across short sensor gaps
        var t = start
        while (t + stepMs <= now) {
            val tEnd = t + stepMs
            while (bgIdx < bg.size && bg[bgIdx].timestamp < tEnd) { lastCgm = bg[bgIdx].value / MGDL_PER_MMOL; bgIdx++ }
            var bolusU = 0.0; for (b in boluses) if (b.timestamp in t until tEnd) bolusU += b.amount
            var carbsG = 0.0; for (c in carbs) if (c.timestamp in t until tEnd) carbsG += c.amount
            out.add(IdSample(lastCgm, basalUhrAt(t + stepMs / 2), bolusU, carbsG))
            t = tEnd
        }
        return out
    }

    /** Persist a human-readable status for the settings screen + reason, and notify the user on any change. */
    private fun publishReIdResult(res: IdResult, profileIsfMgdl: Double, now: Long) {
        val whenStr = dateUtil.dateAndTimeString(now)
        if (res.accepted) {
            val siPct = ((res.siMul - 1.0) * 100.0).roundToInt()
            val egpPct = ((res.egp0Mul - 1.0) * 100.0).roundToInt()
            val agPct = ((res.agMul - 1.0) * 100.0).roundToInt()
            val effIsf = profileIsfMgdl * res.siMul          // ISF scales ~with the SI multiplier (approx, for display)
            val status = ("%s: UPDATED. Insulin sensitivity %+d%% (ISF %.0f→%.0f mg/dL/U approx), " +
                "endogenous glucose %+d%%, carb absorption %+d%%. Forecast error %.2f→%.2f mmol/L. " +
                "Model is at most one bounded step from your profile; the basal you take is unchanged.")
                .format(whenStr, siPct, profileIsfMgdl, effIsf, egpPct, agPct, res.rmsePriorMmol, res.rmseFitMmol)
            preferences.put(StringKey.HovorkaParamIdStatus, status)
            reIdReasonShort = "re-ID ISF%+d%%".format(siPct)
            rxBus.send(EventNewNotification(Notification(RE_ID_NOTIF_ID,
                "HovorkaMPC re-tuned your model: insulin sensitivity %+d%% (ISF≈%.0f→%.0f mg/dL/U). Basal unchanged. Details in HovorkaMPC settings.".format(siPct, profileIsfMgdl, effIsf),
                Notification.INFO)))
            aapsLogger.info(LTag.APS, "HovorkaMPC 4 re-ID: $status")
        } else {
            val status = "%s: no change — your profile still matches your data. (%s)".format(whenStr, res.reason)
            preferences.put(StringKey.HovorkaParamIdStatus, status)
            reIdReasonShort = "re-ID: no change"
            aapsLogger.info(LTag.APS, "HovorkaMPC 4 re-ID: $status")
        }
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
        const val SMB_MEAL_WINDOW_MIN = 180L     // SMB only in a FED state: carbs on board, or a meal within this window
        const val SMB_STACK_WINDOW_MIN = 180L    // rolling window the cumulative-SMB cap is measured over
        const val SMB_POST_HYPO_MMOL = 4.0        // any reading at/below this locks SMB out (rescue carbs must not license a bolus)
        const val SMB_POST_HYPO_LOCKOUT_MIN = 240L // ...for this long afterwards
        const val SMB_STACK_CAP_U = 1.0          // max total SMB within that window (13 x 0.1-0.3U = 2.1U overnight hypo)
        const val RE_ID_DAYS = 10L               // 4: trailing window the re-identification fits over
        const val RE_ID_MIN_SAMPLES = 864        // 4: require ~3 days of 5-min samples before re-tuning
        const val RE_ID_NOTIF_ID = 4210          // 4: notification id for a model re-tune (INFO; re-usable)
        const val EVENTUAL_MIN_MMOL = 1.5        // #1: sanity clamp on the mass-balance eventual (bad COB/IOB)
        const val EVENTUAL_MAX_MMOL = 30.0
        const val HIGH_CORRECTION_MIN_HIGH_MMOL = 2.0  // correction floor needs a GENUINE high (target+this), not merely >target
        const val HIGH_CORRECTION_MARGIN_MMOL = 0.6   // high-glucose correction floor fires only when mass-balance eventual exceeds target by this
        const val HIGH_CORRECTION_HORIZON_H = 1.5     // clear the residual mass-balance excess over this many hours (in-silico 2026-07-07: 1.5h beats 2h on highs, still zero added lows)
        const val HIGH_CORR_SMB_FRACTION = 0.3        // 2026-07-08: share of the mass-balance high-glucose excess delivered NOW as an SMB (rest ramps via the basal floor); self-tapers as IOB builds. Raise → more aggressive at highs (0.5 stacked into a meal bolus → hypo; cut to 0.3)
        const val HIGH_CORR_SMB_MIN_U = 0.05          // skip sub-resolution high-corr microboluses
        const val HIGH_CORR_SMB_MAX_COB_G = 10.0      // 2026-07-08: no front-loaded correction SMB while >=this many g of carbs are pending (meal territory → basal + meal bolus own it; prevents stacking)
        const val HIGH_CORR_SMB_MARGIN_MMOL = 2.0     // 2026-07-13: front-load an SMB only when the mass-balance eventual is THIS far above target (the reversible basal floor handles smaller highs; stops last-mile chasing that overshoots)

        // --- DESCENT GUARD (2026-07-25). The mirror of the correction floor: taper basal toward zero as the
        // RELIABLE mass-balance eventual falls, so the loop stops dosing into a fall instead of waiting for the
        // 3.9 raw-CGM backstop (by which point the insulin is already delivered and cannot be recalled).
        // Thresholds from this user's own 21 days: eventualMB < 4.5 preceded a sub-3.9 reading within 2h in
        // 33-46% of ticks (vs 3% when MB > 8), and the loop was still dosing in 19-44% of them. Measured cost
        // of the guard: ~1.1U withheld over 21 days where no low followed (~0.05 U/day). ---
        // --- UNANNOUNCED-RISE release (2026-07-25). Lets SMB through the two glucose-BLIND gates (fedState,
        // post-hypo lockout) during a genuine unfed climb with no insulin on board. Swept over the trailing
        // 21 days: 8 releases, ZERO followed by a sub-3.9 reading within 2h. Loosening any of the three
        // re-admits the post-hypo rebound this is built to exclude. ---
        // --- POST-HYPO lockout release (2026-07-27). The first hour after a low stays absolutely
        // blocked (46% of those ticks go low again); after that the block lifts once glucose is clearly
        // high, where the measured risk is 0-10% against a 3% no-recent-low baseline. See the derivation
        // at the call site. ---
        const val POST_HYPO_RELEASE_MIN = 60.0          // first hour after a low is never released
        const val POST_HYPO_RELEASE_BG_MMOL = 10.0      // ...after that, release only when clearly high
        const val UNANNOUNCED_RISE_MIN_BG_MMOL = 10.0       // must be clearly high
        const val UNANNOUNCED_RISE_MIN_MB_MMOL = 9.0        // ...and mass balance still says the insulin on board is NOT enough
        const val UNANNOUNCED_RISE_MIN_SINCE_HYPO_MIN = 60.0 // ...and not an immediate rebound off a low
        // --- TIME-OF-DAY carb absorption (2026-07-25). Multipliers on the base HovorkaCarbAbsorptionMin pref,
        // from 54 measured meals: time from carb entry to post-dip glucose peak was 128 min (06-11h),
        // 188 min (11-16h), 261 min (16-24h). Ratios vs the morning block give 1.0 / 1.33 / 1.67. ---
        const val CARB_TOD_MORNING_MUL = 1.0   // 06-11h — the base value already fits breakfast
        const val CARB_TOD_MIDDAY_MUL = 1.33   // 11-16h
        const val CARB_TOD_EVENING_MUL = 1.67  // 16-06h — dinner absorbs >2x slower than breakfast
        const val CARB_TOD_MEAL_LOOKBACK_H = 6L // how far back to look for the meal whose profile still applies
        // Thresholds RAISED 2026-07-27 after measuring outcomes over 21 days. The originals were set by
        // guesswork and were far too low — at BG 5.5-6.0 falling the guard still kept 76% of basal, and
        // the MB arm did not begin tapering until 4.5, by which point the outcome is already decided.
        // Measured probability of dropping below 4.0 within 2h, on FALLING decisions:
        //     BG 6.5-7.0  15%      MB 5.5-6.5  22%
        //     BG 6.0-6.5  23%      MB 5.0-5.5  27%
        //     BG 5.5-6.0  27%      MB 4.5-5.0  34%
        //     BG 5.0-5.5  43%      MB 4.0-4.5  52%
        //     BG 4.5-5.0  51%
        // A band where a quarter of falls end below 4.0 is not one to be delivering near-nominal basal in.
        // Both tapers now start where the risk starts rather than where a low is already underway.
        const val DESCENT_GUARD_MMOL = 6.5     // begin tapering basal below this mass-balance eventual
        const val DESCENT_SUSPEND_MMOL = 4.0   // ...reaching a full suspend here
        // RAW-BG arm: taper on the SENSOR value too, so no forecast can license dosing into an observed fall.
        // Full basal at this value, ramping to zero by HYPO_SUSPEND_MMOL (3.9). Live 2026-07-25 23:22 the
        // MB-only guard still gave 0.24 U/hr at BG 4.8 falling; with this arm that tick keeps 24% -> 0.08.
        // --- IOB DIVERGENCE detector (2026-07-26). Discounts booked IOB that is observably not acting, so a
        // failed site / bad cartridge cannot silence the correction floor at a high. Fitted on the user's
        // trailing 21 days: fires on 3.5% of decisions, covers every bad episode, and only 3% of firings were
        // followed by a sub-3.9 reading within 2h (vs 33-46% for the descent-guard trigger). ---
        const val DIVERGENCE_MIN_BG_MMOL = 10.0   // only at a genuine high — below this a discount would raise dosing toward a hypo
        const val DIVERGENCE_MIN_RISE_MMOL = 1.0  // a real rise across the window, not sensor noise
        const val DIVERGENCE_WINDOW_MIN = 30L     // ...measured over this long
        const val DIVERGENCE_MIN_IOB_U = 1.0      // ...with enough IOB booked for the discount to mean anything
        const val DIVERGENCE_MIN_READINGS = 4     // need this many CGM points in the window to judge
        const val DIVERGENCE_IOB_CREDIT = 0.5     // credit only this share of booked IOB while diverging
        // --- POST-SITE-CHANGE GUARD (2026-08-12) ---
        // Measured on this user's own data (report/site-change-findings.md, realdata/site_change.py):
        // for ~6h after a cannula change insulin does not absorb. Mean glucose 13.5 mmol/L against 7.6
        // in the SAME clock hours on non-change days, while taking 4x the bolus insulin (2.15 vs 0.51
        // U/h). 6 of 6 changes, paired sign test p=0.016; excluding one change with a documented
        // occlusion it is 5/5, +3.3 mmol/L, p=0.031. The extra carbs eaten in those windows justify
        // ~3U more insulin; ~9.8U more was actually delivered and glucose STILL ran 6.0 higher.
        // The cleanest case (2026-08-04) had ZERO carbs, ~13U of bolus over 6h, and averaged 11.8.
        //
        // So the loop's normal response is not merely useless here, it is harmful: the insulin is going
        // somewhere, and when the site opens it arrives all at once. The one change where the loop
        // pushed hardest (2026-08-10, 4.03 U/h) ended at 2.2 mmol/L with 46% of the following 6h
        // below 3.9.
        //
        // This guard is the fourth "dose differently at a high" mechanism in this file and the FIRST
        // that removes insulin rather than adding it — the previous three (stalled-meal rescue, the
        // high-and-rising bypass, unconditional divergence) were all rejected for adding lows. It can
        // only ever LOWER the rate, so it cannot cause a hypo.
        const val DESCENT_BG_GUARD_MMOL = 7.5
    }
}

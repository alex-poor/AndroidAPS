package app.aaps.ui.dialogs

import app.aaps.core.data.model.GlucoseUnit
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.objects.wizard.BolusWizard
import app.aaps.ui.dialogs.compose.WizardInputs
import app.aaps.ui.dialogs.compose.PumpReadyGate
import app.aaps.ui.dialogs.compose.WizardResult
import app.aaps.ui.dialogs.compose.WizardScreen
import dagger.android.support.DaggerDialogFragment
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.abs

/**
 * Redesigned Bolus/Carb Wizard. The UI is Compose ([WizardScreen]); all dosing math reuses the
 * existing [BolusWizard] (`doCalc`) and delivery reuses [BolusWizard.confirmAndExecute] — the exact
 * same constraint + confirmation + execution path as before. BG is taken from CGM (no manual BG /
 * profile / correction fields, per the design). DI + `runWizardDialog` routing are unchanged.
 */
class WizardDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var ctx: Context
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var bolusWizardProvider: Provider<BolusWizard>
    @Inject lateinit var pumpReadyGate: PumpReadyGate

    @Suppress("unused")
    private val handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    private var initialCarbs = 0

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        aapsLogger.debug(LTag.APS, "Dialog opened: ${this.javaClass.simpleName}")
    }

    /**
     * The HandlerThread started above is not a daemon, so without this it outlives the dialog and
     * one thread leaks per wizard open — ten of them were live on device. ErrorDialog already does
     * exactly this; the wizard was simply missing it.
     */
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        arguments?.let { initialCarbs = it.getDouble("carbs_input", 0.0).toInt() }
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    WizardScreen(
                        compute = ::compute,
                        onDeliver = ::deliver,
                        onCancel = { dismiss() },
                        initialInputs = WizardInputs(carbs = initialCarbs)
                    )
                }
            }
        }
    }

    /** Build the wizard for [inputs] and format its components for display. Pure — no side effects. */
    private fun compute(inputs: WizardInputs): WizardResult {
        val profile = profileFunction.getProfile() ?: return WizardResult()
        val units = profileFunction.getUnits()
        // A manually entered glucose REPLACES the CGM everywhere in this calculation — the correction, the
        // in-range label and the delivered dose all key off the same number, so there is no path where the
        // display says one thing and the arithmetic uses another.
        val cgmMgdl = (iobCobCalculator.ads.actualBg() ?: iobCobCalculator.ads.lastBg())?.recalculated ?: 0.0
        val bgDisplay = inputs.manualBg ?: if (cgmMgdl > 0) profileUtil.fromMgdlToUnits(cgmMgdl, units) else 0.0
        val bgMgdl = inputs.manualBg?.let { profileUtil.convertToMgdl(it, units) } ?: cgmMgdl
        val carbs = constraintChecker.applyCarbsConstraints(ConstraintObject(inputs.carbs, aapsLogger)).value()

        val w = buildWizard(inputs, profile, bgDisplay, carbs)

        // Label BG against the user's display low/high marks (e.g. 4.0–10.0) — the SAME band the rest of the
        // app colours BG by — NOT the target band. A single-point profile target makes targetLow==targetHigh,
        // so an at-target BG (7.1 vs target 7.0) would read "high", which is useless for bolusing. Display only.
        val inRange = bgMgdl > 0 &&
            bgDisplay in preferences.get(UnitDoubleKey.OverviewLowMark)..preferences.get(UnitDoubleKey.OverviewHighMark)
        val delta = w.glucoseStatus?.delta ?: 0.0
        val mmol = units == GlucoseUnit.MMOL
        return WizardResult(
            bgText = if (bgMgdl > 0) profileUtil.fromMgdlToStringInUnits(bgMgdl) else "--",
            // A manual entry has no trend: the arrow would be the SENSOR's, and the reason to type a value in
            // is that the sensor is not to be trusted. Showing it would invite reading a slope into a number
            // that has none.
            bgTrendArrow = if (inputs.manualBg != null) "" else when { delta > 3 -> "↗"; delta < -3 -> "↘"; else -> "→" },
            bgFromText = if (inputs.manualBg != null) "Entered manually" else "From CGM",
            bgIsManual = inputs.manualBg != null,
            bgEntryMin = if (mmol) 1.0 else 20.0,
            bgEntryMax = if (mmol) 30.0 else 540.0,
            bgEntryStep = if (mmol) 0.1 else 1.0,
            bgEntryDecimals = if (mmol) 1 else 0,
            bgUnitsLabel = if (mmol) "mmol/L" else "mg/dL",
            bgInRange = inRange,
            carbsInsulin = signed(w.insulinFromCarbs),
            bgInsulin = signed(w.insulinFromBG),
            iobInsulin = signed(-w.insulinFromBolusIOB - w.insulinFromBasalIOB),
            trendInsulin = signed(w.insulinFromTrend),
            superBolusInsulin = signed(w.insulinFromSuperBolus),
            // Show the amount that will ACTUALLY be delivered (post max-bolus constraint), because the
            // redesigned wizard no longer shows the legacy confirm dialog that surfaced the cap. If the
            // constraint reduced the dose, expose it so the user isn't misled about what they're bolusing.
            total = w.insulinAfterConstraints,
            totalText = String.format(Locale.getDefault(), "%.2f U", w.insulinAfterConstraints),
            deliverable = w.insulinAfterConstraints > 0.0,
            carbsOnly = carbs > 0 && w.insulinAfterConstraints <= 0.0,
            note = if (carbs > 0) "Also logging $carbs g carbs" else "",
            cappedWarning = if (w.calculatedTotalInsulin - w.insulinAfterConstraints > activePlugin.activePump.pumpDescription.bolusStep)
                String.format(Locale.getDefault(), "Capped by max bolus: %.2f U → %.2f U", w.calculatedTotalInsulin, w.insulinAfterConstraints)
            else "",
            siteWarning = freshSiteWarning(w.insulinAfterConstraints),
            superBolusAvailable = false
        )
    }

    /**
     * Advisory when bolusing into a cannula less than [FRESH_SITE_H] old.
     *
     * Only fires for a dose big enough to matter -- a fresh site handles a basal trickle fine, and the
     * handicap is only expressed under a large single bolus. Silent when no cannula change has ever
     * been recorded, so a database with no site history never nags.
     */
    private fun freshSiteWarning(insulin: Double): String {
        if (insulin < FRESH_SITE_MIN_BOLUS_U) return ""
        val last = persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.CANNULA_CHANGE)?.timestamp ?: return ""
        val ageH = (dateUtil.now() - last) / T.hours(1).msecs().toDouble()
        if (ageH >= FRESH_SITE_H || ageH < 0) return ""
        return String.format(
            Locale.getDefault(),
            "New cannula (%.0fh) — insulin peaks ~2× slower here. Consider splitting this dose, and give corrections time.",
            ageH
        )
    }

    /** Deliver: rebuild for the confirmed inputs and run the SAME confirm+constraint+execute path. */
    private fun deliver(inputs: WizardInputs) {
        val activity = activity ?: return
        val profile = profileFunction.getProfile() ?: return
        val units = profileFunction.getUnits()
        val cgmMgdl = (iobCobCalculator.ads.actualBg() ?: iobCobCalculator.ads.lastBg())?.recalculated ?: 0.0
        val bgDisplay = inputs.manualBg ?: if (cgmMgdl > 0) profileUtil.fromMgdlToUnits(cgmMgdl, units) else 0.0
        val bgMgdl = inputs.manualBg?.let { profileUtil.convertToMgdl(it, units) } ?: cgmMgdl
        val carbs = constraintChecker.applyCarbsConstraints(ConstraintObject(inputs.carbs, aapsLogger)).value()
        if (carbs <= 0 && !(bgMgdl > 0)) return
        val w = buildWizard(inputs, profile, bgDisplay, carbs)
        if (w.calculatedTotalInsulin > 0.0 || carbs > 0) {
            // skipConfirmation: the Compose Confirm step + press-and-hold gesture IS the confirmation, so
            // suppress the legacy OKDialog (redundant second popup). Constraints, UEL audit and the actual
            // commandQueue.bolus still run — identical execute path, just without the extra tap.
            // Pre-flight the pump before the dose is queued. A stopped or empty pump used to accept
            // the command and then leave the progress dialog at 0% for the driver's whole confirm
            // window; now the user is told why, and "Check again" delivers the SAME dose once the pump
            // is running rather than making them rebuild it.
            pumpReadyGate.runWhenPumpCanDeliver(activity) { w.confirmAndExecute(activity, skipConfirmation = true) }
            dismiss()
        }
    }

    private fun buildWizard(inputs: WizardInputs, profile: app.aaps.core.interfaces.profile.Profile, bgDisplay: Double, carbs: Int): BolusWizard =
        bolusWizardProvider.get().doCalc(
            profile = profile,
            profileName = profileFunction.getProfileName(),
            tempTarget = persistenceLayer.getTemporaryTargetActiveAt(dateUtil.now()),
            carbs = carbs,
            cob = 0.0,
            bg = bgDisplay,
            correction = 0.0,
            // pre-bolus: BolusWizard timestamps the carbs at now + carbTime (see its carbsTimestamp), so the
            // bolus goes in immediately while the loop is told when the carbs actually land.
            carbTime = inputs.carbTime,
            // extended carbs: declares a slow meal's absorption per-meal (AAPS expands to 15-min chunks)
            carbDurationHours = inputs.carbDurationHours,
            percentageCorrection = preferences.get(IntKey.OverviewBolusPercentage),
            useBg = inputs.useBg,
            useCob = false,
            includeBolusIOB = inputs.useIob,
            includeBasalIOB = inputs.useIob,
            useSuperBolus = inputs.useSuperBolus,
            useTT = true,
            // Trend is a CGM property. If the user overrode the CGM, its slope is not evidence about the
            // number they typed — so the trend contribution is withheld rather than silently reused.
            useTrend = inputs.useTrend && inputs.manualBg == null,
            useAlarm = false
        )

    private fun signed(v: Double): String {
        val rounded = if (abs(v) < 0.005) 0.0 else v
        val sign = if (rounded > 0) "+" else ""
        return sign + String.format(Locale.getDefault(), "%.2f U", rounded)
    }

    companion object {

        /** Day-1 window. The measured effect spans days; 24h captures the worst of it. */
        const val FRESH_SITE_H = 24.0

        /** Below this, a single bolus is small enough that depot surface-to-volume is not the issue. */
        const val FRESH_SITE_MIN_BOLUS_U = 1.5
    }

}

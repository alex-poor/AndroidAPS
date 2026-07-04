package app.aaps.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.HardLimits
import app.aaps.core.objects.extensions.getCustomizedName
import app.aaps.core.objects.extensions.pureProfileFromJson
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.ui.dialogs.compose.ProfileViewerCategory
import app.aaps.ui.dialogs.compose.ProfileViewerRow
import app.aaps.ui.dialogs.compose.ProfileViewerSheet
import app.aaps.ui.dialogs.compose.ProfileViewerState
import dagger.android.support.DaggerDialogFragment
import org.json.JSONObject
import java.util.Locale
import javax.inject.Inject

/**
 * Redesigned read-only Profile viewer. UI is Compose ([ProfileViewerSheet]); this fragment keeps the
 * legacy public API — same argument keys ("time", "mode", "customProfile", "customProfileName",
 * "customProfile2") + [UiInteraction.Mode] handling — so `uiInteraction.runProfileViewerDialog`
 * callers are unaffected. Presentation only, no submit.
 */
class ProfileViewerDialog : DaggerDialogFragment() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var config: Config
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var hardLimits: HardLimits
    @Inject lateinit var decimalFormatter: DecimalFormatter

    private var time: Long = 0

    private var mode: UiInteraction.Mode = UiInteraction.Mode.RUNNING_PROFILE
    private var customProfileJson: String = ""
    private var customProfileJson2: String = ""
    private var customProfileName: String = ""

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.BOTTOM)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        // load data from bundle
        (savedInstanceState ?: arguments)?.let { bundle ->
            time = bundle.getLong("time", 0)
            mode = UiInteraction.Mode.entries.toTypedArray()[bundle.getInt("mode", UiInteraction.Mode.RUNNING_PROFILE.ordinal)]
            customProfileJson = bundle.getString("customProfile", "")
            customProfileName = bundle.getString("customProfileName", "")
            if (mode == UiInteraction.Mode.PROFILE_COMPARE)
                customProfileJson2 = bundle.getString("customProfile2", "")
        }

        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)

        val state = buildState()
        if (state == null) {
            dismiss()
            return View(requireContext())
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { ProfileViewerSheet(state = state, onClose = { dismiss() }) } }
        }
    }

    override fun onSaveInstanceState(bundle: Bundle) {
        super.onSaveInstanceState(bundle)
        bundle.putLong("time", time)
        bundle.putInt("mode", mode.ordinal)
        bundle.putString("customProfile", customProfileJson)
        bundle.putString("customProfileName", customProfileName)
        if (mode == UiInteraction.Mode.PROFILE_COMPARE)
            bundle.putString("customProfile2", customProfileJson2)
    }

    /** Extract the same values the legacy dialog displayed into a presentation-only [ProfileViewerState]. */
    private fun buildState(): ProfileViewerState? {
        val profile: ProfileSealed?
        val profile2: ProfileSealed?
        var profileName: String?
        var date: String? = null
        when (mode) {
            UiInteraction.Mode.RUNNING_PROFILE -> {
                val eps = persistenceLayer.getEffectiveProfileSwitchActiveAt(time) ?: return null
                profile = ProfileSealed.EPS(eps, activePlugin)
                profile2 = null
                profileName = eps.originalCustomizedName
                date = dateUtil.dateAndTimeString(eps.timestamp)
            }

            UiInteraction.Mode.CUSTOM_PROFILE  -> {
                profile = pureProfileFromJson(JSONObject(customProfileJson), dateUtil)?.let { ProfileSealed.Pure(it, activePlugin) }
                profile2 = null
                profileName = customProfileName
            }

            UiInteraction.Mode.PROFILE_COMPARE -> {
                profile = pureProfileFromJson(JSONObject(customProfileJson), dateUtil)?.let { ProfileSealed.Pure(it, activePlugin) }
                profile2 = pureProfileFromJson(JSONObject(customProfileJson2), dateUtil)?.let { ProfileSealed.Pure(it, activePlugin) }
                profileName = customProfileName
            }

            UiInteraction.Mode.DB_PROFILE      -> {
                val profileList = persistenceLayer.getProfileSwitches()
                profile = if (profileList.isNotEmpty()) ProfileSealed.PS(profileList[0], activePlugin) else null
                profile2 = null
                profileName = if (profileList.isNotEmpty()) profileList[0].getCustomizedName(decimalFormatter) else null
                date = if (profileList.isNotEmpty()) dateUtil.dateAndTimeString(profileList[0].timestamp) else null
            }
        }
        if (profile == null) return null

        val unitsLabel = profileFunction.getUnits().asText
        val validity = profile.isValid("ProfileViewDialog", activePlugin.activePump, config, rh, rxBus, hardLimits, false)
        val invalid = if (validity.isValid) null
        else rh.gs(app.aaps.core.ui.R.string.invalid_profile) + "\n" + validity.reasons.joinToString(separator = "\n")

        val compare = mode == UiInteraction.Mode.PROFILE_COMPARE && profile2 != null

        // PROFILE_COMPARE keeps both names (legacy split "name\nname2").
        var name2: String? = null
        if (compare) {
            val names = (profileName ?: "").split("\n")
            profileName = names.getOrElse(0) { "" }
            name2 = names.getOrElse(1) { "" }
        }

        return ProfileViewerState(
            name = profileName ?: "",
            name2 = name2,
            dia = rh.gs(app.aaps.core.ui.R.string.format_hours, profile.dia),
            dailyBasal = fmtU(profile.baseBasalSum()),
            dailyBasal2 = if (compare) fmtU(profile2!!.baseBasalSum()) else null,
            date = date,
            unitsLabel = unitsLabel,
            invalid = invalid,
            compare = compare,
            basal = ProfileViewerCategory("Basal", basalRows(profile, profile2, compare)),
            isf = ProfileViewerCategory("ISF", isfRows(profile, profile2, compare, unitsLabel)),
            ic = ProfileViewerCategory("Carb ratio", icRows(profile, profile2, compare)),
            target = ProfileViewerCategory("Target", targetRows(profile, profile2, compare, unitsLabel))
        )
    }

    private fun hhmm(sec: Int) = dateUtil.formatHHMM(sec)
    private fun fmtU(v: Double) = String.format(Locale.getDefault(), "%.2f U/h", v)

    private fun basalRows(p1: Profile, p2: Profile?, compare: Boolean): List<ProfileViewerRow> {
        val rows = mutableListOf<ProfileViewerRow>()
        var prev1 = -1.0
        var prev2 = -1.0
        for (hour in 0..23) {
            val sec = hour * 3600
            val v1 = p1.getBasalTimeFromMidnight(sec)
            val v2 = if (compare) p2!!.getBasalTimeFromMidnight(sec) else -1.0
            if (v1 != prev1 || (compare && v2 != prev2)) {
                rows.add(ProfileViewerRow(hhmm(sec), fmtU(v1), if (compare) fmtU(v2) else null))
            }
            prev1 = v1; prev2 = v2
        }
        return rows
    }

    private fun icRows(p1: Profile, p2: Profile?, compare: Boolean): List<ProfileViewerRow> {
        val rows = mutableListOf<ProfileViewerRow>()
        var prev1 = -1.0
        var prev2 = -1.0
        for (hour in 0..23) {
            val sec = hour * 3600
            val v1 = p1.getIcTimeFromMidnight(sec)
            val v2 = if (compare) p2!!.getIcTimeFromMidnight(sec) else -1.0
            if (v1 != prev1 || (compare && v2 != prev2)) {
                rows.add(ProfileViewerRow(hhmm(sec), fmtG(v1), if (compare) fmtG(v2) else null))
            }
            prev1 = v1; prev2 = v2
        }
        return rows
    }

    private fun isfRows(p1: Profile, p2: Profile?, compare: Boolean, units: String): List<ProfileViewerRow> {
        val rows = mutableListOf<ProfileViewerRow>()
        var prev1 = -1.0
        var prev2 = -1.0
        for (hour in 0..23) {
            val sec = hour * 3600
            val v1 = profileUtil.fromMgdlToUnits(p1.getIsfMgdlTimeFromMidnight(sec))
            val v2 = if (compare) profileUtil.fromMgdlToUnits(p2!!.getIsfMgdlTimeFromMidnight(sec)) else -1.0
            if (v1 != prev1 || (compare && v2 != prev2)) {
                rows.add(ProfileViewerRow(hhmm(sec), "${fmt1(v1)} $units", if (compare) "${fmt1(v2)} $units" else null))
            }
            prev1 = v1; prev2 = v2
        }
        return rows
    }

    private fun targetRows(p1: Profile, p2: Profile?, compare: Boolean, units: String): List<ProfileViewerRow> {
        val rows = mutableListOf<ProfileViewerRow>()
        var prev1l = -1.0; var prev1h = -1.0
        var prev2l = -1.0; var prev2h = -1.0
        for (hour in 0..23) {
            val sec = hour * 3600
            val v1l = p1.getTargetLowMgdlTimeFromMidnight(sec)
            val v1h = p1.getTargetHighMgdlTimeFromMidnight(sec)
            val v2l = if (compare) p2!!.getTargetLowMgdlTimeFromMidnight(sec) else -1.0
            val v2h = if (compare) p2!!.getTargetHighMgdlTimeFromMidnight(sec) else -1.0
            val changed = v1l != prev1l || v1h != prev1h || (compare && (v2l != prev2l || v2h != prev2h))
            if (changed) {
                val t1 = "${profileUtil.fromMgdlToStringInUnits(v1l)} - ${profileUtil.fromMgdlToStringInUnits(v1h)} $units"
                val t2 = if (compare) "${profileUtil.fromMgdlToStringInUnits(v2l)} - ${profileUtil.fromMgdlToStringInUnits(v2h)} $units" else null
                rows.add(ProfileViewerRow(hhmm(sec), t1, t2))
            }
            prev1l = v1l; prev1h = v1h; prev2l = v2l; prev2h = v2h
        }
        return rows
    }

    private fun fmt1(v: Double) = String.format(Locale.getDefault(), "%.1f", v)
    private fun fmtG(v: Double) = String.format(Locale.getDefault(), "%.1f g/U", v)
}

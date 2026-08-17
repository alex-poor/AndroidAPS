package app.aaps.ui.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.model.EPS
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.PureProfile
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventLocalProfileChanged
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.activities.profilehelper.ProfileHelperScreen
import app.aaps.ui.activities.profilehelper.ProfileHelperState
import app.aaps.ui.activities.profilehelper.ProfileKind
import app.aaps.ui.activities.profilehelper.ProfileSlot
import app.aaps.ui.defaultProfile.DefaultProfile
import app.aaps.ui.defaultProfile.DefaultProfileDPV
import app.aaps.ui.dialogs.ProfileViewerDialog
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Build a reference profile from age / weight / TDD and compare two of them. UI is Compose
 * ([ProfileHelperScreen]); the profile maths ([DefaultProfile] / [DefaultProfileDPV]), the validation
 * bounds, the compare hand-off to [ProfileViewerDialog] and the "copy to local profile" confirmation
 * are all unchanged from the legacy screen.
 */
class ProfileHelperActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var tddCalculator: TddCalculator
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var defaultProfile: DefaultProfile
    @Inject lateinit var defaultProfileDPV: DefaultProfileDPV
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rxBus: RxBus

    private lateinit var profileList: ArrayList<CharSequence>
    private lateinit var profileSwitch: List<EPS>

    private val state = mutableStateOf(ProfileHelperState())
    private val tddStats = mutableStateOf<View?>(null)
    private val disposable = CompositeDisposable()

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        profileList = activePlugin.activeProfileSource.profile?.getProfileList() ?: ArrayList()
        profileSwitch = persistenceLayer.getEffectiveProfileSwitchesFromTime(dateUtil.now() - T.months(2).msecs(), true).blockingGet()

        state.value = ProfileHelperState(
            kindLabels = listOf(
                rh.gs(R.string.motol_default_profile),
                rh.gs(R.string.dpv_default_profile),
                rh.gs(R.string.current_profile),
                rh.gs(R.string.available_profile),
                rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch)
            ),
            availableProfiles = profileList.map { it.toString() },
            profileSwitches = profileSwitch.map { it.originalCustomizedName },
            currentProfileName = profileFunction.getProfileName()
        )

        disposable += Single.fromCallable { tddCalculator.stats(this) }
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe({ tddStats.value = it }, fabricPrivacy::logException)

        setContentView(ComposeView(this).apply {
            setContent {
                AapsTheme {
                    ProfileHelperScreen(
                        state = state.value,
                        tddStatsView = tddStats.value,
                        onTab = { state.value = state.value.copy(tab = it) },
                        onSlotChange = { slot ->
                            val slots = state.value.slots.toMutableList().also { it[state.value.tab] = slot }
                            state.value = state.value.copy(slots = slots)
                        },
                        onCopyToLocal = ::copyToLocalProfile,
                        onCompare = ::compareProfiles,
                        onBack = { finish() }
                    )
                }
            }
        })
    }

    private fun copyToLocalProfile() {
        val slot = state.value.slot
        val profile =
            if (slot.kind == ProfileKind.MOTOL_DEFAULT) defaultProfile.profile(slot.age, slot.tdd, slot.weight, profileFunction.getUnits())
            else defaultProfileDPV.profile(slot.age, slot.tdd, slot.basalPct / 100.0, profileFunction.getUnits())
        profile?.let {
            OKDialog.showConfirmation(this, rh.gs(app.aaps.core.ui.R.string.careportal_profileswitch), rh.gs(app.aaps.core.ui.R.string.copytolocalprofile), {
                activePlugin.activeProfileSource.addProfile(
                    activePlugin.activeProfileSource.copyFrom(
                        it, "DefaultProfile " + dateUtil.dateAndTimeAndSecondsString(dateUtil.now()).replace(".", "/")
                    )
                )
                rxBus.send(EventLocalProfileChanged())
            })
        }
    }

    /** Same bounds the legacy screen enforced before it would compare. */
    private fun validate(slot: ProfileSlot): Int? = when (slot.kind) {
        ProfileKind.MOTOL_DEFAULT -> when {
            slot.age < 1 || slot.age > 18                                 -> R.string.invalid_age
            (slot.weight < 5 || slot.weight > 150) && slot.tdd == 0.0     -> R.string.invalid_weight
            (slot.tdd < 5 || slot.tdd > 150) && slot.weight == 0.0        -> R.string.invalid_weight
            else                                                          -> null
        }

        ProfileKind.DPV_DEFAULT   -> when {
            slot.age < 1 || slot.age > 18                                 -> R.string.invalid_age
            slot.tdd < 5 || slot.tdd > 150                                -> R.string.invalid_weight
            slot.basalPct < 32 || slot.basalPct > 37                      -> R.string.invalid_pct
            else                                                          -> null
        }

        else                      -> null
    }

    private fun compareProfiles() {
        val slots = state.value.slots
        slots.forEach { slot -> validate(slot)?.let { ToastUtils.warnToast(this, it); return } }

        getProfile(slots[0])?.let { profile0 ->
            getProfile(slots[1])?.let { profile1 ->
                ProfileViewerDialog().also { pvd ->
                    pvd.arguments = Bundle().also {
                        it.putLong("time", dateUtil.now())
                        it.putInt("mode", UiInteraction.Mode.PROFILE_COMPARE.ordinal)
                        it.putString("customProfile", profile0.jsonObject.toString())
                        it.putString("customProfile2", profile1.jsonObject.toString())
                        it.putString("customProfileName", profileName(slots[0]) + "\n" + profileName(slots[1]))
                    }
                }.show(supportFragmentManager, "ProfileViewDialog")
                return
            }
        }
        ToastUtils.warnToast(this, app.aaps.core.ui.R.string.invalid_input)
    }

    private fun getProfile(slot: ProfileSlot): PureProfile? =
        try { // Profile must not exist
            when (slot.kind) {
                ProfileKind.MOTOL_DEFAULT     -> defaultProfile.profile(slot.age, slot.tdd, slot.weight, profileFunction.getUnits())
                ProfileKind.DPV_DEFAULT       -> defaultProfileDPV.profile(slot.age, slot.tdd, slot.basalPct / 100.0, profileFunction.getUnits())
                ProfileKind.CURRENT           -> profileFunction.getProfile()?.convertToNonCustomizedProfile(dateUtil)
                ProfileKind.AVAILABLE_PROFILE -> activePlugin.activeProfileSource.profile?.getSpecificProfile(profileList[slot.availableIndex].toString())
                ProfileKind.PROFILE_SWITCH    -> ProfileSealed.EPS(value = profileSwitch[slot.profileSwitchIndex], activePlugin = null).convertToNonCustomizedProfile(dateUtil)
            }
        } catch (_: Exception) {
            null
        }

    private fun profileName(slot: ProfileSlot): String =
        when (slot.kind) {
            ProfileKind.MOTOL_DEFAULT     ->
                if (slot.tdd > 0) rh.gs(R.string.format_with_tdd, slot.age, slot.tdd) else rh.gs(R.string.format_with_weight, slot.age, slot.weight)

            ProfileKind.DPV_DEFAULT       -> rh.gs(R.string.format_with_tdd_and_pct, slot.age, slot.tdd, slot.basalPct.toInt())
            ProfileKind.CURRENT           -> profileFunction.getProfileName()
            ProfileKind.AVAILABLE_PROFILE -> profileList[slot.availableIndex].toString()
            ProfileKind.PROFILE_SWITCH    -> profileSwitch[slot.profileSwitchIndex].originalCustomizedName
        }

    override fun onPause() {
        super.onPause()
        disposable.clear()
    }
}

package app.aaps.plugins.aps.compose

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.events.EventOpenAPSUpdateGui
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Redesigned Algorithm screen (handoff Section 6), hosted in Compose. Presents the active APS's
 * parameters as first-class controls. Toggles write the same preference keys the settings screen uses;
 * "Advanced settings" opens the plugin's full preferences. No dosing logic changes.
 */
class AlgorithmFragment : DaggerFragment() {

    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy

    private val disposable = CompositeDisposable()
    private val state = mutableStateOf(AlgorithmUiState())

    private companion object {
        const val MGDL_PER_MMOL = 18.0182
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { AlgorithmScreen(state.value, onToggle = ::onToggle, onOpenSettings = ::openSettings) } }
        }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventOpenAPSUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ build() }, fabricPrivacy::logException)
        build()
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    private fun isHovorka() = activePlugin.activeAPS.javaClass.simpleName == "HovorkaMpcPlugin"

    private fun build() {
        val active = activePlugin.activeAPS as PluginBase
        val chips = activePlugin.getSpecificPluginsList(PluginType.APS).map { p ->
            AlgoChip(label = (p as PluginBase).name, active = p === active)
        }
        val rt = runCatching { activePlugin.activeAPS.lastAPSResult?.rawData() as? RT }.getOrNull()
        val toggles = if (isHovorka()) listOf(
            AlgoToggle("tdd", "Dynamic sensitivity", "Adapts basal to your recent days", preferences.get(BooleanKey.HovorkaTddAdaptation)),
            AlgoToggle("smb", "SMB microbolus", "Faster post-meal corrections (Objective 8)", preferences.get(BooleanKey.HovorkaEnableSmb)),
            AlgoToggle("meal", "Meal detection", "Catch unannounced carbs", preferences.get(BooleanKey.HovorkaMealDetection)),
            AlgoToggle("imm", "Absorption regime bank", "Adapts to fast/slow meals", preferences.get(BooleanKey.HovorkaImmBank))
        ) else emptyList()

        state.value = AlgorithmUiState(
            title = active.name,
            chips = chips,
            predictedMmol = rt?.eventualBG?.let { it / MGDL_PER_MMOL },
            targetMmol = rt?.targetBG?.let { it / MGDL_PER_MMOL },
            bodyWeight = if (isHovorka()) preferences.get(DoubleKey.HovorkaBodyWeight) else null,
            toggles = toggles
        )
    }

    private fun onToggle(id: String, on: Boolean) {
        when (id) {
            "tdd"  -> preferences.put(BooleanKey.HovorkaTddAdaptation, on)
            "smb"  -> preferences.put(BooleanKey.HovorkaEnableSmb, on)
            "meal" -> preferences.put(BooleanKey.HovorkaMealDetection, on)
            "imm"  -> preferences.put(BooleanKey.HovorkaImmBank, on)
        }
        build()
    }

    private fun openSettings() {
        val active = activePlugin.activeAPS as PluginBase
        startActivity(Intent(activity, uiInteraction.preferencesActivity).also {
            it.putExtra(UiInteraction.PLUGIN_NAME, active.javaClass.simpleName)
        })
    }
}

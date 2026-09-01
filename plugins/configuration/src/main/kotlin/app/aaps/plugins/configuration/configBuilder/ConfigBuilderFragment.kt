package app.aaps.plugins.configuration.configBuilder

import android.os.Bundle
import androidx.annotation.StringRes
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.configuration.ConfigBuilder
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.Translator
import app.aaps.plugins.configuration.configBuilder.compose.ConfigCategory
import app.aaps.plugins.configuration.configBuilder.compose.ConfigOption
import app.aaps.plugins.configuration.configBuilder.compose.ConfigScreen
import app.aaps.plugins.configuration.configBuilder.compose.ConfigSummary
import app.aaps.plugins.configuration.configBuilder.compose.ConfigToggle
import app.aaps.plugins.configuration.configBuilder.compose.ConfigUiState
import app.aaps.plugins.configuration.configBuilder.compose.PrefEntry
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.interfaces.protection.ProtectionCheck.Protection.PREFERENCES
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.configBuilder.events.EventConfigBuilderUpdateGui
import app.aaps.plugins.configuration.databinding.ConfigbuilderFragmentBinding
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class ConfigBuilderFragment : DaggerFragment() {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var configBuilder: ConfigBuilder
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var protectionCheck: ProtectionCheck
    @Inject lateinit var config: Config
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var loop: Loop
    @Inject lateinit var translator: Translator
    @Inject lateinit var rh: ResourceHelper

    private var disposable: CompositeDisposable = CompositeDisposable()
    private val pluginViewHolders = ArrayList<ConfigBuilder.PluginViewHolderInterface>()
    // ---- Redesigned Config Builder (Compose overlay) ----
    private val configState = mutableStateOf(ConfigUiState())
    private var generalPlugins: List<PluginBase> = emptyList()
    private var prefPlugins: List<PluginBase> = emptyList()
    private var selectablePlugins: List<Pair<PluginBase, PluginType>> = emptyList()
    private var inMenu = false
    private var queryingProtection = false
    private var _binding: ConfigbuilderFragmentBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ConfigbuilderFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val parentClass = this.activity?.let { it::class.java }
        inMenu = parentClass == uiInteraction.singleFragmentActivity
        updateProtectedUi()
        binding.unlock.setOnClickListener { queryProtection() }

        binding.composeConfig.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeConfig.setContent {
            AapsTheme { ConfigScreen(state = configState.value, onToggle = ::onPluginToggle, onOpenPrefs = ::onOpenPrefs, onSelect = ::onPluginSelect) }
        }
        buildConfigState()
    }

    private fun onOpenPrefs(index: Int) {
        val plugin = prefPlugins.getOrNull(index) ?: return
        startActivity(android.content.Intent(activity, uiInteraction.preferencesActivity).also {
            it.putExtra(UiInteraction.PLUGIN_NAME, plugin.javaClass.simpleName)
        })
    }

    private fun buildConfigState() {
        val summary = buildList {
            (activePlugin.activeBgSource as? PluginBase)?.let { add(ConfigSummary("CGM", it.name, true)) }
            (activePlugin.activePump as? PluginBase)?.let { add(ConfigSummary("Pump", it.name, it.isEnabled())) }
            if (config.APS) (activePlugin.activeAPS as? PluginBase)?.let { add(ConfigSummary("Algorithm", it.name, it.isEnabled())) }
            add(ConfigSummary("Loop", translator.translate(loop.runningMode), loop.runningMode.isClosedLoopOrLgs()))
        }
        // The categories you CHOOSE a plugin in -- pump, algorithm, BG source. The legacy views for these
        // are still built into `binding.categories` below, but the Compose overlay covers them completely,
        // so without this there is no way to change the active pump at all. Mirrors updateGUI()'s list and
        // its config conditions exactly; drift here means a category silently disappears from the UI.
        val selectable = mutableListOf<Pair<PluginBase, PluginType>>()
        val categories = mutableListOf<ConfigCategory>()

        fun category(@StringRes title: Int, @StringRes description: Int, type: PluginType) {
            val plugins = activePlugin.getSpecificPluginsVisibleInList(type)
            if (plugins.isEmpty()) return
            val options = plugins.map { p ->
                val index = selectable.size
                selectable += p to type
                ConfigOption(index, p.name, p.description ?: "", p.isEnabled(type), p.pluginDescription.alwaysEnabled)
            }
            categories += ConfigCategory(rh.gs(title), rh.gs(description), configBuilder.areMultipleSelectionsAllowed(type), options)
        }

        category(R.string.configbuilder_profile, R.string.configbuilder_profile_description, PluginType.PROFILE)
        if (config.APS || config.PUMPCONTROL || config.isEngineeringMode())
            category(app.aaps.core.ui.R.string.configbuilder_insulin, R.string.configbuilder_insulin_description, PluginType.INSULIN)
        if (!config.AAPSCLIENT) {
            category(R.string.configbuilder_bgsource, R.string.configbuilder_bgsource_description, PluginType.BGSOURCE)
            category(R.string.configbuilder_smoothing, R.string.configbuilder_smoothing_description, PluginType.SMOOTHING)
            category(R.string.configbuilder_pump, R.string.configbuilder_pump_description, PluginType.PUMP)
        }
        if (config.APS || config.PUMPCONTROL || config.isEngineeringMode())
            category(R.string.configbuilder_sensitivity, R.string.configbuilder_sensitivity_description, PluginType.SENSITIVITY)
        if (config.APS) {
            category(R.string.configbuilder_aps, R.string.configbuilder_aps_description, PluginType.APS)
            category(R.string.configbuilder_loop, R.string.configbuilder_loop_description, PluginType.LOOP)
            category(app.aaps.core.ui.R.string.constraints, R.string.configbuilder_constraints_description, PluginType.CONSTRAINTS)
        }
        category(R.string.configbuilder_sync, R.string.configbuilder_sync_description, PluginType.SYNC)
        selectablePlugins = selectable

        generalPlugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.GENERAL)
        val plugins = generalPlugins.mapIndexed { i, p -> ConfigToggle(i, p.name, "", p.isEnabled(PluginType.GENERAL)) }

        // Plugins that expose a settings screen → grouped, tappable rows opening their (search-enabled) prefs.
        prefPlugins = activePlugin.getPluginsList().filter {
            it.preferencesId != PluginDescription.PREFERENCE_NONE && it.pluginDescription.pluginName != -1 && !it.pluginDescription.neverVisible
        }
        val prefs = prefPlugins.mapIndexed { i, p -> PrefEntry(i, p.name, groupLabel(p.getType())) }

        configState.value = ConfigUiState(summary, categories, plugins, prefs)
    }

    private fun groupLabel(type: PluginType): String = when (type) {
        PluginType.GENERAL     -> "General"
        PluginType.LOOP, PluginType.APS, PluginType.CONSTRAINTS, PluginType.SENSITIVITY -> "Loop & algorithm"
        PluginType.PUMP        -> "Pump"
        PluginType.BGSOURCE, PluginType.SMOOTHING -> "CGM & data"
        PluginType.INSULIN     -> "Insulin"
        PluginType.PROFILE     -> "Profile"
        PluginType.SYNC        -> "Connections & sync"
    }

    private fun onPluginSelect(index: Int, enabled: Boolean) {
        val (plugin, type) = selectablePlugins.getOrNull(index) ?: return
        if (plugin.pluginDescription.alwaysEnabled) return
        // In an exclusive category the current choice cannot simply be switched off -- that would leave
        // the loop with no pump or no algorithm at all. Only a different option can replace it.
        if (!configBuilder.areMultipleSelectionsAllowed(type) && !enabled) return
        // switchAllowed, not performPluginSwitch: it is what asks before handing the loop a hardware pump,
        // and it reconnects afterwards. The confirmation is async, so the redraw comes from the
        // EventConfigBuilderUpdateGui that performPluginSwitch sends.
        configBuilder.switchAllowed(plugin, enabled, requireActivity(), type)
        buildConfigState()
    }

    private fun onPluginToggle(index: Int, enabled: Boolean) {
        val plugin = generalPlugins.getOrNull(index) ?: return
        configBuilder.performPluginSwitch(plugin, enabled, PluginType.GENERAL)
        buildConfigState()
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        if (inMenu) queryProtection() else updateProtectedUi()
        disposable += rxBus
            .toObservable(EventConfigBuilderUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({
                           for (pluginViewHolder in pluginViewHolders) pluginViewHolder.update(this.requireActivity())
                           if (_binding != null) buildConfigState()
                       }, fabricPrivacy::logException)
        updateGUI()
        if (_binding != null) buildConfigState()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @Synchronized
    private fun updateGUI() {
        binding.categories.removeAllViews()
        configBuilder.createViewsForPlugins(
            title = R.string.configbuilder_profile,
            description = R.string.configbuilder_profile_description,
            pluginType = PluginType.PROFILE,
            plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.PROFILE),
            pluginViewHolders = pluginViewHolders,
            activity = requireActivity(),
            parent = binding.categories
        )
        if (config.APS || config.PUMPCONTROL || config.isEngineeringMode())
            configBuilder.createViewsForPlugins(
                title = app.aaps.core.ui.R.string.configbuilder_insulin,
                description = R.string.configbuilder_insulin_description,
                pluginType = PluginType.INSULIN,
                plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.INSULIN),
                pluginViewHolders = pluginViewHolders,
                activity = requireActivity(),
                parent = binding.categories
            )
        if (!config.AAPSCLIENT) {
            configBuilder.createViewsForPlugins(
                title = R.string.configbuilder_bgsource,
                description = R.string.configbuilder_bgsource_description,
                pluginType = PluginType.BGSOURCE,
                plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.BGSOURCE),
                pluginViewHolders = pluginViewHolders,
                activity = requireActivity(),
                parent = binding.categories
            )
            configBuilder.createViewsForPlugins(
                title = R.string.configbuilder_smoothing,
                description = R.string.configbuilder_smoothing_description,
                pluginType = PluginType.SMOOTHING,
                plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.SMOOTHING),
                pluginViewHolders = pluginViewHolders,
                activity = requireActivity(),
                parent = binding.categories
            )
            configBuilder.createViewsForPlugins(
                title = R.string.configbuilder_pump,
                description = R.string.configbuilder_pump_description,
                pluginType = PluginType.PUMP,
                plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.PUMP),
                pluginViewHolders = pluginViewHolders,
                activity = requireActivity(),
                parent = binding.categories
            )
        }
        if (config.APS || config.PUMPCONTROL || config.isEngineeringMode())
            configBuilder.createViewsForPlugins(
                title = R.string.configbuilder_sensitivity,
                description = R.string.configbuilder_sensitivity_description,
                pluginType = PluginType.SENSITIVITY,
                plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.SENSITIVITY),
                pluginViewHolders = pluginViewHolders,
                activity = requireActivity(),
                parent = binding.categories
            )
        if (config.APS) {
            configBuilder.createViewsForPlugins(
                title = R.string.configbuilder_aps,
                description = R.string.configbuilder_aps_description,
                pluginType = PluginType.APS,
                plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.APS),
                pluginViewHolders = pluginViewHolders,
                activity = requireActivity(),
                parent = binding.categories
            )
            configBuilder.createViewsForPlugins(
                title = R.string.configbuilder_loop,
                description = R.string.configbuilder_loop_description,
                pluginType = PluginType.LOOP,
                plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.LOOP),
                pluginViewHolders = pluginViewHolders,
                activity = requireActivity(),
                parent = binding.categories
            )
            configBuilder.createViewsForPlugins(
                title = app.aaps.core.ui.R.string.constraints,
                description = R.string.configbuilder_constraints_description,
                pluginType = PluginType.CONSTRAINTS,
                plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.CONSTRAINTS),
                pluginViewHolders = pluginViewHolders,
                activity = requireActivity(),
                parent = binding.categories
            )
        }
        configBuilder.createViewsForPlugins(
            title = R.string.configbuilder_sync,
            description = R.string.configbuilder_sync_description,
            pluginType = PluginType.SYNC,
            plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.SYNC),
            pluginViewHolders = pluginViewHolders,
            activity = requireActivity(),
            parent = binding.categories
        )
        configBuilder.createViewsForPlugins(
            title = R.string.configbuilder_general,
            description = R.string.configbuilder_general_description,
            pluginType = PluginType.GENERAL,
            plugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.GENERAL),
            pluginViewHolders = pluginViewHolders,
            activity = requireActivity(),
            parent = binding.categories
        )
    }

    private fun updateProtectedUi() {
        val isLocked = protectionCheck.isLocked(PREFERENCES)
        binding.mainLayout.visibility = isLocked.not().toVisibility()
        // The Compose overlay is a sibling of mainLayout, not a child, so it has to be hidden too --
        // otherwise it keeps drawing over the unlock button and now offers pump selection while locked.
        binding.composeConfig.visibility = isLocked.not().toVisibility()
        binding.unlock.visibility = isLocked.toVisibility()
    }

    private fun queryProtection() {
        val isLocked = protectionCheck.isLocked(PREFERENCES)
        if (isLocked && !queryingProtection) {
            activity?.let { activity ->
                queryingProtection = true
                val doUpdate = { activity.runOnUiThread { queryingProtection = false; if (_binding != null) updateProtectedUi() } }
                protectionCheck.queryProtection(activity, PREFERENCES, doUpdate, doUpdate, doUpdate)
            }
        }
    }
}

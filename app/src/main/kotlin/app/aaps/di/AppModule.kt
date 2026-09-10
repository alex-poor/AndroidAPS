package app.aaps.di

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import app.aaps.MainApp
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.plugins.aps.openAPSSMB.OpenAPSSMBPlugin
import app.aaps.plugins.configuration.configBuilder.ConfigBuilderPlugin
import app.aaps.plugins.constraints.safety.SafetyPlugin
import app.aaps.plugins.insulin.InsulinOrefRapidActingPlugin
import app.aaps.plugins.main.general.overview.OverviewPlugin
import app.aaps.plugins.main.general.persistentNotification.PersistentNotificationPlugin
import app.aaps.plugins.main.general.themes.ThemeSwitcherPlugin
import app.aaps.plugins.main.iob.iobCobCalculator.IobCobCalculatorPlugin
import app.aaps.plugins.main.profile.ProfilePlugin
import app.aaps.plugins.sensitivity.SensitivityOref1Plugin
import app.aaps.plugins.smoothing.NoSmoothingPlugin
import app.aaps.plugins.source.XdripSourcePlugin
import app.aaps.plugins.sync.swarm.SwarmPlugin
import app.aaps.pump.virtual.VirtualPumpPlugin
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.implementations.ConfigImpl
import app.aaps.implementations.UiInteractionImpl
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.android.HasAndroidInjector

@Suppress("unused")
@Module(
    includes = [
        AppModule.AppBindings::class,
        AppModule.Provide::class
    ]
)
open class AppModule {

    @Provides
    fun providesPlugins(
        config: Config,
        @PluginsListModule.AllConfigs allConfigs: Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards PluginBase>,
        @PluginsListModule.PumpDriver pumpDrivers: Lazy<Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards PluginBase>>,
        @PluginsListModule.NotNSClient notNsClient: Lazy<Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards PluginBase>>,
        @PluginsListModule.APS aps: Lazy<Map<@JvmSuppressWildcards Int, @JvmSuppressWildcards PluginBase>>,
        //@PluginsListModule.Unfinished unfinished: Lazy<Map<@JvmSuppressWildcards Int,  @JvmSuppressWildcards PluginBase>>
    )
        : List<@JvmSuppressWildcards PluginBase> {
        val plugins = allConfigs.toMutableMap()
        if (config.PUMPDRIVERS) plugins += pumpDrivers.get()
        if (config.APS) plugins += aps.get()
        if (!config.AAPSCLIENT) plugins += notNsClient.get()
        //if (config.isUnfinishedMode()) plugins += unfinished.get()
        val list = plugins.toList().sortedBy { it.first }.map { it.second }
        return if (config.AAPSCLIENT) asFollower(list) else list
    }

    /**
     * Strip the app down to one job: showing somebody else's glucose.
     *
     * **IN THIS FORK THE `aapsclient` FLAVOUR IS THE DIASWARM FOLLOWER**, and
     * nothing else. It is a separate `applicationId` that installs alongside
     * the pump app, has no pump drivers compiled in, and exists so a person can
     * watch a trend line somebody shared with them. Everything a loop needs and
     * a follower does not — choosing a CGM, setting up a pump, editing a
     * profile, logging carbs — is not merely unhelpful here, it is an invitation
     * to configure things that do nothing, or worse, to believe this app is
     * theirs to dose from.
     *
     * **NOTHING IS DELETED THAT A CATEGORY DEPENDS ON.** `verifySelectionInCategories`
     * calls `getDefaultPlugin` for APS, INSULIN, SENSITIVITY, SMOOTHING,
     * PROFILE, BGSOURCE and PUMP, and that throws `IllegalStateException` when a
     * category has no default — taking the whole app down at construction. This
     * fork has already shipped that crash once, from removing the plugin that
     * carried `.setDefault()` for BGSOURCE. So each of those categories keeps
     * exactly one plugin, and it is hidden rather than dropped.
     *
     * Hiding is done here, on the description, rather than in each plugin: a
     * flag per plugin would be fifteen edits to the loop app's own source for
     * the sake of a follower, and the standing rule on this work is that the
     * rest of that app stays untouched. `showInList` removes the tab;
     * `neverVisible` removes it from the Config Builder and its settings with it.
     *
     * THIS RUNS ONLY WHEN `config.AAPSCLIENT`. The full and pumpcontrol builds
     * never reach it, so the APK that drives the pump is bit-for-bit unaffected.
     */
    private fun asFollower(all: List<PluginBase>): List<PluginBase> {
        // Seen by the person using it. Overview is the graph; Swarm is how they
        // follow somebody. There is nothing else to show them.
        val visible = setOf(OverviewPlugin::class.java, SwarmPlugin::class.java)

        // Needed for the app to construct or for the graph to be drawn, and for
        // no other reason. Each PluginType above needs one of these.
        val required = setOf(
            OverviewPlugin::class.java,          // the graph
            SwarmPlugin::class.java,             // the readings
            IobCobCalculatorPlugin::class.java,  // buildChartData asks it for basal and bucketed data
            ConfigBuilderPlugin::class.java,     // loads every other plugin's enabled/visible state
            ThemeSwitcherPlugin::class.java,
            PersistentNotificationPlugin::class.java, // glucose on the lock screen: a follower wants this
            SafetyPlugin::class.java,            // CONSTRAINTS; asked for on paths shared with the loop
            OpenAPSSMBPlugin::class.java,        // PluginType.APS default
            InsulinOrefRapidActingPlugin::class.java, // INSULIN default
            SensitivityOref1Plugin::class.java,  // SENSITIVITY default
            NoSmoothingPlugin::class.java,       // SMOOTHING default
            ProfilePlugin::class.java,           // PROFILE default — the follower's own profile is
                                                 // never edited; SwarmFollowerBg applies the
                                                 // SUBJECT's, which is what the graph is drawn against
            XdripSourcePlugin::class.java,       // BGSOURCE default, and inert until configured
            VirtualPumpPlugin::class.java        // PUMP default; there is no pump
        )

        return all.filter { it::class.java in required }.onEach { plugin ->
            if (plugin::class.java !in visible) {
                plugin.pluginDescription.showInList = { false }
                plugin.pluginDescription.neverVisible = true
            }
        }
    }

    @Module
    open class Provide {

        @Reusable
        @Provides
        fun providesDefaultSharedPreferences(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    }

    @Module
    interface AppBindings {

        @Binds fun bindContext(mainApp: MainApp): Context
        @Binds fun bindInjector(mainApp: MainApp): HasAndroidInjector
        @Binds fun bindConfigInterface(config: ConfigImpl): Config

        @Binds fun bindActivityNames(activityNames: UiInteractionImpl): UiInteraction
    }
}


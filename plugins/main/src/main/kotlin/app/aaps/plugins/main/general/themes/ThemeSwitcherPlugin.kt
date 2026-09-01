package app.aaps.plugins.main.general.themes

import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
import app.aaps.core.compose.theme.AapsSkinState
import app.aaps.core.compose.theme.AapsSkins
import app.aaps.core.compose.theme.AapsUiMode
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventThemeSwitch
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.main.R
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeSwitcherPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    private val preferences: Preferences,
    private val rxBus: RxBus,
) : PluginBase(
    PluginDescription()
        .mainType(PluginType.GENERAL)
        .neverVisible(true)
        .alwaysEnabled(true)
        .showInList { false }
        .pluginName(R.string.theme_switcher),
    aapsLogger, rh
) {

    private val disposable = CompositeDisposable()

    override fun onStart() {
        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .subscribe {
                if (it.isChanged(StringKey.GeneralDarkMode.key)) {
                    setThemeMode()
                    rxBus.send(EventThemeSwitch())
                }
                // A skin only reaches the Compose surfaces, which repaint from snapshot state — no
                // EventThemeSwitch, because nothing needs to be recreated for it.
                if (it.isChanged(StringKey.GeneralSkin.key)) setSkin()
            }
    }

    /**
     * Applies the light/dark choice to BOTH halves of the app: [AppCompatDelegate] for the XML
     * screens, and [AapsSkinState] for the redesigned Compose ones, which have their own theme and
     * would otherwise stay dark whatever the user picked.
     */
    fun setThemeMode() {
        val stored = try {
            preferences.get(StringKey.GeneralDarkMode)
        } catch (ignored: Exception) {
            rh.gs(R.string.value_system_theme)
        }
        AppCompatDelegate.setDefaultNightMode(
            when (stored) {
                rh.gs(R.string.value_dark_theme)  -> MODE_NIGHT_YES
                rh.gs(R.string.value_light_theme) -> MODE_NIGHT_NO
                else                              -> MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        AapsSkinState.mode = AapsUiMode.fromString(stored)
        setSkin()
    }

    /** Compose-only. An unknown id (including a leftover from the retired layout skins) falls back to the default. */
    fun setSkin() {
        AapsSkinState.skinId = try {
            preferences.get(StringKey.GeneralSkin)
        } catch (ignored: Exception) {
            AapsSkins.Default.id
        }
    }

    override fun onStop() {
        disposable.dispose()
    }
}

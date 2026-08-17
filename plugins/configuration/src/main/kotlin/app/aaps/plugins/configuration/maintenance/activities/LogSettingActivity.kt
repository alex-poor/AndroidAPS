package app.aaps.plugins.configuration.maintenance.activities

import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.configuration.maintenance.compose.LogSettingsScreen
import app.aaps.plugins.configuration.maintenance.compose.LogToggle
import javax.inject.Inject

/**
 * Which log categories AAPS writes. UI is Compose ([LogSettingsScreen]); the toggles still call
 * straight through to [L], which is the single source of truth for what is enabled.
 */
class LogSettingActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var l: L
    @Inject lateinit var rh: ResourceHelper

    private val elements = mutableStateOf<List<LogToggle>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        refresh()
        setContentView(ComposeView(this).apply {
            setContent {
                AapsTheme {
                    LogSettingsScreen(
                        elements = elements.value,
                        onToggle = { name, enabled ->
                            l.logElements().firstOrNull { it.name == name }?.enable(enabled)
                            refresh()
                        },
                        onReset = {
                            l.resetToDefaults()
                            refresh()
                        },
                        onBack = { finish() }
                    )
                }
            }
        })
    }

    private fun refresh() {
        elements.value = l.logElements().map { LogToggle(it.name, it.enabled) }
    }
}

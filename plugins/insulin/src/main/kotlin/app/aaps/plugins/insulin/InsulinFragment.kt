package app.aaps.plugins.insulin

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.insulin.compose.InsulinChip
import app.aaps.plugins.insulin.compose.InsulinScreen
import app.aaps.plugins.insulin.compose.InsulinUiState
import dagger.android.support.DaggerFragment
import javax.inject.Inject

class InsulinFragment : DaggerFragment() {

    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var rh: ResourceHelper

    private val state = mutableStateOf(InsulinUiState())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { InsulinScreen(state.value) } }
        }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        val active = activePlugin.activeInsulin
        val chips = activePlugin.getSpecificPluginsList(PluginType.INSULIN).map { p ->
            InsulinChip(label = (p as PluginBase).name, active = p === active)
        }
        state.value = InsulinUiState(
            types = chips,
            activeName = active.friendlyName,
            comment = active.comment,
            diaHours = active.dia,
            peakMinutes = active.peak
        )
    }
}

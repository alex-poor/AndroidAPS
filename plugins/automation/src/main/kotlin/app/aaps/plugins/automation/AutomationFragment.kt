package app.aaps.plugins.automation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.MenuCompat
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventWearUpdateTiles
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.automation.compose.AutomationRule
import app.aaps.plugins.automation.compose.AutomationScreen
import app.aaps.plugins.automation.compose.AutomationUiState
import app.aaps.plugins.automation.dialogs.EditEventDialog
import app.aaps.plugins.automation.events.EventAutomationDataChanged
import app.aaps.plugins.automation.events.EventAutomationUpdateGui
import app.aaps.plugins.automation.triggers.Trigger
import app.aaps.plugins.automation.triggers.TriggerConnector
import dagger.android.HasAndroidInjector
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Redesigned Automation screen (handoff Section 6): "When … then …" rule cards hosted in Compose.
 * All mutations reuse the existing automation paths — [EditEventDialog] for add/edit,
 * `automationPlugin` for toggle/remove — so no automation logic changes.
 */
class AutomationFragment : DaggerFragment(), MenuProvider {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var automationPlugin: AutomationPlugin
    @Inject lateinit var injector: HasAndroidInjector
    @Inject lateinit var uel: UserEntryLogger

    companion object {

        const val ID_MENU_ADD = 504
        const val ID_MENU_RUN = 505
    }

    private var disposable: CompositeDisposable = CompositeDisposable()
    private val state = mutableStateOf(AutomationUiState())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    AutomationScreen(
                        state = state.value,
                        onAdd = ::add,
                        onEdit = ::edit,
                        onToggle = ::toggle,
                        onRemove = ::remove
                    )
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.FIRST, ID_MENU_ADD, 0, rh.gs(R.string.add_automation)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.FIRST, ID_MENU_RUN, 0, rh.gs(R.string.run_automations)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            ID_MENU_RUN -> { Thread { automationPlugin.processActions() }.start(); true }
            ID_MENU_ADD -> { add(); true }
            else        -> false
        }

    @Synchronized
    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventAutomationUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ build() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventAutomationDataChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ build(); rxBus.send(EventWearUpdateTiles()) }, fabricPrivacy::logException)
        build()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    private fun build() {
        val rules = (0 until automationPlugin.size()).map { pos ->
            val a = automationPlugin.at(pos)
            AutomationRule(
                position = pos,
                title = a.title,
                enabled = a.isEnabled,
                readOnly = a.readOnly,
                system = a.systemAction,
                whenChips = collectTriggerText(a.trigger),
                thenChips = a.actions.map { plain(it.shortDescription()) }.filter { it.isNotBlank() }
            )
        }
        state.value = AutomationUiState(rules)
    }

    private fun collectTriggerText(trigger: Trigger): List<String> {
        val out = ArrayList<String>()
        fun walk(t: Trigger) {
            if (t is TriggerConnector) t.list.forEach { walk(it) }
            else plain(t.friendlyDescription()).takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        walk(trigger)
        return out
    }

    private fun plain(html: String): String = HtmlHelper.fromHtml(html).toString().trim()

    private fun toggle(position: Int, enabled: Boolean) {
        automationPlugin.at(position).isEnabled = enabled
        rxBus.send(EventAutomationDataChanged())
    }

    private fun edit(position: Int) {
        val automation = automationPlugin.at(position)
        EditEventDialog().also {
            it.arguments = Bundle().apply {
                putString("event", automation.toJSON())
                putInt("position", position)
            }
        }.show(childFragmentManager, "EditEventDialog")
    }

    private fun add() {
        EditEventDialog().also {
            it.arguments = Bundle().apply {
                putString("event", AutomationEventObject(injector).toJSON())
                putInt("position", -1)
            }
        }.show(childFragmentManager, "EditEventDialog")
    }

    private fun remove(position: Int) {
        val event = automationPlugin.at(position)
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.removerecord), event.title, Runnable {
                uel.log(Action.AUTOMATION_REMOVED, Sources.Automation, event.title)
                automationPlugin.remove(event)
                rxBus.send(EventAutomationDataChanged())
            })
        }
    }
}

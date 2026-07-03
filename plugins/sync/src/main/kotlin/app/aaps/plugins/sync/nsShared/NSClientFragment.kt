package app.aaps.plugins.sync.nsShared

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.text.toSpanned
import androidx.core.view.MenuCompat
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginFragment
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientRestart
import app.aaps.core.interfaces.sync.NsClient
import app.aaps.core.interfaces.sync.Sync
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.nsShared.compose.ConnCard
import app.aaps.plugins.sync.nsShared.compose.ConnectivityScreen
import app.aaps.plugins.sync.nsShared.compose.ConnectivityUiState
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiQueue
import app.aaps.plugins.sync.nsShared.events.EventNSClientUpdateGuiStatus
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Redesigned Connectivity & sync screen (handoff Section 7), hosted in Compose. Read-only status cards
 * over the active BG source + sync plugins; the log lives one level deeper (kept behind the menu). All
 * sync actions (restart, send now, full sync) reuse the existing paths.
 */
class NSClientFragment : DaggerFragment(), MenuProvider, PluginFragment {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var config: Config
    @Inject lateinit var persistenceLayer: PersistenceLayer

    companion object {

        const val ID_MENU_RESTART = 508
        const val ID_MENU_SEND_NOW = 509
        const val ID_MENU_FULL_SYNC = 510
    }

    override var plugin: PluginBase? = null
    private val nsClientPlugin get() = activePlugin.activeNsClient

    private val disposable = CompositeDisposable()
    private val state = mutableStateOf(ConnectivityUiState())
    private var syncPlugins: List<PluginBase> = emptyList()
    private val handler = Handler(HandlerThread(this::class.simpleName + "Handler").also { it.start() }.looper)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { ConnectivityScreen(state.value, onCard = ::onCard) } }
            requireActivity().addMenuProvider(this@NSClientFragment, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        menu.add(Menu.FIRST, ID_MENU_RESTART, 0, rh.gs(R.string.restart)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.FIRST, ID_MENU_SEND_NOW, 0, rh.gs(R.string.deliver_now)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.FIRST, ID_MENU_FULL_SYNC, 0, rh.gs(R.string.full_sync)).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        MenuCompat.setGroupDividerEnabled(menu, true)
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            ID_MENU_RESTART   -> { rxBus.send(EventNSClientRestart()); true }
            ID_MENU_SEND_NOW  -> { handler.post { nsClientPlugin?.resend("GUI") }; true }
            ID_MENU_FULL_SYNC -> { fullSync(); true }
            else              -> false
        }

    private fun fullSync() {
        var result = ""
        context?.let { context ->
            OKDialog.showConfirmation(
                context, rh.gs(R.string.ns_client), rh.gs(R.string.full_sync_comment),
                {
                    OKDialog.showConfirmation(requireContext(), rh.gs(R.string.ns_client), rh.gs(app.aaps.core.ui.R.string.cleanup_db_confirm_sync), {
                        disposable += Completable.fromAction { result = persistenceLayer.cleanupDatabase(93, deleteTrackedChanges = true) }
                            .subscribeOn(aapsSchedulers.io)
                            .observeOn(aapsSchedulers.main)
                            .subscribeBy(
                                onError = { aapsLogger.error("Error cleaning up databases", it) },
                                onComplete = {
                                    if (result.isNotEmpty())
                                        OKDialog.show(
                                            requireContext(),
                                            rh.gs(app.aaps.core.ui.R.string.result),
                                            HtmlHelper.fromHtml("<b>" + rh.gs(app.aaps.core.ui.R.string.cleared_entries) + "</b><br>" + result).toSpanned()
                                        )
                                    aapsLogger.info(LTag.CORE, "Cleaned up databases with result: $result")
                                    handler.post {
                                        nsClientPlugin?.resetToFullSync()
                                        nsClientPlugin?.resend("FULL_SYNC")
                                    }
                                }
                            )
                        uel.log(action = Action.CLEANUP_DATABASES, source = Sources.NSClient)
                    }, {
                        handler.post {
                            nsClientPlugin?.resetToFullSync()
                            nsClientPlugin?.resend("FULL_SYNC")
                        }
                    })
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventNSClientUpdateGuiQueue::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ build() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventNSClientUpdateGuiStatus::class.java)
            .debounce(3L, TimeUnit.SECONDS)
            .observeOn(aapsSchedulers.main)
            .subscribe({ build() }, fabricPrivacy::logException)
        build()
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        handler.looper.quitSafely()
    }

    private fun build() {
        val cgm = (activePlugin.activeBgSource as? PluginBase)?.name ?: "CGM"
        syncPlugins = activePlugin.getSpecificPluginsVisibleInList(PluginType.SYNC).filter { it.isEnabled() }
        val cards = ArrayList<ConnCard>()
        cards.add(ConnCard("cgm", cgm, "Receiving glucose", 0, tappable = false))
        syncPlugins.forEachIndexed { i, p ->
            val sync = p as? Sync
            var sub = sync?.status?.trim().takeUnless { it.isNullOrBlank() } ?: "Enabled"
            if (p is NsClient) {
                val q = p.dataSyncSelector.queueSize()
                if (q > 0) sub = "$sub · $q queued"
            }
            val level = when {
                sync == null   -> 0
                sync.connected -> 0
                else           -> 1
            }
            cards.add(ConnCard(if (p is NsClient) "ns" else "sync:$i", p.name, sub, level, tappable = p is NsClient))
        }
        val cloud = syncPlugins.firstOrNull { it is NsClient }?.name ?: "Cloud"
        state.value = ConnectivityUiState(cgmName = cgm, cloudName = cloud, connections = cards)
    }

    private fun onCard(id: String) {
        if (id == "ns") handler.post { nsClientPlugin?.resend("GUI") }
    }
}

package app.aaps.ui.dialogs

import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissBolusProgressIfRunning
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.rx.events.EventOverviewBolusStopDeliveryEnabled
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.ui.dialogs.compose.BolusProgressSheet
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Live bolus-delivery progress dialog. UI is Compose ([BolusProgressSheet]) — the EXACT rxBus
 * progress/dismiss/stop-enable lifecycle, the [BolusProgressData] state, the cancel path
 * (`commandQueue.cancelAllBoluses`) and the show/dismiss contract are unchanged from the legacy
 * XML dialog; only the rendering was swapped. Compose state ([percent]/[status]/[stopEnabled])
 * is what the rxBus subscribers now update instead of the old view binding.
 */
class BolusProgressDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var uel: UserEntryLogger

    private val disposable = CompositeDisposable()

    private var running = true
    private var helpActivity: TranslatedDaggerAppCompatActivity? = null

    // Compose-backed view state — updated by the rxBus subscribers below (replaces the XML binding).
    private var percent by mutableIntStateOf(0)
    private var status by mutableStateOf("")
    private var stopEnabled by mutableStateOf(true)
    private var stopVisible by mutableStateOf(true)

    fun setHelperActivity(activity: TranslatedDaggerAppCompatActivity): BolusProgressDialog {
        helpActivity = activity
        return this
    }

    private fun onStopClicked() {
        aapsLogger.debug(LTag.UI, "Stop bolus delivery button pressed")
        BolusProgressData.stopPressed = true
        stopVisible = false
        uel.log(Action.CANCEL_BOLUS, Sources.Overview, BolusProgressData.status)
        commandQueue.cancelAllBoluses(BolusProgressData.id)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = false
        dialog?.setCanceledOnTouchOutside(false)

        status = BolusProgressData.status
        percent = BolusProgressData.percent
        stopEnabled = true
        stopVisible = true
        BolusProgressData.stopPressed = false

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    BolusProgressSheet(
                        percent = percent,
                        status = status,
                        onStop = { if (stopEnabled && stopVisible) onStopClicked() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.BOTTOM)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onResume() {
        super.onResume()
        aapsLogger.debug(LTag.UI, "onResume")
        if (!commandQueue.bolusInQueue())
            BolusProgressData.bolusEnded = true

        if (BolusProgressData.bolusEnded) dismiss()
        else running = true

        disposable += rxBus
            .toObservable(EventPumpStatusChanged::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe { status = it.getStatus(requireContext()) }
        disposable += rxBus
            .toObservable(EventDismissBolusProgressIfRunning::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                aapsLogger.debug(LTag.PUMP, "Running id $id. Close request id  ${it.id}")
                if (it.id == null || it.id == BolusProgressData.id)
                    if (running) dismiss()
            }
        disposable += rxBus
            .toObservable(EventOverviewBolusProgress::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                aapsLogger.debug(LTag.UI, "Status: ${BolusProgressData.status} Percent: ${BolusProgressData.percent}")
                status = BolusProgressData.status
                percent = BolusProgressData.percent
                if (BolusProgressData.percent == 100) {
                    stopVisible = false
                    scheduleDismiss()
                }
            }
        disposable += rxBus
            .toObservable(EventOverviewBolusStopDeliveryEnabled::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe {
                aapsLogger.debug(LTag.UI, "StopDeliveryButton enabled=${it.isEnabled}")
                stopEnabled = it.isEnabled
            }
    }

    override fun dismiss() {
        aapsLogger.debug(LTag.UI, "dismiss")
        try {
            super.dismiss()
        } catch (e: IllegalStateException) {
            // dialog not running yet. onResume will try again. Set bolusEnded to make extra
            // sure onResume will catch this
            BolusProgressData.bolusEnded = true
            aapsLogger.error("Unhandled exception", e)
        }
        // Reset stop button
        BolusProgressData.stopPressed = false
        helpActivity?.finish()
    }

    override fun onPause() {
        super.onPause()
        aapsLogger.debug(LTag.UI, "onPause")
        running = false
        disposable.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private fun scheduleDismiss() {
        aapsLogger.debug(LTag.UI, "scheduleDismiss")
        Thread {
            SystemClock.sleep(5000)
            BolusProgressData.bolusEnded = true
            activity?.runOnUiThread {
                if (running) {
                    aapsLogger.debug(LTag.UI, "executing")
                    try {
                        dismiss()
                    } catch (e: Exception) {
                        aapsLogger.error("Unhandled exception", e)
                    }
                }
            }
        }.start()
    }
}

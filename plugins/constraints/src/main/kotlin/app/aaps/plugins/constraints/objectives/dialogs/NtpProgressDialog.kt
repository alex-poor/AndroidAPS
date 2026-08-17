package app.aaps.plugins.constraints.objectives.dialogs

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNtpStatus
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.plugins.constraints.objectives.compose.NtpProgressSheet
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

class NtpProgressDialog : DaggerDialogFragment() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var aapsSchedulers: AapsSchedulers

    private val disposable = CompositeDisposable()

    private var state: String? = null
    private var percent = 0

    private val statusText = mutableStateOf("")
    private val progress = mutableIntStateOf(0)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        isCancelable = false

        state = savedInstanceState?.getString("state", null)
        percent = savedInstanceState?.getInt("percent", 0) ?: 0

        statusText.value = state ?: rh.gs(app.aaps.core.ui.R.string.timedetection)
        progress.intValue = percent
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    NtpProgressSheet(
                        title = rh.gs(app.aaps.core.ui.R.string.please_wait),
                        status = statusText.value,
                        percent = progress.intValue,
                        closeLabel = rh.gs(app.aaps.core.ui.R.string.close),
                        onClose = { dismiss() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        aapsLogger.debug(LTag.UI, "onResume")
        if (percent == 100) {
            dismiss()
            return
        } else
            dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        disposable += rxBus
            .toObservable(EventNtpStatus::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ event: EventNtpStatus ->
                           aapsLogger.debug(LTag.UI, "Status: " + event.status + " Percent: " + event.percent)
                           statusText.value = event.status
                           progress.intValue = event.percent
                           state = event.status
                           percent = event.percent
                           if (event.percent == 100) {
                               SystemClock.sleep(100)
                               dismiss()
                           }
                       }, fabricPrivacy::logException)
    }

    override fun onPause() {
        aapsLogger.debug(LTag.UI, "onPause")
        super.onPause()
        disposable.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("state", state)
        outState.putInt("percent", percent)
        super.onSaveInstanceState(outState)
    }
}
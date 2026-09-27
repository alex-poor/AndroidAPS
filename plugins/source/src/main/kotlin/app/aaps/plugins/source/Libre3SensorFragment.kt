package app.aaps.plugins.source

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.libre3.Libre3NfcActivation
import app.aaps.plugins.source.compose.Libre3SensorScreen
import dagger.android.support.DaggerFragment
import javax.inject.Inject

/**
 * Host for [Libre3SensorScreen]. State comes straight from the plugin's flow, so the screen has
 * no state of its own to drift.
 *
 * "Start new sensor" arms NFC reader-mode for a single Libre 3 tap, behind a confirmation, because
 * a fresh sensor's tap ACTIVATES it irreversibly. The activation itself runs in the plugin; this
 * fragment only owns the reader-mode (which needs an Activity) and reports the outcome. Both
 * destructive actions confirm through `OKDialog`, the same path every other irreversible action in
 * AAPS uses.
 */
class Libre3SensorFragment : DaggerFragment() {

    @Inject lateinit var libre3SourcePlugin: Libre3SourcePlugin
    @Inject lateinit var rh: app.aaps.core.interfaces.resources.ResourceHelper

    private val nfcAdapter: NfcAdapter? get() = context?.let { NfcAdapter.getDefaultAdapter(it) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    val state by libre3SourcePlugin.sensorState.collectAsState()
                    Libre3SensorScreen(
                        state = state,
                        onStartNewSensor = { startActivationScan() },
                        onStopSensor = {
                            OKDialog.showConfirmation(
                                requireActivity(),
                                "Stop this sensor? It cannot be restarted."
                            ) { libre3SourcePlugin.stopSensor() }
                        },
                        onForgetSensor = {
                            OKDialog.showConfirmation(
                                requireActivity(),
                                "Forget this sensor? AAPS will no longer connect to it."
                            ) { libre3SourcePlugin.forgetSensor() }
                        }
                    )
                }
            }
        }

    /** Confirm the irreversible act, then arm reader-mode for one tap. */
    private fun startActivationScan() {
        val adapter = nfcAdapter
        if (adapter == null || !adapter.isEnabled) {
            OKDialog.show(
                requireContext(), rh.gs(R.string.source_libre3),
                "NFC is off or unavailable. Turn NFC on to start a sensor."
            )
            return
        }
        OKDialog.showConfirmation(
            requireActivity(),
            "Start a new sensor?\n\nHold the phone to a freshly-applied Libre 3 to ACTIVATE it — " +
                "activating a fresh sensor cannot be undone. (Scanning a sensor already running under " +
                "your account takes it over instead, which is safe.)"
        ) { armReader(adapter) }
    }

    private fun armReader(adapter: NfcAdapter) {
        Toast.makeText(requireContext(), "Hold the phone to the sensor…", Toast.LENGTH_LONG).show()
        val flags = NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(requireActivity(), { tag -> onTag(tag) }, flags, null)
    }

    /** Reader-mode callback — runs on a binder thread, so the blocking NFC I/O is safe here. */
    private fun onTag(tag: Tag) {
        val result = runCatching { libre3SourcePlugin.activateSensor(tag) }
            .getOrElse { Libre3NfcActivation.Result.Failed(it.message ?: "NFC error") }
        val act = activity ?: return
        act.runOnUiThread {
            nfcAdapter?.disableReaderMode(act)
            val msg = when (result) {
                is Libre3NfcActivation.Result.Activated ->
                    "Activated ${result.serialNumber}. Pairing over Bluetooth now; it warms up for " +
                        "${result.warmupMinutes} min before the first reading."

                Libre3NfcActivation.Result.Retry ->
                    "The sensor isn't ready yet. Wait a minute or two after applying it, then try again."

                is Libre3NfcActivation.Result.Failed ->
                    "Couldn't start the sensor: ${result.reason}"
            }
            OKDialog.show(requireContext(), rh.gs(R.string.source_libre3), msg)
        }
    }

    override fun onPause() {
        // Never leave reader-mode armed when the screen isn't in front of the user.
        activity?.let { act -> runCatching { nfcAdapter?.disableReaderMode(act) } }
        super.onPause()
    }
}

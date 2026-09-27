package app.aaps.plugins.source

import android.app.AlertDialog
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.libre3.Libre3LagOverride
import app.aaps.libre3.Libre3NfcActivation
import app.aaps.plugins.source.compose.Libre3SensorScreen
import dagger.android.support.DaggerFragment
import javax.inject.Inject
import kotlin.math.roundToInt

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
    @Inject lateinit var profileUtil: ProfileUtil

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
                        onEnterFingerprickBg = { promptFingerprickBg() },
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

    /**
     * Enter a finger-prick BG. On a fast rise it arms the lag override (the finger-prick becomes the
     * loop's ground truth and a decaying correction rides the sensor stream until the rise resolves);
     * on a flat/falling trace it does nothing, and says so.
     */
    private fun promptFingerprickBg() {
        val ctx = requireContext()
        val units = profileUtil.units
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "BG (${units.asText})"
        }
        AlertDialog.Builder(ctx)
            .setTitle("Finger-prick BG")
            .setMessage(
                "Your current finger-prick reading. On a fast rise this becomes the loop's ground " +
                    "truth and corrects the lagging sensor until the rise flattens. It does nothing " +
                    "when you're flat or falling — the sensor is accurate then."
            )
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                val entered = input.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val mgdl = profileUtil.convertToMgdl(entered, units).roundToInt()
                val msg = when (val r = libre3SourcePlugin.applyManualBg(mgdl)) {
                    is Libre3LagOverride.ArmResult.Armed ->
                        "Applied. Correcting the sensor by +${profileUtil.fromMgdlToStringInUnits(r.gapMgdl)} " +
                            "${units.asText}, fading out as the rise flattens." +
                            if (r.clamped) " (Capped to a physiologically plausible gap.)" else ""

                    is Libre3LagOverride.ArmResult.Rejected ->
                        "Not applied: ${r.reason}"
                }
                OKDialog.show(ctx, rh.gs(R.string.source_libre3), msg)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onPause() {
        // Never leave reader-mode armed when the screen isn't in front of the user.
        activity?.let { act -> runCatching { nfcAdapter?.disableReaderMode(act) } }
        super.onPause()
    }
}

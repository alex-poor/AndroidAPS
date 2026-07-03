package app.aaps.plugins.main.general.smsCommunicator

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.smsCommunicator.Sms
import app.aaps.core.interfaces.smsCommunicator.SmsCommunicator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.main.general.smsCommunicator.compose.SmsLine
import app.aaps.plugins.main.general.smsCommunicator.compose.SmsScreen
import app.aaps.plugins.main.general.smsCommunicator.compose.SmsUiState
import app.aaps.plugins.main.general.smsCommunicator.events.EventSmsCommunicatorUpdateGui
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.Collections
import javax.inject.Inject
import kotlin.math.max

/**
 * Redesigned SMS & remote screen (handoff Section 7), hosted in Compose. Read-only presentation over
 * the SMS plugin state + preferences — no command/authentication logic changes.
 */
class SmsCommunicatorFragment : DaggerFragment() {

    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var smsCommunicator: SmsCommunicator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var preferences: Preferences

    private val disposable = CompositeDisposable()
    private val state = mutableStateOf(SmsUiState())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { SmsScreen(state.value) } }
        }

    @Synchronized
    override fun onResume() {
        super.onResume()
        disposable += rxBus
            .toObservable(EventSmsCommunicatorUpdateGui::class.java)
            .observeOn(aapsSchedulers.main)
            .subscribe({ build() }, fabricPrivacy::logException)
        build()
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    private fun build() {
        Collections.sort(smsCommunicator.messages) { a: Sms, b: Sms -> (a.date - b.date).toInt() }
        val messagesToShow = 40
        val start = max(0, smsCommunicator.messages.size - messagesToShow)
        val lines = (start until smsCommunicator.messages.size).map { x ->
            val sms = smsCommunicator.messages[x]
            SmsLine(
                time = dateUtil.timeString(sms.date),
                incoming = sms.received || sms.ignored,
                processed = sms.processed,
                ignored = sms.ignored,
                number = sms.phoneNumber,
                text = sms.text
            )
        }.reversed()

        val remoteOn = preferences.get(BooleanKey.SmsAllowRemoteCommands)
        val otpPassword = runCatching { preferences.get(StringKey.SmsOtpPassword) }.getOrDefault("")
        val allowed = runCatching { preferences.get(StringKey.SmsAllowedNumbers) }.getOrDefault("")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }

        state.value = SmsUiState(
            remoteCommandsOn = remoteOn,
            otpOn = remoteOn && otpPassword.length >= 3,
            allowedNumbers = allowed,
            messages = lines
        )
    }
}

package app.aaps.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.wizard.QuickWizard
import app.aaps.core.objects.wizard.QuickWizardEntry
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.EditQuickWizardResult
import app.aaps.ui.dialogs.compose.EditQuickWizardSheet
import app.aaps.ui.dialogs.compose.EditQuickWizardState
import app.aaps.ui.events.EventQuickWizardChange
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dagger.android.support.DaggerDialogFragment
import org.json.JSONException
import javax.inject.Inject

/**
 * Redesigned Edit Quick Wizard (config) dialog. UI is Compose ([EditQuickWizardSheet]); [save] maps
 * the edited values back onto the [QuickWizardEntry] with the SAME `entry.storage.put(...)` calls and
 * persists via `quickWizard.addOrUpdate(entry)` exactly as before. The from/to time-of-day pickers
 * stay in the host (MaterialTimePicker), updating Compose state via mutableStateOf.
 */
class EditQuickWizardDialog : DaggerDialogFragment() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var quickWizard: QuickWizard
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var constraintChecker: ConstraintsChecker
    @Inject lateinit var ctx: Context
    @Inject lateinit var preferences: Preferences

    var position = -1
    private var fromSeconds: Int = 0
    private var toSeconds: Int = 0

    // Compose-observable display strings for the from/to time-of-day (owned by the host pickers).
    private var fromDisplay by mutableStateOf("")
    private var toDisplay by mutableStateOf("")

    private lateinit var entry: QuickWizardEntry

    companion object {

        const val MIN_PERCENTAGE: Double = 10.0
        const val MAX_PERCENTAGE: Double = 200.0
        const val DEFAULT_PERCENTAGE: Double = 100.0
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog?.window?.setGravity(Gravity.BOTTOM)
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)

        (arguments ?: savedInstanceState)?.let { bundle ->
            position = bundle.getInt("position", -1)
        }
        entry = if (position == -1) quickWizard.newEmptyItem() else quickWizard[position]

        fromSeconds = entry.validFrom()
        toSeconds = entry.validTo()
        fromDisplay = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(fromSeconds))
        toDisplay = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(toSeconds))

        val maxCarbs = constraintChecker.getMaxCarbsAllowed().value().toDouble()
        val showDevice = preferences.get(BooleanKey.WearControl)
        val showSuperBolus = preferences.get(BooleanKey.OverviewUseSuperBolus)

        val devicePhone = entry.device() == QuickWizardEntry.DEVICE_ALL || entry.device() == QuickWizardEntry.DEVICE_PHONE
        val deviceWatch = entry.device() == QuickWizardEntry.DEVICE_ALL || entry.device() == QuickWizardEntry.DEVICE_WATCH

        val state = EditQuickWizardState(
            buttonText = entry.buttonText(),
            carbs = entry.carbs().toDouble(),
            maxCarbs = maxCarbs,
            carbTime = entry.carbTime().toDouble(),
            percentage = entry.percentage().toDouble(),
            minPercentage = MIN_PERCENTAGE,
            maxPercentage = MAX_PERCENTAGE,
            useBG = entry.useBG(),
            useCOB = entry.useCOB(),
            useIOB = entry.useIOB(),
            usePositiveIOBOnly = entry.usePositiveIOBOnly(),
            useTrend = entry.useTrend(),
            useSuperBolus = entry.useSuperBolus(),
            useTempTarget = entry.useTempTarget(),
            useAlarm = entry.useAlarm(),
            useEcarbs = entry.useEcarbs(),
            carbs2 = entry.carbs2().toDouble(),
            timeOffset = entry.time().toDouble(),
            durationHours = entry.duration().toDouble(),
            showDevice = showDevice,
            showSuperBolus = showSuperBolus,
            devicePhone = devicePhone,
            deviceWatch = deviceWatch,
            fromDisplay = fromDisplay,
            toDisplay = toDisplay
        )

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    // Re-read the observable display strings so the pickers update the rows.
                    EditQuickWizardSheet(
                        state = state.copy(fromDisplay = fromDisplay, toDisplay = toDisplay),
                        onSave = ::save,
                        onClose = { dismiss() },
                        onPickFrom = { pickTime(isFrom = true) },
                        onPickTo = { pickTime(isFrom = false) }
                    )
                }
            }
        }
    }

    private fun pickTime(isFrom: Boolean) {
        val current = if (isFrom) fromSeconds else toSeconds
        val clockFormat = if (DateFormat.is24HourFormat(context)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H
        val timePicker = MaterialTimePicker.Builder()
            .setTimeFormat(clockFormat)
            .setHour(T.secs(current.toLong()).hours().toInt())
            .setMinute(T.secs((current % 3600).toLong()).mins().toInt())
            .build()
        timePicker.addOnPositiveButtonClickListener {
            val seconds = (T.hours(timePicker.hour.toLong()).secs() + T.mins(timePicker.minute.toLong()).secs()).toInt()
            val display = dateUtil.timeString(dateUtil.secondsOfTheDayToMillisecondsOfHoursAndMinutes(seconds))
            if (isFrom) {
                fromSeconds = seconds
                fromDisplay = display
            } else {
                toSeconds = seconds
                toDisplay = display
            }
        }
        timePicker.show(parentFragmentManager, "event_time_time_picker")
    }

    private fun save(result: EditQuickWizardResult) {
        // Legacy guard: require normal carbs, or eCarbs enabled with a positive eCarbs value.
        if (result.carbs > 0 || (result.useEcarbs == QuickWizardEntry.YES && result.carbs2 > 0)) {
            try {
                entry.storage.put("buttonText", result.buttonText)
                entry.storage.put("carbs", result.carbs)
                entry.storage.put("carbTime", result.carbTime)
                entry.storage.put("useAlarm", result.useAlarm)
                entry.storage.put("validFrom", fromSeconds)
                entry.storage.put("validTo", toSeconds)
                entry.storage.put("useBG", result.useBG)
                entry.storage.put("useCOB", result.useCOB)
                entry.storage.put("useIOB", result.useIOB)
                entry.storage.put("usePositiveIOBOnly", result.usePositiveIOBOnly)
                entry.storage.put("useTrend", result.useTrend)
                entry.storage.put("useSuperBolus", result.useSuperBolus)
                entry.storage.put("useTempTarget", result.useTempTarget)
                entry.storage.put("percentage", result.percentage)
                if (result.devicePhone && result.deviceWatch) {
                    entry.storage.put("device", QuickWizardEntry.DEVICE_ALL)
                } else if (result.devicePhone) {
                    entry.storage.put("device", QuickWizardEntry.DEVICE_PHONE)
                } else if (result.deviceWatch) {
                    entry.storage.put("device", QuickWizardEntry.DEVICE_WATCH)
                }
                entry.storage.put("useEcarbs", result.useEcarbs)
                entry.storage.put("time", result.time)
                entry.storage.put("duration", result.duration)
                entry.storage.put("carbs2", result.carbs2)
            } catch (e: JSONException) {
                aapsLogger.error("Unhandled exception", e)
            }

            quickWizard.addOrUpdate(entry)
            rxBus.send(EventQuickWizardChange())
            dismiss()
        } else {
            ToastUtils.warnToast(context, R.string.change_your_input)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("position", position)
    }
}

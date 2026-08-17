package app.aaps.ui.dialogs

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Translator
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.utils.HtmlHelper
import app.aaps.ui.R
import app.aaps.ui.dialogs.compose.CareInputs
import app.aaps.ui.dialogs.compose.CareMeter
import app.aaps.ui.dialogs.compose.CareSheet
import app.aaps.ui.dialogs.compose.CareSheetState
import com.google.common.base.Joiner
import dagger.android.support.DaggerDialogFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.LinkedList
import javax.inject.Inject

/**
 * Redesigned generic careportal event dialog. UI is Compose ([CareSheet]); [submit] runs the SAME
 * `TE` construction + `persistenceLayer.insertPumpTherapyEventIfNewByTimestamp` + `uel.log` /
 * `OKDialog` confirmation path as the legacy dialog. Which fields are shown (glucose + source /
 * duration / notes) is derived from the [UiInteraction.EventType] arg, exactly as before.
 */
class CareDialog(val fm: FragmentManager) : DaggerDialogFragment() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var translator: Translator
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var dateUtil: DateUtil

    private val disposable = CompositeDisposable()

    private var options: UiInteraction.EventType = UiInteraction.EventType.BGCHECK

    private var valuesWithUnit = mutableListOf<ValueWithUnit?>()

    @StringRes
    private var event: Int = app.aaps.core.ui.R.string.none

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putInt("event", event)
        savedInstanceState.putInt("options", options.ordinal)
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

        (savedInstanceState ?: arguments)?.let {
            event = it.getInt("event", app.aaps.core.ui.R.string.error)
            options = UiInteraction.EventType.entries.toTypedArray()[it.getInt("options", 0)]
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AapsTheme { CareSheet(state = buildState(), onSubmit = ::submit, onClose = { dismiss() }) } }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    private fun buildState(): CareSheetState {
        // Visibility mirrors the legacy XML-driven dialog exactly:
        //   glucose + source: BGCHECK, QUESTION, ANNOUNCEMENT
        //   duration:         NOTE, EXERCISE
        //   notes:            NOTE, QUESTION, ANNOUNCEMENT, EXERCISE (independent of preferences)
        val showBg = when (options) {
            UiInteraction.EventType.BGCHECK,
            UiInteraction.EventType.QUESTION,
            UiInteraction.EventType.ANNOUNCEMENT -> true

            else                                 -> false
        }
        val showDuration = when (options) {
            UiInteraction.EventType.NOTE,
            UiInteraction.EventType.EXERCISE     -> true

            else                                 -> false
        }
        val showNotes = when (options) {
            UiInteraction.EventType.NOTE,
            UiInteraction.EventType.QUESTION,
            UiInteraction.EventType.ANNOUNCEMENT,
            UiInteraction.EventType.EXERCISE     -> true

            else                                 -> preferences.get(BooleanKey.OverviewShowNotesInDialogs)
        }

        val mmol = profileFunction.getUnits() == GlucoseUnit.MMOL
        val bgInitial = profileUtil.fromMgdlToUnits(glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0)

        return CareSheetState(
            title = rh.gs(
                when (options) {
                    UiInteraction.EventType.BGCHECK        -> app.aaps.core.ui.R.string.careportal_bgcheck
                    UiInteraction.EventType.SENSOR_INSERT  -> app.aaps.core.ui.R.string.cgm_sensor_insert
                    UiInteraction.EventType.BATTERY_CHANGE -> app.aaps.core.ui.R.string.pump_battery_change
                    UiInteraction.EventType.NOTE           -> app.aaps.core.ui.R.string.careportal_note
                    UiInteraction.EventType.EXERCISE       -> app.aaps.core.ui.R.string.careportal_exercise
                    UiInteraction.EventType.QUESTION       -> app.aaps.core.ui.R.string.careportal_question
                    UiInteraction.EventType.ANNOUNCEMENT   -> app.aaps.core.ui.R.string.careportal_announcement
                }
            ),
            submitLabel = rh.gs(app.aaps.core.ui.R.string.ok),
            showBg = showBg,
            bgInitial = bgInitial,
            bgMin = if (mmol) 2.0 else 36.0,
            bgMax = if (mmol) 30.0 else 500.0,
            bgStep = if (mmol) 0.1 else 1.0,
            bgDecimals = if (mmol) 1 else 0,
            bgUnit = if (mmol) rh.gs(app.aaps.core.ui.R.string.mmol) else rh.gs(app.aaps.core.ui.R.string.mgdl),
            showDuration = showDuration,
            durationMax = Constants.MAX_PROFILE_SWITCH_DURATION,
            durationStep = 10.0,
            showNotes = showNotes
        )
    }

    private fun submit(inputs: CareInputs) {
        val enteredBy = "AAPS"
        val unitResId = if (profileFunction.getUnits() == GlucoseUnit.MGDL) app.aaps.core.ui.R.string.mgdl else app.aaps.core.ui.R.string.mmol

        var eventTime = dateUtil.nowWithoutMilliseconds()
        eventTime -= eventTime % 1000
        val eventTimeChanged = false

        val therapyEvent = TE(
            timestamp = eventTime,
            type = when (options) {
                UiInteraction.EventType.BGCHECK        -> TE.Type.FINGER_STICK_BG_VALUE
                UiInteraction.EventType.SENSOR_INSERT  -> TE.Type.SENSOR_CHANGE
                UiInteraction.EventType.BATTERY_CHANGE -> TE.Type.PUMP_BATTERY_CHANGE
                UiInteraction.EventType.NOTE           -> TE.Type.NOTE
                UiInteraction.EventType.EXERCISE       -> TE.Type.EXERCISE
                UiInteraction.EventType.QUESTION       -> TE.Type.QUESTION
                UiInteraction.EventType.ANNOUNCEMENT   -> TE.Type.ANNOUNCEMENT
            },
            glucoseUnit = profileFunction.getUnits()
        )

        val actions: LinkedList<String> = LinkedList()
        actions.add(rh.gs(R.string.confirm_treatment))
        if (options == UiInteraction.EventType.BGCHECK || options == UiInteraction.EventType.QUESTION || options == UiInteraction.EventType.ANNOUNCEMENT) {
            val meterType =
                when (inputs.meter) {
                    CareMeter.METER  -> TE.MeterType.FINGER
                    CareMeter.SENSOR -> TE.MeterType.SENSOR
                    CareMeter.MANUAL -> TE.MeterType.MANUAL
                }
            actions.add(rh.gs(R.string.glucose_type) + ": " + translator.translate(meterType))
            actions.add(rh.gs(app.aaps.core.ui.R.string.bg_label) + ": " + profileUtil.stringInCurrentUnitsDetect(inputs.bg) + " " + rh.gs(unitResId))
            therapyEvent.glucoseType = meterType
            therapyEvent.glucose = inputs.bg
            valuesWithUnit.add(ValueWithUnit.fromGlucoseUnit(inputs.bg, profileFunction.getUnits()))
            valuesWithUnit.add(ValueWithUnit.TEMeterType(meterType))
        }
        if (options == UiInteraction.EventType.NOTE || options == UiInteraction.EventType.EXERCISE) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.duration_label) + ": " + rh.gs(app.aaps.core.ui.R.string.format_mins, inputs.durationMin))
            therapyEvent.duration = T.mins(inputs.durationMin.toLong()).msecs()
            valuesWithUnit.add(ValueWithUnit.Minute(inputs.durationMin).takeIf { inputs.durationMin != 0 })
        }
        val notes = inputs.notes
        if (notes.isNotEmpty()) {
            actions.add(rh.gs(app.aaps.core.ui.R.string.notes_label) + ": " + notes)
            therapyEvent.note = notes
        }

        if (eventTimeChanged) actions.add(rh.gs(app.aaps.core.ui.R.string.time) + ": " + dateUtil.dateAndTimeString(eventTime))

        therapyEvent.enteredBy = enteredBy

        val source = when (options) {
            UiInteraction.EventType.BGCHECK        -> Sources.BgCheck
            UiInteraction.EventType.SENSOR_INSERT  -> Sources.SensorInsert
            UiInteraction.EventType.BATTERY_CHANGE -> Sources.BatteryChange
            UiInteraction.EventType.NOTE           -> Sources.Note
            UiInteraction.EventType.EXERCISE       -> Sources.Exercise
            UiInteraction.EventType.QUESTION       -> Sources.Question
            UiInteraction.EventType.ANNOUNCEMENT   -> Sources.Announcement
        }

        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(event), HtmlHelper.fromHtml(Joiner.on("<br/>").join(actions)), {
                valuesWithUnit.add(0, ValueWithUnit.Timestamp(eventTime).takeIf { eventTimeChanged })
                valuesWithUnit.add(1, ValueWithUnit.TEType(therapyEvent.type))
                disposable += persistenceLayer.insertPumpTherapyEventIfNewByTimestamp(
                    therapyEvent = therapyEvent,
                    action = Action.CAREPORTAL,
                    source = source,
                    note = notes,
                    listValues = valuesWithUnit.filterNotNull()
                ).subscribe()
            }, null)
        }
        dismiss()
    }
}

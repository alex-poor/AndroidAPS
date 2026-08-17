package app.aaps.plugins.constraints.objectives.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.constraints.R
import app.aaps.plugins.constraints.objectives.compose.ExamOption
import app.aaps.plugins.constraints.objectives.compose.ExamSheet
import app.aaps.plugins.constraints.objectives.compose.ExamState
import app.aaps.plugins.constraints.objectives.events.EventObjectivesUpdateGui
import app.aaps.plugins.constraints.objectives.objectives.Objective
import app.aaps.plugins.constraints.objectives.objectives.Objective.ExamTask
import dagger.android.support.DaggerDialogFragment
import javax.inject.Inject

/**
 * One exam question from an objective. UI is Compose ([ExamSheet]); the grading rule is unchanged —
 * every option must evaluate correctly, and a wrong answer still locks the question for an hour.
 */
class ObjectivesExamDialog : DaggerDialogFragment() {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var dateUtil: DateUtil

    companion object {

        var objective: Objective? = null
    }

    private var currentTask = 0
    private val state = mutableStateOf(ExamState())
    private val hintViews = mutableStateOf<List<View>>(emptyList())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // load data from bundle
        (savedInstanceState ?: arguments)?.let { bundle ->
            currentTask = bundle.getInt("currentTask", 0)
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AapsTheme {
                    ExamSheet(
                        state = state.value,
                        hintViews = hintViews.value,
                        onToggleOption = ::toggleOption,
                        onVerify = ::verify,
                        onReset = ::resetAnswer,
                        onBack = { currentTask--; updateGui() },
                        onNext = { currentTask++; updateGui() },
                        onNextUnanswered = ::nextUnanswered,
                        onClose = { dismiss() }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(false)
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onResume() {
        super.onResume()
        updateGui()
    }

    override fun onSaveInstanceState(bundle: Bundle) {
        super.onSaveInstanceState(bundle)
        bundle.putInt("currentTask", currentTask)
    }

    private fun task(): ExamTask? = objective?.tasks?.getOrNull(currentTask) as? ExamTask

    private fun toggleOption(index: Int) {
        val task = task() ?: return
        if (task.answered) return
        task.options.getOrNull(index)?.let { it.selected = !it.selected }
        updateGui()
    }

    private fun verify() {
        val task = task() ?: return
        if (task.answered) return
        val result = task.options.all { it.evaluate() }
        task.answered = result
        if (!result) {
            task.disabledTo = dateUtil.now() + T.hours(1).msecs()
            context?.let { ToastUtils.infoToast(it, R.string.wronganswer) }
        } else task.disabledTo = 0
        updateGui()
        rxBus.send(EventObjectivesUpdateGui())
    }

    private fun resetAnswer() {
        val task = task() ?: return
        task.answered = false
        updateGui()
        rxBus.send(EventObjectivesUpdateGui())
    }

    private fun nextUnanswered() {
        val objective = objective ?: return
        for (i in (currentTask + 1) until objective.tasks.size) {
            if (!objective.tasks[i].isCompleted()) {
                currentTask = i; updateGui(); return
            }
        }
        for (i in 0..currentTask) {
            if (!objective.tasks[i].isCompleted()) {
                currentTask = i; updateGui(); return
            }
        }
    }

    @Synchronized
    fun updateGui() {
        val objective = objective ?: return
        val task = task() ?: return
        val ctx = context ?: return

        // Answered questions reveal the correct set, matching the legacy "tick the right ones and
        // disable" behaviour.
        val options = task.options.map { ExamOption(rh.gs(it.option), if (task.answered) it.isCorrect else it.selected) }

        hintViews.value = task.hints.map { it.generate(ctx) }
        state.value = ExamState(
            name = rh.gs(task.task),
            question = rh.gs(task.question),
            options = options,
            answered = task.answered,
            disabledUntil = if (task.isEnabledAnswer()) null else rh.gs(R.string.answerdisabledto, dateUtil.timeString(task.disabledTo)),
            canVerify = !task.answered && task.isEnabledAnswer(),
            canGoBack = currentTask != 0,
            canGoNext = currentTask != objective.tasks.size - 1,
            canGoNextUnanswered = !objective.isCompleted,
            position = "${currentTask + 1} / ${objective.tasks.size}"
        )
    }
}

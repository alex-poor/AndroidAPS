package app.aaps.workflow

import android.content.Context
import android.os.SystemClock
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.lifecycle.AppLifecycle
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.workflow.CalculationWorkflow
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.JOB
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.MAIN_CALCULATION
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.PASS
import app.aaps.core.interfaces.workflow.CalculationWorkflow.Companion.UPDATE_PREDICTIONS
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.core.utils.worker.then
import app.aaps.workflow.iob.IobCobOref1Worker
import app.aaps.workflow.iob.IobCobOrefWorker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalculationWorkflowImpl @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val dateUtil: DateUtil,
    private val dataWorkerStorage: DataWorkerStorage,
    private val activePlugin: ActivePlugin,
    private val appLifecycle: AppLifecycle
) : CalculationWorkflow {

    init {
        // Verify definition
        var sumPercent = 0
        for (pass in CalculationWorkflow.ProgressData.entries) sumPercent += pass.percentOfTotal
        require(sumPercent == 100)
    }

    override fun stopCalculation(job: String, from: String) {
        aapsLogger.debug(LTag.WORKER, "Stopping calculation thread: $from")
        WorkManager.getInstance(context).cancelUniqueWork(job)
        val workStatus = WorkManager.getInstance(context).getWorkInfosForUniqueWork(job).get()
        while (workStatus.isNotEmpty() && workStatus[0].state == WorkInfo.State.RUNNING)
            SystemClock.sleep(100)
        aapsLogger.debug(LTag.WORKER, "Calculation thread stopped: $from")
    }

    override fun runCalculation(
        job: String,
        iobCobCalculator: IobCobCalculator,
        overviewData: OverviewData,
        reason: String,
        end: Long,
        bgDataReload: Boolean,
        cause: Event?
    ) {
        // Only prepare graph series when someone is looking at them. Upstream ran all 16 workers on
        // every CGM tick regardless; measured on device that was 1.54 s of the 1.64 s chain and the
        // largest single CPU consumer in the app, spent drawing an overview that was in a pocket.
        // HistoryBrowseActivity is always a foreground, graph-only user of this workflow, so it opts
        // in explicitly rather than through the lifecycle flag.
        val drawGraphs = appLifecycle.uiVisible || job != MAIN_CALCULATION
        aapsLogger.debug(LTag.WORKER, "Starting calculation worker: $reason to ${dateUtil.dateAndTimeAndSecondsString(end)} (graphs=$drawGraphs)")

        WorkManager.getInstance(context)
            .beginUniqueWork(
                job, ExistingWorkPolicy.REPLACE,
                if (bgDataReload) OneTimeWorkRequest.Builder(LoadBgDataWorker::class.java).setInputData(dataWorkerStorage.storeInputData(LoadBgDataWorker.LoadBgData(iobCobCalculator, end))).build()
                else OneTimeWorkRequest.Builder(DummyWorker::class.java).build()
            )
            // ---- dosing path: IOB/COB then the loop. Runs first and unconditionally. ----
            .then(
                if (activePlugin.activeSensitivity.isOref1)
                    OneTimeWorkRequest.Builder(IobCobOref1Worker::class.java)
                        .setInputData(dataWorkerStorage.storeInputData(IobCobOref1Worker.IobCobOref1WorkerData(iobCobCalculator, reason, end, job == MAIN_CALCULATION, cause)))
                        .build()
                else
                    OneTimeWorkRequest.Builder(IobCobOrefWorker::class.java)
                        .setInputData(dataWorkerStorage.storeInputData(IobCobOrefWorker.IobCobOrefWorkerData(iobCobCalculator, reason, end, job == MAIN_CALCULATION, cause)))
                        .build()
            )
            .then(OneTimeWorkRequest.Builder(UpdateIobCobSensWorker::class.java).build())
            .then(
                runIf = job == MAIN_CALCULATION,
                OneTimeWorkRequest.Builder(InvokeLoopWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(InvokeLoopWorker.InvokeLoopData(cause)))
                    .build()
            )
            .then(
                runIf = job == MAIN_CALCULATION,
                OneTimeWorkRequest.Builder(UpdateWidgetWorker::class.java).build()
            )
            // ---- presentation path: graph series only, skipped when the UI is not on screen ----
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(PrepareBucketedDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBucketedDataWorker.PrepareBucketedData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(PrepareBgDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBgDataWorker.PrepareBgData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_BG.pass).build())
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(PrepareTreatmentsDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTreatmentsDataWorker.PrepareTreatmentsData(overviewData)))
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(PrepareBasalDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBasalDataWorker.PrepareBasalData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(PrepareTemporaryTargetDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTemporaryTargetDataWorker.PrepareTemporaryTargetData(overviewData)))
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(PrepareRunningModeDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareRunningModeDataWorker.PrepareRunningModeData(overviewData)))
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_TT.pass).build())
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(PrepareIobAutosensGraphDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareIobAutosensGraphDataWorker.PrepareIobAutosensData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                runIf = drawGraphs && job == MAIN_CALCULATION,
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_IOB.pass).build())
                    .build()
            )
            .then(
                runIf = drawGraphs && job == MAIN_CALCULATION,
                OneTimeWorkRequest.Builder(PreparePredictionsWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PreparePredictionsWorker.PreparePredictionsData(overviewData)))
                    .build()
            )
            .then(
                runIf = drawGraphs,
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, job).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }

    override fun runOnReceivedPredictions(
        overviewData: OverviewData
    ) {
        aapsLogger.debug(LTag.WORKER, "Starting updateReceivedPredictions worker")

        WorkManager.getInstance(context)
            .beginUniqueWork(
                UPDATE_PREDICTIONS, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequest.Builder(PreparePredictionsWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PreparePredictionsWorker.PreparePredictionsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, UPDATE_PREDICTIONS).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }

    override fun runOnEventTherapyEventChange(overviewData: OverviewData) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                MAIN_CALCULATION, ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(PrepareTreatmentsDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTreatmentsDataWorker.PrepareTreatmentsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()

    }

    override fun runGraphsOnly(iobCobCalculator: IobCobCalculator, overviewData: OverviewData) {
        aapsLogger.debug(LTag.WORKER, "Rebuilding graph series after UI became visible")
        WorkManager.getInstance(context)
            .beginUniqueWork(
                MAIN_CALCULATION, ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(PrepareBucketedDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBucketedDataWorker.PrepareBucketedData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareBgDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBgDataWorker.PrepareBgData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareTreatmentsDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTreatmentsDataWorker.PrepareTreatmentsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareBasalDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBasalDataWorker.PrepareBasalData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareTemporaryTargetDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareTemporaryTargetDataWorker.PrepareTemporaryTargetData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareRunningModeDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareRunningModeDataWorker.PrepareRunningModeData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareIobAutosensGraphDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareIobAutosensGraphDataWorker.PrepareIobAutosensData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PreparePredictionsWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PreparePredictionsWorker.PreparePredictionsData(overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putString(JOB, MAIN_CALCULATION).putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }

    override fun runOnScaleChanged(iobCobCalculator: IobCobCalculator, overviewData: OverviewData) {
        WorkManager.getInstance(context)
            .beginUniqueWork(
                MAIN_CALCULATION, ExistingWorkPolicy.APPEND,
                OneTimeWorkRequest.Builder(PrepareBucketedDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBucketedDataWorker.PrepareBucketedData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(PrepareBgDataWorker::class.java)
                    .setInputData(dataWorkerStorage.storeInputData(PrepareBgDataWorker.PrepareBgData(iobCobCalculator, overviewData)))
                    .build()
            )
            .then(
                OneTimeWorkRequest.Builder(UpdateGraphWorker::class.java)
                    .setInputData(Data.Builder().putInt(PASS, CalculationWorkflow.ProgressData.DRAW_FINAL.pass).build())
                    .build()
            )
            .enqueue()
    }
}
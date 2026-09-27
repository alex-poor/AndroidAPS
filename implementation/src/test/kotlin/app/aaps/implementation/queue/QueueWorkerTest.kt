package app.aaps.implementation.queue

import android.content.Context
import android.os.PowerManager
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.androidPermissions.AndroidPermission
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.implementation.queue.commands.CommandTempBasalAbsolute
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.Callback
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class QueueWorkerTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var powerManager: PowerManager
    @Mock lateinit var androidPermission: AndroidPermission
    @Mock lateinit var uiInteraction: UiInteraction
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var jobName: CommandQueueName
    @Mock lateinit var workManager: WorkManager

    init {
        addInjector {
            if (it is CommandTempBasalAbsolute) {
                it.aapsLogger = aapsLogger
                it.activePlugin = activePlugin
                it.rh = rh
                it.pumpEnactResultProvider = pumpEnactResultProvider
            }
            if (it is QueueWorker) {
                it.aapsLogger = aapsLogger
                it.queue = commandQueue
                it.context = context
                it.rxBus = rxBus
                it.activePlugin = activePlugin
                it.rh = rh
                it.preferences = preferences
                it.androidPermission = androidPermission
                it.config = config
            }
        }
    }

    private lateinit var commandQueue: CommandQueueImplementation
    private lateinit var sut: QueueWorker

    @BeforeEach
    fun prepare() {
        commandQueue = CommandQueueImplementation(
            injector, aapsLogger, rxBus, aapsSchedulers, rh, constraintChecker,
            profileFunction, activePlugin, context, config, dateUtil, fabricPrivacy,
            uiInteraction, persistenceLayer, decimalFormatter, pumpEnactResultProvider, jobName, workManager
        )

        val pumpDescription = PumpDescription()
        pumpDescription.basalMinimumRate = 0.1

        whenever(context.getSystemService(Context.POWER_SERVICE)).thenReturn(powerManager)
        whenever(profileFunction.getProfile()).thenReturn(validProfile)

        val bolusConstraint = ConstraintObject(0.0, aapsLogger)
        whenever(constraintChecker.applyBolusConstraints(anyOrNull())).thenReturn(bolusConstraint)
        whenever(constraintChecker.applyExtendedBolusConstraints(anyOrNull())).thenReturn(bolusConstraint)
        val carbsConstraint = ConstraintObject(0, aapsLogger)
        whenever(constraintChecker.applyCarbsConstraints(anyOrNull())).thenReturn(carbsConstraint)
        val rateConstraint = ConstraintObject(0.0, aapsLogger)
        whenever(constraintChecker.applyBasalConstraints(anyOrNull(), anyOrNull())).thenReturn(rateConstraint)
        val percentageConstraint = ConstraintObject(0, aapsLogger)
        whenever(constraintChecker.applyBasalPercentConstraints(anyOrNull(), anyOrNull()))
            .thenReturn(percentageConstraint)
        whenever(rh.gs(ArgumentMatchers.eq(app.aaps.core.ui.R.string.temp_basal_absolute), anyOrNull(), anyOrNull())).thenReturn("TEMP BASAL %1\$.2f U/h %2\$d min")

        sut = TestListenableWorkerBuilder<QueueWorker>(context).build()
    }

    @Test
    fun commandIsPickedUp() = runTest(timeout = 30.seconds) {
        commandQueue.tempBasalAbsolute(2.0, 60, true, validProfile, PumpSync.TemporaryBasalType.NORMAL, null)
        val result = sut.doWorkAndLog()
        assertIs<ListenableWorker.Result.Success>(result)
        assertThat(commandQueue.size()).isEqualTo(0)
    }

    @Test
    fun `a command that throws is failed back to its caller and does not wedge the queue`() = runTest(timeout = 30.seconds) {
        val pump = spy(testPumpPlugin)
        doThrow(IllegalStateException("driver blew up")).whenever(pump).setTempBasalAbsolute(any(), any(), any(), any(), any())
        whenever(activePlugin.activePump).thenReturn(pump)
        whenever(rh.gs(app.aaps.core.ui.R.string.connectiontimedout)).thenReturn("Connection timed out")
        var reported: PumpEnactResult? = null
        val callback = object : Callback() {
            override fun run() { reported = result }
        }

        commandQueue.tempBasalAbsolute(2.0, 60, true, validProfile, PumpSync.TemporaryBasalType.NORMAL, callback)
        val result = sut.doWorkAndLog()

        assertIs<ListenableWorker.Result.Success>(result)
        assertThat(commandQueue.performing()).isNull()
        assertThat(commandQueue.size()).isEqualTo(0)
        assertThat(reported?.success).isFalse()
    }
}

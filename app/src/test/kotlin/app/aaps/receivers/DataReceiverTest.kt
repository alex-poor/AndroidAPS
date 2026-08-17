package app.aaps.receivers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.work.OneTimeWorkRequest
import app.aaps.core.interfaces.receivers.Intents
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.utils.receivers.DataWorkerStorage
import app.aaps.plugins.source.XdripSourcePlugin
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.reflect.KClass

/**
 * xDrip is the only local BG broadcast this build accepts. The Dexcom / Glimp / Poctech / Tomato /
 * MM640g / Ottai / Syai / SI / Sino source plugins and the SMS command channel were removed, so the
 * receiver's `when` has a single arm and everything else must fall through to the else branch.
 */
class DataReceiverTest : TestBase() {

    // The System Under Test
    private lateinit var dataReceiver: DataReceiver

    // Mocks for dependencies
    @Mock private lateinit var dataWorkerStorage: DataWorkerStorage
    @Mock private lateinit var fabricPrivacy: FabricPrivacy
    @Mock private lateinit var context: Context
    @Mock private lateinit var bundle: Bundle

    private val workRequestCaptor = argumentCaptor<OneTimeWorkRequest>()

    @BeforeEach
    fun setUp() {

        // Manually inject mocks into the receiver instance
        dataReceiver = DataReceiver().also {
            it.aapsLogger = aapsLogger
            it.dataWorkerStorage = dataWorkerStorage
            it.fabricPrivacy = fabricPrivacy
        }
    }

    private fun createIntent(action: String): Intent {
        val intent = mock<Intent>()
        whenever(intent.action).thenReturn(action)
        whenever(intent.extras).thenReturn(bundle)
        return intent
    }

    private fun assertWorkerEnqueued(workerClass: KClass<*>) {
        verify(dataWorkerStorage).enqueue(workRequestCaptor.capture())
        val capturedRequest = workRequestCaptor.singleValue
        assert(capturedRequest.workSpec.workerClassName == workerClass.java.name)
    }

    @Test
    fun `processIntent enqueues XdripSourceWorker for ACTION_NEW_BG_ESTIMATE`() {
        // Arrange
        val intent = createIntent(Intents.ACTION_NEW_BG_ESTIMATE)
        whenever(dataWorkerStorage.storeInputData(any(), any())).thenReturn(androidx.work.Data.EMPTY)

        // Act
        dataReceiver.processIntent(context, intent)

        // Assert
        assertWorkerEnqueued(XdripSourcePlugin.XdripSourceWorker::class)
    }

    @Test
    fun `processIntent ignores broadcasts from removed BG sources`() {
        // Arrange
        for (action in listOf(Intents.POCTECH_BG, Intents.GLIMP_BG, Intents.TOMATO_BG, Intents.NS_EMULATOR, Intents.DEXCOM_BG)) {
            // Act
            dataReceiver.processIntent(context, createIntent(action))
        }

        // Assert
        verify(dataWorkerStorage, never()).enqueue(any())
    }

    @Test
    fun `processIntent does nothing if intent has no bundle`() {
        // Arrange
        val intent = Intent(Intents.ACTION_NEW_BG_ESTIMATE) // No bundle attached

        // Act
        dataReceiver.processIntent(context, intent)

        // Assert
        verify(dataWorkerStorage, never()).enqueue(any())
    }

    @Test
    fun `processIntent does nothing for an unknown action`() {
        // Arrange
        val intent = createIntent("some.unknown.ACTION")

        // Act
        dataReceiver.processIntent(context, intent)

        // Assert
        verify(dataWorkerStorage, never()).enqueue(any())
    }
}

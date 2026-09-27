package app.aaps.plugins.source

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

class Libre3CredentialStoreTest {

    @TempDir lateinit var dir: File
    private lateinit var store: Libre3CredentialStore
    private val file get() = File(dir, Libre3CredentialStore.FILE_NAME)

    @BeforeEach
    fun setUp() {
        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(dir)
        store = Libre3CredentialStore(context, mock<AAPSLogger>())
    }

    private fun creds(kAuth: ByteArray? = null) = Libre3CredentialStore.Credentials(
        serial = "0D0000ABCD", mac = "AA:BB:CC:DD:EE:FF", blePin = byteArrayOf(1, 2, 3, 4),
        kAuth = kAuth, startedEpochMs = 1_790_000_000_000L, warmupMinutes = 60, lifeDays = 15
    )

    @Test
    fun `save, update and load round-trip, leaving no temp file behind`() {
        store.save(creds())
        val kAuth = ByteArray(149) { it.toByte() }
        store.updateKAuth(kAuth)

        val loaded = store.load()
        assertThat(loaded).isNotNull()
        assertThat(loaded!!.kAuth).isEqualTo(kAuth)
        assertThat(loaded.serial).isEqualTo("0D0000ABCD")
        assertThat(File(dir, Libre3CredentialStore.FILE_NAME + ".tmp").exists()).isFalse()
    }

    @Test
    fun `an unchanged kAuth is not rewritten`() {
        val kAuth = ByteArray(149) { 7 }
        store.save(creds(kAuth))
        file.setLastModified(1_000L)

        store.updateKAuth(kAuth.copyOf())

        assertThat(file.lastModified()).isEqualTo(1_000L)
    }
}

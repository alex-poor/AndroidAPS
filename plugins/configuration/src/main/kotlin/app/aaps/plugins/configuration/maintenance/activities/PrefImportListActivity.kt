package app.aaps.plugins.configuration.maintenance.activities

import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.maintenance.PrefsFile
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.maintenance.PrefsMetadataKeyImpl
import app.aaps.plugins.configuration.maintenance.compose.PrefsFileListScreen
import app.aaps.plugins.configuration.maintenance.compose.PrefsFileRow
import app.aaps.plugins.configuration.maintenance.data.PrefsStatusImpl
import javax.inject.Inject

/**
 * Pick an exported settings file to import. UI is Compose ([PrefsFileListScreen]); the selection still
 * goes back through `importExportPrefs.selectedImportFile` + `RESULT_OK` rather than through the
 * intent, because a full preferences file is too large to pass as an extra.
 */
class PrefImportListActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fileListProvider: FileListProvider
    @Inject lateinit var importExportPrefs: ImportExportPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val files = fileListProvider.listPreferenceFiles()
        setContentView(ComposeView(this).apply {
            setContent {
                AapsTheme {
                    PrefsFileListScreen(
                        title = rh.gs(R.string.preferences_import_list_title),
                        files = files.map { it.toRow() },
                        onSelect = { index ->
                            importExportPrefs.selectedImportFile = files[index]
                            setResult(RESULT_OK, Intent())
                            finish()
                        },
                        onBack = { finish() }
                    )
                }
            }
        })
    }

    private fun PrefsFile.toRow(): PrefsFileRow {
        val flavour = metadata[PrefsMetadataKeyImpl.AAPS_FLAVOUR]
        val version = metadata[PrefsMetadataKeyImpl.AAPS_VERSION]
        return PrefsFileRow(
            name = name,
            flavour = flavour?.value ?: "",
            flavourOk = flavour?.status == PrefsStatusImpl.OK,
            version = version?.value ?: "",
            versionOk = version?.status == PrefsStatusImpl.OK,
            exportedAgo = metadata[PrefsMetadataKeyImpl.CREATED_AT]?.let { fileListProvider.formatExportedAgo(it.value) } ?: "",
            deviceName = metadata[PrefsMetadataKeyImpl.DEVICE_NAME]?.value ?: ""
        )
    }
}

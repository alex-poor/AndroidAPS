package app.aaps.plugins.configuration.maintenance.activities

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import app.aaps.core.compose.theme.AapsTheme
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.maintenance.ImportExportPrefs
import app.aaps.core.interfaces.maintenance.PrefsFile
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.maintenance.ImportExportPrefsImpl
import app.aaps.plugins.configuration.maintenance.PrefsMetadataKeyImpl
import app.aaps.plugins.configuration.maintenance.cloud.CloudConstants
import app.aaps.plugins.configuration.maintenance.cloud.CloudStorageManager
import app.aaps.plugins.configuration.maintenance.compose.PrefsFileListScreen
import app.aaps.plugins.configuration.maintenance.compose.PrefsFileRow
import app.aaps.plugins.configuration.maintenance.data.PrefsStatusImpl
import app.aaps.plugins.configuration.maintenance.formats.EncryptedPrefsFormat
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cloud counterpart of [PrefImportListActivity], sharing its Compose screen. The listing is paged, so
 * this one also drives the "load more" footer; the paging logic (token, page size, name filter and
 * per-entry download) is carried over unchanged from the legacy RecyclerView version.
 */
class CloudPrefImportListActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fileListProvider: FileListProvider
    @Inject lateinit var importExportPrefs: ImportExportPrefs
    @Inject lateinit var cloudStorageManager: CloudStorageManager
    @Inject lateinit var encryptedPrefsFormat: EncryptedPrefsFormat

    private val files = mutableStateOf<List<PrefsFile>>(emptyList())
    private val loadMoreLabel = mutableStateOf<String?>(null)
    private val loadMoreEnabled = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        files.value = ImportExportPrefsImpl.cloudPrefsFiles.toList()
        refreshLoadMore()

        setContentView(ComposeView(this).apply {
            setContent {
                AapsTheme {
                    PrefsFileListScreen(
                        title = rh.gs(R.string.import_from_cloud),
                        files = files.value.map { it.toRow() },
                        onSelect = { index ->
                            importExportPrefs.selectedImportFile = files.value[index]
                            setResult(RESULT_OK, Intent())
                            finish()
                        },
                        onBack = { finish() },
                        subtitle = fileCountText(),
                        loadMoreLabel = loadMoreLabel.value,
                        loadMoreEnabled = loadMoreEnabled.value,
                        onLoadMore = ::loadMore
                    )
                }
            }
        })
    }

    private fun fileCountText(): String? {
        val total = ImportExportPrefsImpl.cloudTotalFilesCount
        val current = files.value.size
        if (total <= 0) return null
        return if (current >= total || ImportExportPrefsImpl.cloudNextPageToken == null)
            rh.gs(R.string.cloud_import_file_count_all, current)
        else rh.gs(R.string.cloud_import_file_count, current, total)
    }

    private fun refreshLoadMore() {
        if (ImportExportPrefsImpl.cloudNextPageToken == null) {
            loadMoreLabel.value = null
            return
        }
        val total = ImportExportPrefsImpl.cloudTotalFilesCount
        val current = files.value.size
        val remaining = if (total > 0) minOf(CloudConstants.DEFAULT_PAGE_SIZE, total - current) else CloudConstants.DEFAULT_PAGE_SIZE
        loadMoreLabel.value = rh.gs(R.string.load_more_with_count, remaining, current)
        loadMoreEnabled.value = true
    }

    private fun loadMore() {
        loadMoreEnabled.value = false
        loadMoreLabel.value = rh.gs(R.string.loading)
        lifecycleScope.launch {
            val nextToken = ImportExportPrefsImpl.cloudNextPageToken ?: run { loadMoreLabel.value = null; return@launch }
            val provider = cloudStorageManager.getActiveProvider() ?: run { loadMoreLabel.value = null; return@launch }

            val alreadyLoaded = files.value.size
            val page = provider.listSettingsFiles(CloudConstants.DEFAULT_PAGE_SIZE, nextToken)
            ImportExportPrefsImpl.cloudNextPageToken = page.nextPageToken

            val namePattern = Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{6}.*\\.json$", RegexOption.IGNORE_CASE)
            val toProcess = page.files.filter { namePattern.containsMatchIn(it.name) }
            val appended = mutableListOf<PrefsFile>()
            toProcess.forEachIndexed { i, f ->
                loadMoreLabel.value = rh.gs(R.string.loading_progress, alreadyLoaded + i + 1, alreadyLoaded + toProcess.size)
                try {
                    provider.downloadFile(f.id)?.let { bytes ->
                        val content = String(bytes, Charsets.UTF_8)
                        appended.add(PrefsFile(f.name, content, encryptedPrefsFormat.loadMetadata(content)))
                    }
                } catch (_: Exception) {
                    // Ignore single entry error
                }
            }
            files.value = files.value + appended
            refreshLoadMore()
        }
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

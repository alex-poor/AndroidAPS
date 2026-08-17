package app.aaps.core.interfaces.maintenance

import androidx.documentfile.provider.DocumentFile
import java.io.File

interface FileListProvider {

    val resultPath: File
    fun ensurePreferenceDirExists(): DocumentFile?
    fun ensureExportDirExists(): DocumentFile?
    fun ensureTempDirExists(): DocumentFile?
    fun ensureExtraDirExists(): DocumentFile?

    fun newPreferenceFile(): DocumentFile?
    fun newExportCsvFile(): DocumentFile?

    fun ensureResultDirExists(): File
    fun newResultFile(): File
    fun listPreferenceFiles(): MutableList<PrefsFile>
    fun checkMetadata(metadata: Map<PrefsMetadataKey, PrefMetadata>): Map<PrefsMetadataKey, PrefMetadata>
    fun formatExportedAgo(utcTime: String): String
}
package app.aaps.plugins.configuration.maintenance.cloud

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.configuration.R
import app.aaps.plugins.configuration.maintenance.cloud.compose.ConfigSheetDialog
import app.aaps.plugins.configuration.maintenance.cloud.compose.ExportOptionsLabels
import app.aaps.plugins.configuration.maintenance.cloud.compose.ExportOptionsSheet
import app.aaps.plugins.configuration.maintenance.cloud.compose.ExportOptionsState
import app.aaps.plugins.configuration.activities.DaggerAppCompatActivityWithResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExportOptionsDialog @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val sp: SP,
    private val cloudStorageManager: CloudStorageManager
) {
    
    companion object {
        private const val LOG_PREFIX = CloudConstants.LOG_PREFIX
        
        // SharedPreferences keys for export destination settings
        const val PREF_ALL_CLOUD_ENABLED = "export_all_cloud_enabled"
        const val PREF_LOG_EMAIL_ENABLED = "export_log_email_enabled"
        const val PREF_LOG_CLOUD_ENABLED = "export_log_cloud_enabled"
        const val PREF_SETTINGS_LOCAL_ENABLED = "export_settings_local_enabled"
        const val PREF_SETTINGS_CLOUD_ENABLED = "export_settings_cloud_enabled"
        const val PREF_CSV_LOCAL_ENABLED = "export_csv_local_enabled"
        const val PREF_CSV_CLOUD_ENABLED = "export_csv_cloud_enabled"
    }
    
    /**
     * Show export options configuration dialog
     */
    fun showExportOptionsDialog(
        activity: DaggerAppCompatActivityWithResult,
        onSettingsChanged: () -> Unit = {}
    ) {
        val isCloudConfigured = cloudStorageManager.isCloudStorageActive()

        var allCloud = sp.getBoolean(PREF_ALL_CLOUD_ENABLED, false)
        var logEmail = sp.getBoolean(PREF_LOG_EMAIL_ENABLED, true)
        var logCloud = sp.getBoolean(PREF_LOG_CLOUD_ENABLED, false)
        var settingsLocal = sp.getBoolean(PREF_SETTINGS_LOCAL_ENABLED, true)
        var settingsCloud = sp.getBoolean(PREF_SETTINGS_CLOUD_ENABLED, false)
        var csvLocal = sp.getBoolean(PREF_CSV_LOCAL_ENABLED, true)
        var csvCloud = sp.getBoolean(PREF_CSV_CLOUD_ENABLED, false)

        // Same initial adjustments the legacy dialog made before showing.
        if (!isCloudConfigured) {
            logCloud = false; settingsCloud = false; csvCloud = false
            if (!logEmail && !logCloud) logEmail = true
            if (!settingsLocal && !settingsCloud) settingsLocal = true
            if (!csvLocal && !csvCloud) csvLocal = true
        }
        if (allCloud && isCloudConfigured) {
            logCloud = true; settingsCloud = true; csvCloud = true; logEmail = false; csvLocal = false
        }

        ConfigSheetDialog.show(activity, "exportOptions") { dismiss ->
            ExportOptionsSheet(
                labels = ExportOptionsLabels(
                    title = rh.gs(R.string.export_options),
                    allCloud = rh.gs(R.string.export_all_cloud),
                    logSection = rh.gs(R.string.log_export_option),
                    email = rh.gs(R.string.email_option),
                    cloud = rh.gs(R.string.cloud_storage_option),
                    settingsSection = rh.gs(R.string.user_settings_export_option),
                    local = rh.gs(R.string.local_storage_option),
                    csvSection = rh.gs(R.string.user_entries_csv_export_option),
                    ok = rh.gs(app.aaps.core.ui.R.string.ok),
                    cancel = rh.gs(app.aaps.core.ui.R.string.cancel),
                    cloudNotConfigured = rh.gs(R.string.cloud_connection_failed)
                ),
                isCloudConfigured = isCloudConfigured,
                initial = ExportOptionsState(allCloud, logEmail, logCloud, settingsLocal, settingsCloud, csvLocal, csvCloud),
                onWarn = { ToastUtils.warnToast(activity, it) },
                onSave = { s ->
                    sp.putBoolean(PREF_ALL_CLOUD_ENABLED, s.allCloud)
                    sp.putBoolean(PREF_LOG_EMAIL_ENABLED, s.logEmail)
                    sp.putBoolean(PREF_LOG_CLOUD_ENABLED, s.logCloud)
                    sp.putBoolean(PREF_SETTINGS_LOCAL_ENABLED, s.settingsLocal)
                    sp.putBoolean(PREF_SETTINGS_CLOUD_ENABLED, s.settingsCloud)
                    sp.putBoolean(PREF_CSV_LOCAL_ENABLED, s.csvLocal)
                    sp.putBoolean(PREF_CSV_CLOUD_ENABLED, s.csvCloud)
                    onSettingsChanged()
                    ToastUtils.infoToast(activity, rh.gs(R.string.export_options_updated))
                },
                onClose = dismiss
            )
        }
    }
    
    /**
     * Get current export destination preferences
     */
    fun isAllCloudEnabled(): Boolean {
        val value = sp.getBoolean(PREF_ALL_CLOUD_ENABLED, false)
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: isAllCloudEnabled=$value")
        return value
    }
    
    fun isLogCloudEnabled(): Boolean {
        val value = sp.getBoolean(PREF_LOG_CLOUD_ENABLED, false)
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: isLogCloudEnabled=$value")
        return value
    }
    
    fun isSettingsLocalEnabled(): Boolean {
        val value = sp.getBoolean(PREF_SETTINGS_LOCAL_ENABLED, true)
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: isSettingsLocalEnabled=$value")
        return value
    }
    
    fun isSettingsCloudEnabled(): Boolean {
        val value = sp.getBoolean(PREF_SETTINGS_CLOUD_ENABLED, false)
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: isSettingsCloudEnabled=$value")
        return value
    }
    
    /**
     * Check if both local and cloud are enabled for settings export
     */
    fun isSettingsBothEnabled(): Boolean {
        val localEnabled = sp.getBoolean(PREF_SETTINGS_LOCAL_ENABLED, true)
        val cloudEnabled = sp.getBoolean(PREF_SETTINGS_CLOUD_ENABLED, false)
        val bothEnabled = localEnabled && cloudEnabled
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: isSettingsBothEnabled=$bothEnabled (local=$localEnabled, cloud=$cloudEnabled)")
        return bothEnabled
    }
    
    fun isCsvCloudEnabled(): Boolean {
        val value = sp.getBoolean(PREF_CSV_CLOUD_ENABLED, false)
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: isCsvCloudEnabled=$value")
        return value
    }
    
    /**
     * Reset all export settings to local/email mode
     * - Disable "All Cloud"
     * - Set Log to "Email" (disable cloud)
     * - Set Settings to "Local" (disable cloud)
     * - Set CSV to "Local" (disable cloud)
     */
    fun resetToLocalSettings() {
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: Resetting all settings to local/email mode")
        sp.putBoolean(PREF_ALL_CLOUD_ENABLED, false)
        sp.putBoolean(PREF_LOG_EMAIL_ENABLED, true)
        sp.putBoolean(PREF_LOG_CLOUD_ENABLED, false)
        sp.putBoolean(PREF_SETTINGS_LOCAL_ENABLED, true)
        sp.putBoolean(PREF_SETTINGS_CLOUD_ENABLED, false)
        sp.putBoolean(PREF_CSV_LOCAL_ENABLED, true)
        sp.putBoolean(PREF_CSV_CLOUD_ENABLED, false)
    }
    
    /**
     * Enable "All Cloud" option for cloud export
     * - Enable "All Cloud" switch
     * - Set Log to "Cloud" (disable email)
     * - Set Settings to "Cloud" (disable local)
     * - Set CSV to "Cloud" (disable local)
     */
    fun enableAllCloud() {
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: Enabling all cloud mode")
        sp.putBoolean(PREF_ALL_CLOUD_ENABLED, true)
        sp.putBoolean(PREF_LOG_EMAIL_ENABLED, false)
        sp.putBoolean(PREF_LOG_CLOUD_ENABLED, true)
        sp.putBoolean(PREF_SETTINGS_LOCAL_ENABLED, true)
        sp.putBoolean(PREF_SETTINGS_CLOUD_ENABLED, true)
        sp.putBoolean(PREF_CSV_LOCAL_ENABLED, false)
        sp.putBoolean(PREF_CSV_CLOUD_ENABLED, true)
    }

    /**
     * Enable local storage for settings export
     * This is called when user declines cloud export but we still want to ensure local storage is enabled
     */
    fun enableLocalStorage() {
        aapsLogger.info(LTag.CORE, "$LOG_PREFIX ExportDestination: Enabling local storage")
        sp.putBoolean(PREF_SETTINGS_LOCAL_ENABLED, true)
    }
}

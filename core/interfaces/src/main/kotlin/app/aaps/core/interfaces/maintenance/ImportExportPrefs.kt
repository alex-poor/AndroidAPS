package app.aaps.core.interfaces.maintenance

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject

interface ImportExportPrefs {

    fun doImportSharedPreferences(activity: FragmentActivity)
    fun importSharedPreferences(activity: FragmentActivity)
    fun prefsFileExists(): Boolean
    fun verifyStoragePermissions(fragment: Fragment, onGranted: Runnable)
    fun exportSharedPreferences(f: Fragment)
    fun exportSharedPreferencesNonInteractive(context: Context, password: String): Boolean
    fun exportUserEntriesCsv(activity: FragmentActivity)
    fun exportApsResult(algorithm: String?, input: JSONObject, output: JSONObject?)

    /**
     * Store for selected file from UI
     */
    var selectedImportFile: PrefsFile?
}
package app.aaps.core.utils.fabric

import android.content.Context
import java.util.UUID

/**
 * A stable, anonymous per-installation identifier.
 *
 * Upstream takes this from Firebase Installations. This build has no Firebase, so the id is a
 * random UUID minted on first use and kept in the app's own preferences — same contract (stable
 * across restarts, reset on reinstall), no network call and no Google dependency.
 */
object InstanceId {

    private const val PREFS = "instance_id"
    private const val KEY = "id"

    var instanceId: String = ""
        private set

    fun init(context: Context) {
        if (instanceId.isNotEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        instanceId = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY, it).apply()
        }
    }
}

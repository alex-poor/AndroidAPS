package app.aaps.implementation.utils.fabric

import android.os.Bundle
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import dagger.Reusable
import javax.inject.Inject

/**
 * Local-only implementation of [FabricPrivacy]: everything is written to the AAPS log, nothing
 * leaves the device.
 *
 * Upstream routes these calls to Firebase Analytics and Crashlytics. On a personal single-user
 * loop that is pure cost — it shipped health telemetry to Google for a crash report nobody reads,
 * cost ~4.9 MB of dex (GMS + Firebase + transport), ran four Firebase background threads, and kept
 * two telemetry databases (`google_app_measurement_local.db`,
 * `com.google.android.datatransport.events`) in the app's data directory.
 *
 * The interface is kept intact so the ~40 call sites are unchanged, and the information they report
 * is not lost — it goes to AndroidAPS.log, which is on the device, survives longer than logcat's
 * ~30 minutes, and is the log actually consulted when something goes wrong.
 */
@Reusable
class FabricPrivacyImpl @Inject constructor(
    private val aapsLogger: AAPSLogger
) : FabricPrivacy {

    override fun setUserProperty(key: String, value: String) {
        aapsLogger.debug(LTag.CORE, "Property $key = $value")
    }

    override fun logCustom(name: String, event: Bundle) {
        aapsLogger.debug(LTag.CORE, "Event $name: $event")
    }

    override fun logCustom(event: String) {
        aapsLogger.debug(LTag.CORE, "Event $event")
    }

    override fun logMessage(message: String) {
        aapsLogger.info(LTag.CORE, message)
    }

    override fun logException(throwable: Throwable) {
        aapsLogger.error("Exception: ", throwable)
    }

    /**
     * Analytics is never collected, so this is always false. Call sites use it to decide whether to
     * report; they now skip that work entirely.
     */
    override fun fabricEnabled(): Boolean = false

}

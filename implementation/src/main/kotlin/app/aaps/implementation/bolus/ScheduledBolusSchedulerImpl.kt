package app.aaps.implementation.bolus

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.bolus.ScheduledBolus
import app.aaps.core.interfaces.bolus.ScheduledBolusScheduler
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists pending split-bolus second doses in a local JSON list ([StringNonKey.ScheduledBoluses]) and
 * arms an [AlarmManager] reminder for each. [setAndAllowWhileIdle] is used rather than an exact alarm:
 * this is a confirm-reminder, not an injection, so a few minutes of Doze jitter is acceptable and no
 * exact-alarm permission is needed. The alarm targets [ScheduledBolusReceiver] by class name (the app
 * module isn't on this module's classpath) which calls back into [onAlarmFired].
 */
@Singleton
class ScheduledBolusSchedulerImpl @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val uiInteraction: UiInteraction,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger
) : ScheduledBolusScheduler {

    private val alarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    @Synchronized
    override fun schedule(dueAt: Long, units: Double, note: String): ScheduledBolus {
        val list = loadAll()
        // High-range id: distinct from the small built-in notification ids, reused as alarm request code
        // and notification id. Bump on collision so two near-simultaneous schedules don't clash.
        var id = (ID_BASE + dateUtil.now() % 1_000_000L).toInt()
        while (list.any { it.id == id }) id++
        val bolus = ScheduledBolus(id = id, dueAt = dueAt, units = units, note = note)
        list.add(bolus)
        saveAll(list)
        armAlarm(bolus)
        aapsLogger.debug(LTag.CORE, "Scheduled split bolus $units U at ${dateUtil.dateAndTimeString(dueAt)} (id=$id)")
        return bolus
    }

    @Synchronized
    override fun cancel(id: Int) {
        val list = loadAll()
        if (list.removeAll { it.id == id }) {
            saveAll(list)
            cancelAlarm(id)
            uiInteraction.dismissNotification(id)
            aapsLogger.debug(LTag.CORE, "Cancelled scheduled bolus id=$id")
        }
    }

    @Synchronized
    override fun list(): List<ScheduledBolus> = loadAll().sortedBy { it.dueAt }

    @Synchronized
    override fun rescheduleAll() {
        val now = dateUtil.now()
        // Snapshot ids: onAlarmFired mutates the stored list, so iterate a copy.
        for (bolus in loadAll().toList()) {
            if (bolus.dueAt <= now) onAlarmFired(bolus.id) // was due while powered off → fire now
            else armAlarm(bolus)
        }
    }

    @Synchronized
    override fun onAlarmFired(id: Int) {
        val list = loadAll()
        val bolus = list.firstOrNull { it.id == id }
        if (bolus == null) {
            aapsLogger.debug(LTag.CORE, "onAlarmFired: no pending bolus id=$id")
            return
        }
        // Remove first: the alarm has served its purpose and must not reschedule on the next boot. The dose
        // is only given if the user now confirms it in the dialog the reminder opens — never autonomously.
        list.removeAll { it.id == id }
        saveAll(list)
        val validTo = maxOf(bolus.dueAt, dateUtil.now()) + T.hours(2).msecs()
        uiInteraction.addScheduledBolusDeliverNotification(id = bolus.id, units = bolus.units, note = bolus.note, validTo = validTo)
    }

    private fun armAlarm(bolus: ScheduledBolus) {
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bolus.dueAt, pendingIntent(bolus.id))
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to arm scheduled-bolus alarm id=${bolus.id}", e)
        }
    }

    private fun cancelAlarm(id: Int) = alarmManager.cancel(pendingIntent(id))

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun pendingIntent(id: Int): PendingIntent {
        val intent = Intent(ACTION)
            .setClassName(context.packageName, RECEIVER_CLASS)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun loadAll(): MutableList<ScheduledBolus> {
        val out = mutableListOf<ScheduledBolus>()
        try {
            val arr = JSONArray(preferences.get(StringNonKey.ScheduledBoluses))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(ScheduledBolus(id = o.getInt("id"), dueAt = o.getLong("dueAt"), units = o.getDouble("units"), note = o.optString("note", "")))
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.CORE, "Failed to parse scheduled boluses; resetting", e)
            saveAll(mutableListOf())
        }
        return out
    }

    private fun saveAll(list: List<ScheduledBolus>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("dueAt", it.dueAt).put("units", it.units).put("note", it.note)) }
        preferences.put(StringNonKey.ScheduledBoluses, arr.toString())
    }

    companion object {

        const val ACTION = "app.aaps.action.SCHEDULED_BOLUS_FIRE"
        const val EXTRA_ID = "scheduled_bolus_id"
        const val RECEIVER_CLASS = "app.aaps.receivers.ScheduledBolusReceiver"
        private const val ID_BASE = 2_000_000L
    }
}

package app.aaps.core.interfaces.bolus

/**
 * Schedules the deferred half of a split bolus and raises a confirm-reminder when it comes due.
 *
 * Design: the second dose is NEVER delivered autonomously. At [schedule] time the dose is persisted
 * to a small local list and an Android alarm is armed; when the alarm fires ([onAlarmFired]) the app
 * raises a sound-backed notification whose single action opens the pre-filled Insulin dialog, so the
 * user's own hold-to-confirm remains the thing that actually injects insulin. Pending doses are local
 * only — they are never synced to Nightscout, because they have not happened yet.
 */
interface ScheduledBolusScheduler {

    /**
     * Persist and arm a reminder for [units] U at [dueAt] (epoch-ms). [note] is a short label shown on
     * the reminder. Returns the created record (its [ScheduledBolus.id] can be passed to [cancel]).
     */
    fun schedule(dueAt: Long, units: Double, note: String): ScheduledBolus

    /** Drop a pending dose and cancel its alarm. No-op if already fired or unknown. */
    fun cancel(id: Int)

    /** Pending doses not yet fired, soonest first — for a "pending second dose" UI and for cancelling. */
    fun list(): List<ScheduledBolus>

    /**
     * Re-arm alarms for everything still pending — call on boot/app-start, since AlarmManager alarms do
     * not survive a reboot. A dose whose [ScheduledBolus.dueAt] is already in the past (phone was off
     * when it came due) is fired immediately.
     */
    fun rescheduleAll()

    /** The alarm for [id] fired: raise the confirm-reminder and remove the record. Called by the receiver. */
    fun onAlarmFired(id: Int)
}

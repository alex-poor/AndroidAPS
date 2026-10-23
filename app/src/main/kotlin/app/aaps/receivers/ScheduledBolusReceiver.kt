package app.aaps.receivers

import android.content.Context
import android.content.Intent
import app.aaps.core.interfaces.bolus.ScheduledBolusScheduler
import app.aaps.implementation.bolus.ScheduledBolusSchedulerImpl
import dagger.android.DaggerBroadcastReceiver
import javax.inject.Inject

/**
 * Fired by AlarmManager when a split-bolus second dose comes due. Delegates to the scheduler, which
 * raises the confirm-reminder and drops the record. Explicit, non-exported — only our own PendingIntent
 * (built in [ScheduledBolusSchedulerImpl]) ever triggers it.
 */
class ScheduledBolusReceiver : DaggerBroadcastReceiver() {

    @Inject lateinit var scheduledBolusScheduler: ScheduledBolusScheduler

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ScheduledBolusSchedulerImpl.ACTION) return
        val id = intent.getIntExtra(ScheduledBolusSchedulerImpl.EXTRA_ID, -1)
        if (id != -1) scheduledBolusScheduler.onAlarmFired(id)
    }
}

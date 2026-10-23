package app.aaps.core.interfaces.bolus

/**
 * A single insulin dose the user has chosen to deliver *later* — the second half of a split bolus.
 *
 * It is NOT a treatment: nothing has been injected and nothing is written to the treatment DB or
 * Nightscout until the user actually confirms it at the due time. The record only exists so the
 * app can raise a reminder at [dueAt]; it lives in a small local JSON list (see
 * [ScheduledBolusScheduler]) and is dropped once its reminder has fired.
 *
 * @param id          stable identifier; also the AlarmManager request code, so each pending dose has
 *                    its own cancelable alarm.
 * @param dueAt       epoch-ms when the reminder should fire.
 * @param units       insulin units to offer for delivery (already pump-rounded by the caller).
 * @param note        short human label carried onto the reminder, e.g. "Split 2/2 · dinner".
 */
data class ScheduledBolus(
    val id: Int,
    val dueAt: Long,
    val units: Double,
    val note: String
)

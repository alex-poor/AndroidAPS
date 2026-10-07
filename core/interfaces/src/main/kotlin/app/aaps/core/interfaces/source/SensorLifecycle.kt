package app.aaps.core.interfaces.source

/**
 * A CGM source's authoritative view of its own sensor's lifecycle, for the UI to show warm-up and
 * expiry honestly.
 *
 * This exists because the Overview used to reconstruct warm-up/expiry from the latest SENSOR_CHANGE
 * therapy event plus a generic warm-up preference. During a fresh sensor's warm-up there is no new
 * therapy event yet (none is written until the sensor connects and reports patch status), so the
 * Overview read the PREVIOUS sensor's event and showed "Expired"; and the warm-up preference
 * defaults to 0, so warm-up never showed at all and a warming-up sensor read as "No recent reading".
 *
 * A source that knows its activation time, warm-up window and life (e.g. the native Libre 3 source,
 * from the credentials onboarding writes immediately) returns this instead. A source that does not
 * returns null and the Overview falls back to the therapy-event + preference path unchanged.
 */
sealed interface SensorLifecycle {

    /** Counting up to the first reading. [fractionElapsed] 0..1 drives a FILLING ring. */
    data class WarmingUp(val minutesRemaining: Int, val fractionElapsed: Float) : SensorLifecycle

    /** Normal running. [fractionRemaining] 1..0 drives a DEPLETING ring; [label] e.g. "6d 3h". */
    data class Active(val remainingHours: Long, val fractionRemaining: Float, val label: String) : SensorLifecycle

    /** Past its life — replace it. */
    data object Expired : SensorLifecycle
}

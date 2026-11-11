package app.aaps.libre3

/**
 * Keeps the loop suspended when the Libre 3 drops below its own reportable floor during a hypo.
 *
 * The sensor cannot report below ~2.2 mmol/L (39 mg/dL): below that it emits an out-of-range
 * sentinel, which [Libre3GlucoseRecord.isValid] rejects. Dropping that reading is the safe choice
 * for a sensor FAULT (we must not fabricate a low), but it is the DANGEROUS choice for a real
 * crash below the floor: with no data the loop goes stale, the last zero temp expires, and the
 * pump reverts to scheduled basal — delivering insulin at the worst possible moment. This is the
 * 2026-11-12 incident: BG fell to 2.2, the sensor dropped out, and basal resumed into the hypo.
 *
 * The record carries no low-vs-fault flag, so the only reliable discriminator is the trajectory
 * INTO the drop-out: a genuine crash arrives via a recent, low last reading (…42, 40, 41, then
 * out-of-range); a fault at normal BG jumps straight from a normal value to the sentinel. When the
 * drop-out immediately follows a recent, low reading we synthesise a severe-low so the loop stays
 * suspended; otherwise we drop as before. The entry decision uses [recencyMs]; once entered, the
 * hold persists (regardless of how long since the last valid reading) up to [maxHoldMs], so a
 * genuinely dead sensor cannot suspend delivery indefinitely.
 *
 * Pure and dependency-free so it unit-tests off-device, like [Libre3LagOverride].
 */
class Libre3LowHold(
    /** Last in-range reading at or below this (mg/dL) means an out-of-range drop is a real low, not a fault. */
    private val lowGateMgdl: Int = 80,
    /** The low last reading must be no older than this for the drop-out to START a hold. */
    private val recencyMs: Long = 10 * 60_000L,
    /** Cap on how long a single continuous drop-out keeps holding (dead-sensor guard). */
    private val maxHoldMs: Long = 25 * 60_000L,
    /** The severe-low reported while holding — low enough to force a hard suspend. */
    private val floorMgdl: Int = 40,
) {

    private var lastValidMgdl = -1
    private var lastValidAtMs = 0L
    private var holdStartMs = 0L
    private var lastSynthKey = Long.MIN_VALUE

    /** True while a synthesised severe-low is being reported (for honest logging/state). */
    var holding: Boolean = false
        private set

    /** Feed every in-range reading. Clears any active hold. */
    fun onValid(mgdl: Int, nowMs: Long) {
        lastValidMgdl = mgdl
        lastValidAtMs = nowMs
        holdStartMs = 0L
        holding = false
    }

    /**
     * Feed every out-of-range reading.
     *
     * @param key a per-reading identity (the record's lifeCount) so the sensor's repeat
     *   notifications do not double-insert within the same minute.
     * @return the severe-low (mg/dL) to insert as a real glucose value, or null to drop the reading.
     */
    fun onOutOfRange(nowMs: Long, key: Long): Int? {
        if (holdStartMs == 0L) {
            // Entry: only begin a hold when the drop-out immediately follows a recent, low reading.
            val lowAndRecent = lastValidMgdl in 1 until lowGateMgdl && (nowMs - lastValidAtMs) in 0..recencyMs
            if (!lowAndRecent) {
                holding = false
                return null
            }
            holdStartMs = nowMs
        }
        if (nowMs - holdStartMs > maxHoldMs) {   // held long enough; treat as a failed sensor, stop holding
            holding = false
            return null
        }
        holding = true
        if (key == lastSynthKey) return null     // already reported this minute
        lastSynthKey = key
        return floorMgdl
    }
}

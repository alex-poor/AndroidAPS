package app.aaps.libre3

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Manual-BG lag override for a *rising* excursion.
 *
 * On a fast rise, blood glucose leads interstitial, so the CGM reads stale-low and the loop
 * under-doses through exactly the window it should act. A finger-prick taken then is the true current
 * value. This carries that correction forward — but only while it is physiologically justified, and
 * it fades out **before** the CGM peak so it can't stack insulin into the coming fall.
 *
 * Model (all in mg/dL, rate in mg/dL/min), grounded in the literature discussed in
 * `report/libre3-native-plan.md`:
 * ```
 *   gap          = manualBg − sensorNow                       measured ground-truth offset
 *   slope        = least-squares slope over the last N raw readings   (denoises the 1-min jitter)
 *   rateFactor   = clamp( (slope/slopeEntry − decel) / (1 − decel), 0..1 )
 *   timeFactor   = 2^(−elapsedMin / halfLifeMin)
 *   offset(t)    = gap · rateFactor · timeFactor              (≥ 0)
 *   corrected    = rawSensor + offset
 * ```
 * `rateFactor` reaches 0 when the rise decelerates to `decel×` the entry rate — a *smooth* fade that
 * front-runs the CGM peak (the CGM's own rate only zeroes at its peak, which lags the blood peak, so
 * a rate=0 rule would fade too late). `timeFactor` is an independent backstop for a fast, sharp peak.
 * It is **not** peak-safe by construction — no CGM-derived rule can be, since the CGM hasn't seen the
 * peak yet — it is *bounded and decaying*, and the loop's own maxIOB / descent-guard catch the small
 * post-peak residual.
 *
 * The slope uses a window of [Config.windowSize] one-minute samples specifically because the Libre 3
 * per-minute stream is noisy (up/down/up/down); a single minute-delta would false-trigger. The
 * regression can't even be evaluated on fewer than that, which is the "≥ N ticks of evidence" rule.
 *
 * Pure and stateful: feed it every raw reading via [onReading]; arm it from a finger-prick via
 * [armFromManualBg]. No Android, no DB — [Libre3SourcePlugin] does the persistence.
 */
class Libre3LagOverride(val cfg: Config = Config()) {

    data class Config(
        /** Samples in the regression window — also the minimum evidence before anything acts. */
        val windowSize: Int = 5,
        /** Don't engage below this rise rate: the CGM is accurate here (Pleus: ARD ~8.5% < 1 mg/dL/min). */
        val armThresholdMgdlPerMin: Double = 1.0,
        /** Offset fades to 0 once the rise slows to this fraction of the entry rate. */
        val decelFraction: Double = 0.5,
        /** Independent time-decay half-life (min) — fades the offset on a clock regardless of rate. */
        val decayHalfLifeMin: Double = 15.0,
        /** Hard stop: an excursion's rise phase shouldn't need the override longer than this. */
        val maxDurationMin: Double = 30.0,
        /** Plausibility ceiling on the measured gap: gap ≤ slopeEntry × this (physiological lag max). */
        val tauMaxMin: Double = 15.0,
        val validRange: IntRange = 39..501
    )

    sealed interface ArmResult {
        /** Armed. [gapMgdl] is the effective (possibly plausibility-clamped) offset applied. */
        data class Armed(val gapMgdl: Double, val clamped: Boolean) : ArmResult
        data class Rejected(val reason: String) : ArmResult
    }

    private class Active(val startMs: Long, val gapMgdl: Double, val slopeEntry: Double)

    // Raw readings only (never corrected values) — the slope must reflect the true sensor trajectory.
    private val window = ArrayDeque<Pair<Long, Int>>()
    private var active: Active? = null

    fun isActive(): Boolean = active != null

    /**
     * Record a raw sensor reading and get the value to store. Returns the raw value unchanged unless
     * an override is active, in which case it returns raw + the decaying offset (and expires the
     * override the moment the offset has faded to nothing).
     */
    fun onReading(timeMs: Long, rawMgdl: Int): Int {
        window.addLast(timeMs to rawMgdl)
        while (window.size > cfg.windowSize) window.removeFirst()

        val a = active ?: return rawMgdl
        val slopeNow = slope() ?: return rawMgdl
        val elapsedMin = (timeMs - a.startMs) / 60_000.0
        val ratio = slopeNow / a.slopeEntry

        // Smooth fade-out that front-runs the peak, plus the hard backstops.
        if (slopeNow < 0.0 || ratio <= cfg.decelFraction || elapsedMin >= cfg.maxDurationMin) {
            active = null
            return rawMgdl
        }
        val rateFactor = ((ratio - cfg.decelFraction) / (1.0 - cfg.decelFraction)).coerceIn(0.0, 1.0)
        val timeFactor = exp(-ln(2.0) * elapsedMin / cfg.decayHalfLifeMin)
        val offset = a.gapMgdl * rateFactor * timeFactor
        return (rawMgdl + offset).roundToInt().coerceIn(cfg.validRange.first, cfg.validRange.last)
    }

    /**
     * Arm from a finger-prick. Uses the current window for the entry slope and the latest raw reading
     * as the sensor's current value. Only arms on a genuine fast rise; a flat/falling trace, a
     * non-higher prick, or too few readings is rejected (the caller can still log it as a BG check).
     */
    fun armFromManualBg(timeMs: Long, manualMgdl: Int): ArmResult {
        if (window.size < cfg.windowSize) return ArmResult.Rejected("need ${cfg.windowSize} readings first")
        val slopeEntry = slope() ?: return ArmResult.Rejected("cannot determine trend")
        if (slopeEntry < cfg.armThresholdMgdlPerMin)
            return ArmResult.Rejected("not rising fast enough (${"%.1f".format(slopeEntry)} < ${cfg.armThresholdMgdlPerMin} mg/dL/min)")

        val sensorNow = window.last().second
        val gap = (manualMgdl - sensorNow).toDouble()
        if (gap <= 0.0) return ArmResult.Rejected("finger-prick not above the sensor — no rise lag to correct")

        val maxPlausible = slopeEntry * cfg.tauMaxMin
        val clamped = gap > maxPlausible
        val effGap = if (clamped) maxPlausible else gap

        active = Active(startMs = timeMs, gapMgdl = effGap, slopeEntry = slopeEntry)
        return ArmResult.Armed(effGap, clamped)
    }

    /** Drop an active override but keep the reading window — for a disconnect on the same sensor. */
    fun cancel() {
        active = null
    }

    /** Drop the override and clear the window — for a sensor change. */
    fun reset() {
        active = null
        window.clear()
    }

    /** Least-squares slope (mg/dL per minute) over the window; null until it is full. */
    private fun slope(): Double? {
        if (window.size < cfg.windowSize) return null
        val t0 = window.first().first
        val xs = window.map { (it.first - t0) / 60_000.0 }   // minutes
        val ys = window.map { it.second.toDouble() }
        val xBar = xs.average()
        val yBar = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - xBar
            num += dx * (ys[i] - yBar)
            den += dx * dx
        }
        return if (den == 0.0) null else num / den
    }
}

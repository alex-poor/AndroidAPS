package app.aaps.plugins.aps.camapsfx

/**
 * CamAPS's day-to-day TDD (total-daily-dose) adaptation — the operating-point learning (guide §9). Run every
 * tick (`MPC::ModifyTDD` 0x144f90), it scales the raw TDD into the adapted `c`/`conv`/`x14` the whole controller
 * works in, from: the target-glucose deviation, exercise/hypo fractions, and a 4-tap look-ahead over a PERSISTED
 * per-hour `FractionTDD` store (updated from past glucose performance — the slow multi-day learning). On a cold
 * single tick with no accumulated fraction it is the IDENTITY (all factors 1.0); the adaptation lives in the
 * FractionTDD drift over days.
 */
object TddAdapt {
    val weightsLookAhead = floatArrayOf(0.5f, 0.3f, 0.15f, 0.05f)   // weightsLookAheadFracTDD (sum 1.0)
    const val fractionTDDhypo = 0.6f
    const val fractionTDDexercise = 0.5f
    const val maxTDDcorrection = 1.05f       // per-update cap when fraction ≥ 1.0
    const val maxTDDcorrectionLow = 1.20f    // per-update cap when fraction < 1.0

    /**
     * `MPC::UpdateFractionTDD` — the per-hour learning update (the slow multi-day drift). Given the past hour's
     * glucose-performance summary (from `GetHourlyRecordSummary`): [deviation] (glucose vs target, local_74),
     * the two min-glucose measures [minGluA]/[minGluB], the sample [count], and [allowAbove1] (permits fraction
     * > 1.0). Nudges the stored [currentFrac] by a bounded correction and clamps. `count ≤ 4` ⇒ no update.
     *   correction = clamp(deviation·0.10592 + 1, 0.7, maxCorr)   [maxCorr = 1.05 if frac≥1 else 1.20]
     *   — but when glucose ran BELOW target (deviation<0) yet never actually went low (min ≥ 4.4), don't cut
     *     (correction = 1.0); and any real hypo (min < 3.9) caps the correction at 0.7.
     *   fraction = clamp(currentFrac · correction, 0.4, 1.8), then capped at 1.0 unless [allowAbove1].
     */
    fun updateFractionTDD(currentFrac: Float, deviation: Float, minGluA: Float, minGluB: Float,
                          count: Int, allowAbove1: Boolean): Float {
        if (count <= 4) return currentFrac
        val corr = deviation * 0.10592001f + 1.0f
        val maxCorr = if (currentFrac >= 1.0f) maxTDDcorrection else maxTDDcorrectionLow
        var f = if (corr <= maxCorr) (if (corr < 0.7f) 0.7f else corr) else maxCorr
        if (deviation < 0.0f) {
            // reduce only if glucose actually went low; if the min stayed ≥ 4.4, hold (no cut)
            val holdNoCut = minGluA >= 4.4f && !minGluB.isNaN() && minGluB >= 4.4f
            if (holdNoCut) f = 1.0f
        }
        if ((minGluB < 3.9f || minGluA < 3.9f) && 0.7f < f) f = 0.7f
        var nf = currentFrac * f
        if (nf > 1.8f) nf = 1.8f
        if (nf < 0.4f) nf = 0.4f
        if (nf > 1.0f && !allowAbove1) nf = 1.0f
        return nf
    }

    /**
     * `MPC::ModifyTDD` — scale [rawTDD] into the adapted TDD.
     * [targetProfile48] = the target-glucose profile (this+0xd8, 48 half-hour slots; <0 ⇒ default 5.8, else
     * clamped [4.4,11]). [prevCGM] = GetPrevCGM (or 5.5 when none / stale > 90 min). [minBG2h]/[minGlu1h] =
     * GetMinBG(2h)/GetMinGlucose(1h). [fracTDD24] = the persisted per-hour FractionTDD (default all 1.0);
     * [paramsLoaded] = DataDatabases::LoadParameters succeeded.
     */
    fun modifyTDD(rawTDD: Float, targetProfile48: FloatArray, nowHour: Int, nowMin: Int, prevCGM: Float,
                  exerciseActive: Boolean, minBG2h: Float, minGlu1h: Float, fracTDD24: FloatArray,
                  paramsLoaded: Boolean): Float {
        var tdd = rawTDD
        // (0) target glucose for this time-of-day
        val tp = targetProfile48[nowHour * 2 + if (nowMin >= 30) 1 else 0]
        val target = if (tp >= 0f) tp.coerceIn(4.4f, 11f) else 5.8f
        // (1) target-deviation scaling: raise TDD when target < 5.8 (tighter)
        tdd *= (5.8f - target) * 0.1324f + 1.0f
        // (2) exercise / hypo fraction (hypo only counts when CGM < 14)
        if (!exerciseActive || prevCGM >= 14.0f) {
            if (minOf(minBG2h, minGlu1h) < 3.9f && prevCGM < 14.0f) tdd *= fractionTDDhypo
        } else {
            tdd *= fractionTDDexercise
        }
        // (3) look-ahead fraction over the persisted per-hour FractionTDD (4-tap, hours now..now-3)
        if (paramsLoaded) {
            var f = 0f
            for (k in 0..3) f += weightsLookAhead[k] * fracTDD24[((nowHour - k) % 24 + 24) % 24]
            tdd *= f
        }
        return tdd
    }
}

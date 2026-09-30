package app.aaps.plugins.aps.camapsfx

/**
 * `DataDatabases::reduce_bg` (0x14cad4) + the `ReadT1DData` load cap — the CGM pre-processing that runs at
 * `DataDatabases::Init`, BEFORE the [GdflWalk]/[Gdfl] estimation-horizon walk consumes CGM.
 *
 * Two stages the clean-room previously skipped (which is why dense 1-min CGM over-snapped the walk):
 *   1. LOAD CAP: CGM is read into a `Vector<float,390>`, so only the most-recent 390 raw samples survive.
 *      With Dexcom's 5-min cadence that is ~32h; with Libre-3's 1-min cadence it is only ~6.5h — see the
 *      standing reminder `camaps-decimates-cgm-revisit`.
 *   2. DECIMATE (reduce_bg): the 390 raw samples are resampled onto a grid starting at `now − 8h` (rounded
 *      DOWN to a 10-min boundary), advancing 15 min per step, tightening to 10 min once within the recent
 *      45-min zone. One raw sample is picked per grid bucket (plus a start-of-data "carry" sample and a
 *      tail sample), giving the ~29 coarse CGM records the estimator actually sees.
 *
 * Validated bit-exact against the binary's decimated CGM DB (29/29 records for tick_0000). Times are absolute
 * minutes (as [InputParser] emits); the binary works in `time_t` seconds but the 10-min rounding, 15/10-min
 * steps and 45-min zone are all whole minutes, so a minute grid is exact for minute-resolution CGM.
 */
object Decimate {
    data class C(val t: Long, val v: Float)

    const val LOAD_CAP = 390
    private const val LOOKBACK = 480L   // 8h
    private const val SPAN45 = 45L
    private const val SPAN15 = 15L
    private const val SPAN10 = 10L

    /** ReadT1DData load cap: keep the most-recent [LOAD_CAP] raw CGM samples (file order = chronological). */
    fun loadCap(raw: List<C>): List<C> = if (raw.size > LOAD_CAP) raw.subList(raw.size - LOAD_CAP, raw.size) else raw

    /**
     * `DataDatabases::GetCGMapproximate` (0x152e60) on the [reduced] (decimated) DB: the |value| of the newest
     * CGM record with time ≤ [targetMin]; the first record's value when they are all after [targetMin]; and
     * [fallback] only when the DB is empty. This is the starting glucose `MPC::DetermineSetPoint` seeds from
     * (at `now − predictLead`), NOT a raw-CGM lookup.
     */
    fun getCgmApproximate(reduced: List<C>, targetMin: Long, fallback: Float): Float {
        if (reduced.isEmpty()) return fallback
        if (reduced[0].t >= targetMin) return kotlin.math.abs(reduced[0].v)
        var out = fallback
        for (c in reduced) { if (c.t > targetMin) break; out = kotlin.math.abs(c.v) }
        return out
    }

    /**
     * reduce_bg: resample [rawIn] (already load-capped or not — [loadCap] is applied here) onto the coarse grid.
     * [nowMin] = the decision time (param_4). Returns the decimated (time, value) records the walk will read.
     */
    fun reduceBg(rawIn: List<C>, nowMin: Long): List<C> {
        val raw = loadCap(rawIn)
        val n = raw.size
        if (n < 2) return raw.toList()
        // grid cursor = now - 8h, rounded DOWN to a 10-min boundary
        var grid = nowMin - LOOKBACK
        grid -= ((grid % 10) + 10) % 10
        var step = SPAN15
        val out = ArrayList<C>()
        fun t(i1: Int) = raw[i1 - 1].t                 // cgmTime[uVar12-1], 1-based index
        var i = 1                                       // uVar12
        // initial skip-ahead (LAB_0014cce8 reached first)
        while (i <= n && grid > t(i)) i++
        while (true) {                                  // LAB_0014cd1c
            var selected = false
            if (n >= i) {                               // not(uVar10 < uVar12)
                val ct = t(i)
                if (grid + step > ct && grid <= ct) { out.add(raw[i - 1]); selected = true }
            }
            grid += step
            if (i <= n) step = if (raw[n - 1].t > t(i) + SPAN45) SPAN15 else SPAN10
            if (n < i) selected = true                  // exhausted → force-skip carry
            if (!selected && i <= n) {                  // carry selection
                val ct = t(i)
                if (grid + step > ct && grid <= ct && ct <= nowMin) { out.add(raw[i - 1]); i++ }
            }
            if (i <= n) { while (i <= n && grid > t(i)) i++; continue }  // skip-ahead + loop
            break
        }
        // tail: append the last record <= now if it isn't already the final output
        var last = n
        if (raw[n - 1].t > nowMin) { var k = 1; while (k < n && raw[k].t <= nowMin) k++; last = k }
        val li = last - 1
        if (raw[li].t <= nowMin && (out.isEmpty() || raw[li].t > out.last().t)) out.add(raw[li])
        return out
    }
}

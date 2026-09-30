package app.aaps.plugins.aps.camapsfx

/**
 * `MPC::GetDataForLearning`'s step-time walk (0x142c40, fill loop) — the CGM/event-aligned schedule of the
 * estimator's history horizon. Reproduces the exact step times + per-step dt the binary uses, driven by
 * `DataDatabases::GetFirst` (0x1522a0) snapping.
 *
 * The walk starts a fixed 8h before "now" and advances by dtInt (25 min); at each cursor `GetFirst` looks for
 * an event in the window and, if found, snaps the step earlier so estimation aligns to real CGM/meal/bolus
 * times. Snap rule (empirically confirmed): with a meal/bolus in the window the snap is `min(CGM_time,
 * cursor − window + 3)` (just `cursor − window + 3` when no CGM), clamped ≤ cursor; with only a CGM it is the
 * CGM time. Stored record shifts (from DataDatabases::Init): meals +10 min, CGM none, bolus none.
 */
object GdflWalk {
    data class Step(val timeAbsMin: Long, val dt: Int)

    /** GetCGMFirst: the OLDEST CGM in the window `(cursor − window, cursor]`; found when any exists. */
    private fun cgmAt(cursor: Long, window: Int, cgm: List<Long>): Pair<Boolean, Long> {
        val t = cgm.filter { it > cursor - window && it <= cursor }.minOrNull() ?: return false to 0L
        return true to t
    }
    private fun inWindowIncl(cursor: Long, window: Int, times: List<Long>) = times.any { it > cursor - window && it <= cursor }
    /** A bolus (time, durationMin) is in the window if its active interval [t, t+dur] overlaps [cursor−window, cursor).
     *  The lower edge is INCLUSIVE (`>=`), matching `DataDatabases::GetInsulinBolus`'s window edge (0x15266c): a
     *  boundary point-bolus at exactly `cursor−window` counts, so tick_0021's walk is 67 steps (meals aligned).
     *  (A strict `>` earlier looked "better in aggregate" only because the multi-meal estimation was diverging; with
     *  the fBio meal-finalisation fix + prior-meal DB in place, `>=` is strictly better — replay 43→44/49 exact, no
     *  regression, and the sanity walk stays 56/56 bit-exact.) */
    private fun bolusInWindow(cursor: Long, window: Int, bol: List<Pair<Long, Int>>) =
        bol.any { (t, dur) -> t < cursor && t + dur >= cursor - window }

    /** `GetFirst(cursor, window)` → (found, snap). */
    private fun getFirst(cursor: Long, window: Int, cgm: List<Long>, meals: List<Long>, bol: List<Pair<Long, Int>>): Pair<Boolean, Long> {
        val (cf, ct) = cgmAt(cursor, window, cgm)
        val m = inWindowIncl(cursor, window, meals)
        val b = bolusInWindow(cursor, window, bol)
        if (!m && !b) return (cf) to ct            // CGM-only: snap = CGM time (or not found)
        var snap = if (cf) minOf(ct, cursor - window + 3) else cursor - window + 3
        if (snap > cursor) snap = cursor
        return true to snap
    }

    /**
     * Generate the horizon steps (time, dt). [now]/[cgm]/[meals]/[bol] in absolute minutes (meals already +10).
     * Mirrors the fill loop: step 0 is never snapped; the cursor advances by dtInt, snapping to events; near
     * `now` the window shrinks so the last steps land exactly on the recent CGM cadence.
     */
    var debug = false
    fun walk(now: Long, cgm: List<Long>, meals: List<Long>, bol: List<Pair<Long, Int>>, lookbackMin: Int = 480, dtInt: Int = 25): List<Step> {
        val out = ArrayList<Step>()
        var cursor = now - lookbackMin
        var window = dtInt
        var k = 0
        fun hm(m: Long) = "%02d:%02d".format((m % 1440) / 60, m % 60)
        while (true) {
            val (found, snap) = getFirst(cursor, window, cgm, meals, bol)
            if (debug && k in 22..40) {
                val (cf, ct) = cgmAt(cursor, window, cgm)
                println("    k=$k cursor=${hm(cursor)} win=$window found=$found snap=${if(found)hm(snap) else "-"} [cgm=$cf@${hm(ct)} meal=${inWindowIncl(cursor,window,meals)} bol=${bolusInWindow(cursor,window,bol)}]")
            }
            val stepTime: Long; val dt: Int; var next: Long
            if (k < 1 || !found) { stepTime = cursor; dt = window; next = cursor + dtInt }
            else { stepTime = snap; dt = (window - (cursor - snap)).toInt(); next = snap + dtInt }
            // end-clamp: once the next cursor passes now, pin at now and shrink the window to (now - stepTime)
            if (next > now) { window = (now - stepTime).toInt(); next = now } else window = dtInt
            out.add(Step(stepTime, dt))
            if (stepTime >= now || window <= 0) break
            cursor = next; k++
            if (out.size > 200) break   // safety
        }
        return out
    }
}

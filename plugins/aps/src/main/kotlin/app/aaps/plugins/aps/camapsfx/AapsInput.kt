package app.aaps.plugins.aps.camapsfx

/**
 * The live data-access adapter: turns AAPS history (or any device DB) into the [InputParser] the validated
 * controller consumes, exactly as `stage3/realdata_to_camaps.py` does for the offline replay (that pipeline's
 * output matches the genuine binary 49/49 bit-exact, so this is its faithful Kotlin port).
 *
 * It is DB-AGNOSTIC — it takes plain record lists, so the SAME code serves the offline validation (fed from a
 * pulled `live.db`) and a live AAPS plugin (fed from the AAPS repositories). It never touches SQL itself.
 *
 * Mapping (per `report/camaps-model-spec.md`): weight + the 48-slot SCHEDULED basal profile → the operating
 * point; `Insulin_infusion` = the ACTUAL delivered basal (profile × active TBR) as change-points; `Insulin_bolus`
 * = real boluses in the window; `Enteral_bolus` = real carbs; `Glucose_concentration` = real CGM (mg/dL → mmol/L,
 * one per minute). The controller uses only time DELTAS + local-minute-of-day, so times map to
 * `(epochMs + utcOffsetMs)/60000` (an absolute-minute frame whose `%1440` is the local minute-of-day).
 */
object AapsInput {
    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L
    private const val MGDL_PER_MMOL = 18.0f

    data class Cgm(val epochMs: Long, val mgdl: Float)                       // glucoseValues (value in mg/dL)
    data class Bolus(val epochMs: Long, val units: Float)                    // boluses.amount
    data class Carb(val epochMs: Long, val grams: Float)                     // carbs.amount
    data class Tbr(val epochMs: Long, val isAbsolute: Boolean, val rate: Float, val durationMs: Long)  // temporaryBasals
    data class Block(val durationMs: Long, val amountUh: Float)              // effectiveProfileSwitches.basalBlocks

    /** Scheduled basal rate (U/h) at a LOCAL ms-of-day, from the profile blocks (blocks_at_local). */
    fun blockAtLocal(blocks: List<Block>, localMs: Long): Float {
        val tod = ((localMs % DAY_MS) + DAY_MS) % DAY_MS
        var acc = 0L
        for (b in blocks) { acc += b.durationMs; if (tod < acc) return b.amountUh }
        return blocks.last().amountUh
    }

    /** Build the `sec-from-local-midnight → U/h` accessor from profile blocks (the offline / live.db path). */
    fun basalFn(blocks: List<Block>): (Int) -> Float = { sec -> blockAtLocal(blocks, sec.toLong() * 1000L) }

    /** Actual delivered basal (U/h) at epoch [ts]: scheduled profile × the active TBR (absolute overrides, else %). */
    fun deliveredUhr(ts: Long, tz: Long, basalAtLocalSec: (Int) -> Float, tbrs: List<Tbr>): Float {
        val base = basalAtLocalSec((((ts + tz) % DAY_MS + DAY_MS) % DAY_MS / 1000L).toInt())
        var tb: Tbr? = null
        for (t in tbrs) if (t.epochMs <= ts && ts < t.epochMs + t.durationMs) tb = t
        return when { tb == null -> base; tb.isAbsolute -> tb.rate; else -> base * tb.rate / 100f }
    }

    private fun round4(v: Float): Float = Math.round(v * 10000.0).toFloat() / 10000f

    /**
     * Assemble one decision tick's [InputParser] for `now = [nowEpochMs]` over a trailing [windowH]-hour window.
     * [tz] = local UTC offset (ms). [blocks]/[cgm]/[boluses]/[carbs]/[tbrs] are the device history (the caller
     * has already applied `isValid=1 [AND referenceId IS NULL]`); this method does the CamAPS content selection.
     */
    fun buildTick(nowEpochMs: Long, windowH: Float, tz: Long, weightKg: Float, targetMmol: Float?,
                  basalAtLocalSec: (Int) -> Float, cgm: List<Cgm>, boluses: List<Bolus>, carbs: List<Carb>, tbrs: List<Tbr>): InputParser {
        val p = InputParser("")
        p.weightKg = weightKg
        for (i in 0 until 48) p.basalProfileUh[i] = basalAtLocalSec(i * 30 * 60)  // rate at 30-min slot i (local)
        p.basalUday = p.basalProfileUh.sum() * 0.5f                          // profile TDD (sum × 30min/60)
        if (targetMmol != null) for (i in 0 until 48) p.targetProfile[i] = targetMmol
        fun toMin(ms: Long): Long = (ms + tz) / MIN_MS
        p.nowMinute = toMin(nowEpochMs)
        val w0 = nowEpochMs - (windowH * HOUR_MS).toLong()

        // CGM: keyed by MINUTE over the whole history (xDrip duplicates collapse, last wins), mg/dL → mmol/L,
        // then the minutes in [w0//min, now//min] are emitted (aligned to the minute start) — exactly the binary-
        // verified selection (`bg[ts//MIN_MS]` + `wmins = range(w0//MIN_MS, tick//MIN_MS + 1)`).
        val bg = HashMap<Long, Float>()
        for (g in cgm) bg[g.epochMs / MIN_MS] = g.mgdl / MGDL_PER_MMOL
        val wMin0 = w0 / MIN_MS; val nowMinM = nowEpochMs / MIN_MS
        for (mnt in bg.keys.sorted()) if (mnt in wMin0..nowMinM) p.cgm.add(InputParser.Rec(toMin(mnt * MIN_MS), bg[mnt]!!))

        for (b in boluses) if (b.epochMs in w0..nowEpochMs && b.units > 0f) p.boluses.add(InputParser.Rec(toMin(b.epochMs), b.units, 0f))
        p.boluses.sortBy { it.minute }
        for (c in carbs) if (c.epochMs in w0..nowEpochMs && c.grams > 0f) p.meals.add(InputParser.Rec(toMin(c.epochMs), c.grams))
        p.meals.sortBy { it.minute }

        // Insulin_infusion = delivered-basal change-points across the window (per-minute scan)
        var last = Float.NaN
        var m = w0 / MIN_MS; val nowM = nowEpochMs / MIN_MS
        while (m <= nowM) {
            val u = round4(deliveredUhr(m * MIN_MS, tz, basalAtLocalSec, tbrs))
            if (u != last) { p.infusion.add(InputParser.Rec(toMin(m * MIN_MS), u)); last = u }
            m++
        }
        if (p.infusion.isEmpty()) p.infusion.add(InputParser.Rec(toMin(w0), round4(deliveredUhr(w0, tz, basalAtLocalSec, tbrs))))
        return p
    }
}

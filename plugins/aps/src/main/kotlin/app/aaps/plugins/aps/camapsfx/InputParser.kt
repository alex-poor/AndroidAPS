package app.aaps.plugins.aps.camapsfx


/**
 * Parser for the CamAPS T1D data file (`DataInOut::ReadT1DData`'s human-readable input — the same format the
 * emulator/`oneRun` consumes: `stage3/sanity_input.txt`). This is the reference-input side of the [DataAccess]
 * seam: it turns the raw patient history + config into typed records a [DataAccess] serves. The eventual AAPS
 * integration maps the AAPS database to the same shape; validating against this file bridges the two.
 *
 * Sections: Weight, the 48-slot basal profile (U/h, 30-min steps), Enteral_bolus (meals: time,CHO),
 * Insulin_bolus (time,U,dur,type), Insulin_infusion (time,rate,type), Glucose_concentration (CGM: time,mmol/L),
 * Exercise, Reference_glucose, and Start (the decision time "now"). Times are `dd/mm/yyyy hh:mm`.
 */
class InputParser(text: String) {
    data class Rec(val minute: Long, val a: Float, val b: Float = 0f)   // minute = absolute minutes; a/b = values

    var weightKg = 0f
    var basalUday = 0f                       // the "Basal: X U/day" field (conv = 45/basalUday)
    val basalProfileUh = FloatArray(48)     // raw U/h per 30-min slot (before the conv scaling)
    val targetProfile = FloatArray(48) { -1f }  // "Target glucose level in 30min steps" (mmol/L); −1 ⇒ default 5.8
    val meals = ArrayList<Rec>()            // (minute, CHO g)
    val boluses = ArrayList<Rec>()          // (minute, U, durationMin)
    val infusion = ArrayList<Rec>()         // (minute, U/h)
    val cgm = ArrayList<Rec>()              // (minute, mmol/L)
    val exercise = ArrayList<Rec>()         // (minute, durationMin)
    var nowMinute = 0L

    private fun parseTime(s: String): Long {
        // dd/mm/yyyy hh:mm
        val m = Regex("""(\d{2})/(\d{2})/(\d{4})\s+(\d{2}):(\d{2})""").find(s) ?: return 0
        val (dd, mm, yyyy, hh, mi) = m.destructured
        // days since a fixed epoch (proleptic-ish; only differences/among-record ordering + minute-of-day matter)
        val days = daysFromCivil(yyyy.toInt(), mm.toInt(), dd.toInt())
        return days * 1440L + hh.toInt() * 60L + mi.toInt()
    }
    // Howard Hinnant's days-from-civil (1970-01-01 = 0)
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = (y - era * 400).toLong()
        val doy = ((153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1).toLong()
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    val localMinuteOfDay: Int get() = ((nowMinute % 1440L + 1440L) % 1440L).toInt()

    init {
        val lines = text.lines()
        var section = ""
        var profileRow = 0
        var targetRow = 0
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("Weight:") -> weightKg = Regex("""[\d.]+""").find(line)!!.value.toFloat()
                line.startsWith("Basal:") -> basalUday = Regex("""[\d.]+""").find(line)!!.value.toFloat()
                line.startsWith("Basal rate in 30min") -> { section = "profile"; profileRow = 0 }
                line.startsWith("Target glucose level in 30min") -> { section = "targetprofile"; targetRow = 0 }
                line.startsWith("Enteral_bolus") -> section = "meal"
                line.startsWith("Insulin_bolus") -> section = "bolus"
                line.startsWith("Insulin_infusion") -> section = "infusion"
                line.startsWith("Glucose_concentration") -> section = "cgm"
                line.startsWith("Exercise") -> section = "exercise"
                line.startsWith("Reference_glucose") -> section = "refglu"
                line.startsWith("Start") -> section = "start"
                line.startsWith("Enteral_infusion") || line.startsWith("Parenteral") -> section = "skip"
                line.startsWith("Time") || line.startsWith("(dd/mm") || line.isEmpty() || line.startsWith("ID:") ||
                    line.startsWith("Basal:") || line == "END" -> { /* header/blank */ }
                section == "profile" && line[0].isDigit() -> {
                    val vals = line.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toFloat() }
                    for ((i, v) in vals.withIndex()) if (profileRow * 12 + i < 48) basalProfileUh[profileRow * 12 + i] = v
                    profileRow++
                }
                section == "targetprofile" && line[0].isDigit() -> {
                    val vals = line.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toFloat() }
                    for ((i, v) in vals.withIndex()) if (targetRow * 12 + i < 48) targetProfile[targetRow * 12 + i] = v
                    targetRow++
                }
                line.contains("/") && line.contains(":") -> {   // a timestamped record row
                    val t = parseTime(line)
                    val nums = Regex("""-?\d+\.?\d*""").findAll(line.substringAfter(line.take(16))).map { it.value }.toList()
                    // after the 16-char timestamp, the remaining numbers are the values
                    val after = line.substring(minOf(16, line.length)).trim()
                    val v = Regex("""-?\d+\.?\d+""").findAll(after).map { it.value.toFloat() }.toList()
                    when (section) {
                        "meal" -> if (v.isNotEmpty()) meals.add(Rec(t, v[0]))
                        "bolus" -> if (v.isNotEmpty()) boluses.add(Rec(t, v[0], if (v.size > 1) v[1] else 0f))
                        "infusion" -> if (v.isNotEmpty()) infusion.add(Rec(t, v[0]))
                        "cgm" -> if (v.isNotEmpty()) cgm.add(Rec(t, v[0]))
                        "exercise" -> exercise.add(Rec(t, if (v.isNotEmpty()) v[0] else 0f))
                        "start" -> nowMinute = t
                    }
                }
            }
        }
    }

    // The processed infusion DB (`ReadT1DData` rebuild): a 24h baseline record + the most-recent 79 delivered
    // TBRs (scaled ×conv, each with duration = gap-to-next), total capped at 80 (the Vector<...,80> capacity).
    private var infDB: List<Triple<Long, Float, Int>>? = null   // (timeAbsMin, scaledRate, durMin)
    private var infDBConv = Float.NaN
    private fun infusionDB(conv: Float): List<Triple<Long, Float, Int>> {
        infDB?.let { if (infDBConv == conv) return it }
        val cap = 79                                            // 80 vector − 1 baseline
        val kept = if (infusion.size > cap) infusion.subList(infusion.size - cap, infusion.size) else infusion
        val db = ArrayList<Triple<Long, Float, Int>>()
        if (kept.isNotEmpty()) {
            val t0 = kept.first().minute
            db.add(Triple(t0 - 1440L, Controller.REF_TDD / 48f, 1440))            // baseline (scaled, = refTDD/48)
            for (i in kept.indices) {
                val t = kept[i].minute
                val nxt = if (i + 1 < kept.size) kept[i + 1].minute else nowMinute  // last record ends at now
                db.add(Triple(t, kept[i].a * conv, (nxt - t).toInt()))
            }
        }
        infDB = db; infDBConv = conv
        return db
    }

    /**
     * `DataDatabases::GetInsulinInfusion` (0x152b84): the time-weighted average infusion rate over the window
     * `[atAbsMin − windowMin, atAbsMin]` on the REBUILT DB ([infusionDB]) — each record active for its own
     * `[t, t+dur]` interval, `Σ(rate·overlap)/Σ(overlap)`. Rates are already ×[conv]. Returns null (binary's
     * −999.9) when the window overlaps no record. Validated = 0.47712 U/h at now (window 15) on the sanity run.
     */
    fun insulinInfusion(atAbsMin: Long, windowMin: Int, conv: Float): Float? {
        val start = atAbsMin - windowMin; val end = atAbsMin
        var num = 0.0; var tot = 0
        for ((t, rate, dur) in infusionDB(conv)) {
            if (t >= end) break                                 // sorted; record starts at/after window end
            val lo = maxOf(t, start); val hi = minOf(t + dur, end)
            val ov = (hi - lo).toInt()
            if (ov > 0) { num += rate.toDouble() * ov / 60.0; tot += ov }
        }
        return if (tot == 0) null else (num / (tot / 60.0)).toFloat()
    }

    /**
     * `DataDatabases::GetInsulinBolus` (0x15266c): the bolus insulin applied in the window `[t − windowMin, t]`,
     * scaled by [conv]. A point bolus (duration < 0.1) in the window contributes its full value; an extended
     * bolus contributes `value × overlapMinutes / duration` (start offset +1 min). Returns 0 when none (the
     * binary's −999.9 → 0 in the horizon). Boluses with `btime < t` and active-interval end ≥ `t − windowMin`.
     */
    fun insulinBolus(atAbsMin: Long, windowMin: Int, conv: Float): Float {
        val ws = atAbsMin - windowMin
        var sum = 0f; var found = false
        for (r in boluses) {
            val bt = r.minute; val bval = r.a; val bdur = r.b.toInt()
            val end = bt + bdur
            if (bt < atAbsMin && end >= ws) {
                sum += if (bdur >= 1) {                       // extended (dur ≥ 0.1 min ⇒ ≥1 here)
                    val start = bt + 1
                    var ov = bdur.toFloat()
                    if (start < ws) ov -= (ws - start)
                    if (end > atAbsMin) ov -= (end - atAbsMin)
                    if (ov < 0f) ov = 0f
                    (bval * conv) * ov / bdur
                } else bval * conv                            // point bolus
                found = true
            }
        }
        return if (found) sum else 0f
    }

    companion object {
    }
}

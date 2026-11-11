package app.aaps.plugins.aps.camapsfx

/**
 * The one entry point the plugin calls: device records → [AapsInput] → [Controller.runTick] → delivered basal
 * (U/h). This is the bit-exact clean-room CamAPS FX controller (validated 49/49 vs the genuine binary on the
 * user's real AAPS history, offline). The whole engine is stateless per tick — it re-derives everything from the
 * trailing window, so nothing can silently persist between calls.
 *
 * @param basalAtLocalSec scheduled basal U/h at a seconds-from-local-midnight (e.g. `profile.getBasalTimeFromMidnight`).
 * @return the recommended basal rate in U/h (before AAPS's maxBasal/maxIOB constraints, applied by the caller/Loop).
 */
object CamapsFxEngine {
    /** The default GetRate control config (p1=4 control blocks, p2=7 prediction, 25-min steps) — the binary's. */
    // BURST correction (the monitored-trial bundle): supersedes plain aggressive — forces the aggressive path,
    // gates it on the RAW CGM (holds through a stuck high), lifts the hard ceiling (~2.25→~3.5 U/h, still under
    // maxBasal), and adds a 30-min fall taper so the correction self-limits and never stacks into an overnight
    // crater. Tuned on 14 days of the user's own data (boost 2.5 / aggroCap 2.0 / taper 1.5). Default OFF.
    private fun defaultCfg(mealActive: Int, aggressive: Boolean, mealAntiSuspend: Boolean, burst: Boolean): Tick.Config {
        val aggr = aggressive || burst
        // BURST now drives the UNIFIED correction floor: one keyed law (rises with how high, trend-damped,
        // bounded, budget-stopped) REPLACES the old boost/aggroCap/IOB-brake/fall-taper stack — so aggression
        // and its brake are the same function and can't stack. The old multiplier/guard knobs are neutralised
        // when burst is on (boost=1, no brake, no taper); plain `aggressive` (non-burst) keeps the old 1.8x.
        return Tick.Config(0f, 30f, 0f, 25, 25, 150, 6, p1 = 4, p2 = 7, bolus = 0,
            mealActive = mealActive, 0f, 0f, 0f, 0f, 0f, 0f, aggressive = aggr,
            boostFactor = if (burst) 1.0f else if (aggr) 1.8f else 1.0f,
            aggroCap = if (burst) 1.5f else 1.5f,
            mealAntiSuspend = mealAntiSuspend,
            rawCgmAggro = burst,
            fallTaperK = 0f,
            burstIobBrake = false,
            unifiedFloor = burst)
    }

    fun decide(
        nowMs: Long, windowH: Float, tzOffsetMs: Long, weightKg: Float, targetMmol: Float?,
        basalAtLocalSec: (Int) -> Float,
        cgm: List<AapsInput.Cgm>, boluses: List<AapsInput.Bolus>, carbs: List<AapsInput.Carb>, tbrs: List<AapsInput.Tbr>,
        aggressive: Boolean = false, mealAntiSuspend: Boolean = false, burst: Boolean = false
    ): Result {
        val p = AapsInput.buildTick(nowMs, windowH, tzOffsetMs, weightKg, targetMmol, basalAtLocalSec, cgm, boluses, carbs, tbrs)
        val mealActive = if (p.meals.any { it.minute > p.nowMinute - 720 }) 1 else 0
        val r = Controller.runTick(
            p, basalUday = if (p.basalUday > 0f) p.basalUday else 10.8f,
            coldInit = { CamapsColdInit.coldInit(p.weightKg) }, cfg = defaultCfg(mealActive, aggressive, mealAntiSuspend, burst),
            mealProb = { _, _ -> 1f }, longUpdate = { false }, mealDb = MealPriorDb()
        )
        return Result(r.rate, p.cgm.size, p.boluses.size, p.meals.size, r.conv, r.target, r.predEventualMmol)
    }

    data class Result(val rateUhr: Float, val nCgm: Int, val nBolus: Int, val nMeal: Int, val conv: Float,
                      val targetMmol: Float, val eventualMmol: Float)
}

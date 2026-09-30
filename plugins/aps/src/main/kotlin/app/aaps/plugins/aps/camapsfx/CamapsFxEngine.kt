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
    private fun defaultCfg(mealActive: Int) = Tick.Config(0f, 30f, 0f, 25, 25, 150, 6, p1 = 4, p2 = 7, bolus = 0,
        mealActive = mealActive, 0f, 0f, 0f, 0f, 0f, 0f)

    fun decide(
        nowMs: Long, windowH: Float, tzOffsetMs: Long, weightKg: Float, targetMmol: Float?,
        basalAtLocalSec: (Int) -> Float,
        cgm: List<AapsInput.Cgm>, boluses: List<AapsInput.Bolus>, carbs: List<AapsInput.Carb>, tbrs: List<AapsInput.Tbr>
    ): Result {
        val p = AapsInput.buildTick(nowMs, windowH, tzOffsetMs, weightKg, targetMmol, basalAtLocalSec, cgm, boluses, carbs, tbrs)
        val mealActive = if (p.meals.any { it.minute > p.nowMinute - 720 }) 1 else 0
        val r = Controller.runTick(
            p, basalUday = if (p.basalUday > 0f) p.basalUday else 10.8f,
            coldInit = { CamapsColdInit.coldInit(p.weightKg) }, cfg = defaultCfg(mealActive),
            mealProb = { _, _ -> 1f }, longUpdate = { false }, mealDb = MealPriorDb()
        )
        return Result(r.rate, p.cgm.size, p.boluses.size, p.meals.size, r.conv, r.target, r.predEventualMmol)
    }

    data class Result(val rateUhr: Float, val nCgm: Int, val nBolus: Int, val nMeal: Int, val conv: Float,
                      val targetMmol: Float, val eventualMmol: Float)
}

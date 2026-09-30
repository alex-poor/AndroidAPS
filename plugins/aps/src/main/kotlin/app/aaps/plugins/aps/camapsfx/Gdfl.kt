package app.aaps.plugins.aps.camapsfx

/**
 * Assembles the estimator's learning horizon (`MPC::GetDataForLearning`) entirely from the raw patient history
 * — the step-time [GdflWalk] + the per-step DataDatabases reads — into the arrays [ImmBank.runLearn] consumes.
 * This is the GDFL half of the data-access layer, the counterpart to [HorizonBuilder] (the GDFO half).
 *
 * Per step k at time t with window dt[k]:
 *   - dt[k]     from the walk;
 *   - ins[k]    `GetInsulinInfusion(t, dt[k])` (0 for step 0);
 *   - cgm[k]    the CGM value at t (oldest in the window) or −999.9 when none — the filter's measurement slot;
 *   - meal[k]   `GetMeal` = Σ(int)CHO in (t−dt, t];
 *   - bir[k]    `GetBIRStepsSmoothed(t)` (profile×conv, floor c, cap x14).
 */
object Gdfl {
    class Horizon(val n: Int, val dt: FloatArray, val bir: FloatArray, val ins: FloatArray,
                  val cgm: FloatArray, val cgmObs: FloatArray, val meal: FloatArray, val insApp: FloatArray,
                  val stepLocalMin: IntArray)

    fun assemble(p: InputParser, profile48: FloatArray, conv: Float, floorBase: Float, cap: Float): Horizon {
        // CGM is load-capped (390) + decimated (reduce_bg) at DataDatabases::Init, BEFORE the walk reads it.
        val reduced = Decimate.reduceBg(p.cgm.map { Decimate.C(it.minute, it.a) }, p.nowMinute)
        val cgmT = reduced.map { it.t }
        val meals10 = p.meals.map { it.minute + 10 }
        val bol = p.boluses.map { it.minute to it.b.toInt() }
        val steps = GdflWalk.walk(p.nowMinute, cgmT, meals10, bol)
        val n = steps.size
        val dt = FloatArray(n); val bir = FloatArray(n); val ins = FloatArray(n)
        val cgm = FloatArray(n) { -999.9f }; val cgmObs = FloatArray(n) { -999.9f }; val meal = FloatArray(n); val insApp = FloatArray(n)
        val stepLocalMin = IntArray(n)
        for (k in 0 until n) {
            val t = steps[k].timeAbsMin; val d = steps[k].dt
            dt[k] = d.toFloat()
            stepLocalMin[k] = ((t % 1440).toInt() + 1440) % 1440    // local minute-of-day for GetMealTypeIdx
            ins[k] = if (k == 0) 0f else (p.insulinInfusion(t, d, conv) ?: 0f)
            insApp[k] = p.insulinBolus(t, d, conv)
            // CGM value: oldest decimated CGM in (t−d, t]  (matches GetCGMFirst's window on the reduced DB)
            val cg = reduced.filter { it.t > t - d && it.t <= t }.minByOrNull { it.t }
            if (cg != null) cgm[k] = cg.v
            // meal: Σ(int)CHO in (t−d, t]  (meals shifted +10)
            var m = 0f; for (r in p.meals) if (r.minute + 10 > t - d && r.minute + 10 <= t) m += r.a.toInt().toFloat()
            meal[k] = m
            bir[k] = HorizonBuilder.birStep(profile48, ((t % 1440).toInt() + 1440) % 1440, floorBase, cap)
        }
        return Horizon(n, dt, bir, ins, cgm, cgmObs, meal, insApp, stepLocalMin)
    }
}

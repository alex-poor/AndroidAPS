package app.aaps.plugins.aps.camapsfx

/**
 * `MPC::GetDataForOptimisation` (0x148598) — the data-access layer that assembles one optimiser/rollout
 * horizon from the pump basal profile + the CGM/insulin/meal history. This is the plumbing that turns live
 * device state into the [Optimiser] / [ImmBank] input arrays; the numeric transforms it calls
 * ([Optimiser.getBIRStepsSmoothed], [Optimiser.getBIRpump]) are already validated bit-exact, so this object
 * just reproduces the array bookkeeping around them.
 *
 * The horizon length is `dtPerControlStep·nSteps + 1` (16-slot `Vector<float,180>` buffers, only the first
 * `len` used). Per GDFO:
 *   - **dt**   `[0]=controlStepInt`, `[1..len-2]=dtInt`, `[len-1]=totalMinutes − dtInt·(len-2)` (line 168-204).
 *   - **ins**  `[0]=DataDatabases::GetInsulinInfusion` (current basal delivery); `[1..]` start 0 and are
 *              overlaid later by [Optimiser.adviceToInsulinInfusion] with the control iterate (line 212).
 *   - **insApp** `= DataDatabases::GetInsulinBolus` per step (bolus insulin applied; 0 when none) (line 220-227).
 *   - **meal arrays** `= DataDatabases::GetMeal` per step (0 unless a meal falls in the step) (line 230-236).
 *   - **bir**  `[k] = GetBIRStepsSmoothed(stepTime_k)` — the projected basal-insulin-requirement (line 238-240).
 *
 * Step times: GDFO seeds `t = now − dtInt` then, before each `GetBIRStepsSmoothed`, does `t += dt[k]`
 * (line 205, 218-219), so `stepTime_k = now − dtInt + Σ_{j≤k} dt[j]` — i.e. `now, now+ctrl, now+2·ctrl, …`
 * for the uniform control segment. `GetBIRStepsSmoothed` in turn samples [Optimiser.getBIRpump] at
 * `stepTime_k − 30·j` (j=0..7). The whole chain is a pure function of the 48-slot profile + local time.
 *
 * The genuine 16-slot horizon concatenates a control segment (this call, `len` slots) with a longer
 * prediction/anticipation segment (a second GDFO / `GetDataForLearning` over a later span); the CGM slots
 * carry the −999.9 "no measurement" sentinel (a forward rollout assimilates nothing). This object builds one
 * segment; the caller concatenates.
 */
object HorizonBuilder {

    /** GDFO horizon length = `dtPerControlStep·nSteps + 1`. */
    fun horizonLength(nSteps: Int, dtPerControlStep: Int = 1): Int = dtPerControlStep * nSteps + 1

    /**
     * The per-step control-interval array (minutes), truncated to int downstream. [len] ≥ 1.
     * `dt[0]=controlStep`; `dt[1..len-2]=dtInt`; `dt[len-1]=totalMinutes − dtInt·(len-2)` (the remainder that
     * makes the horizon exactly span [totalMinutes]). For `len<3` only `dt[0]` (and, for len==2, the remainder)
     * apply, matching GDFO's `if (uVar13 < 3)` early-out.
     */
    fun buildDt(controlStep: Int, dtInt: Int, totalMinutes: Int, len: Int): FloatArray {
        val dt = FloatArray(len)
        if (len >= 1) dt[0] = controlStep.toFloat()
        if (len >= 3) for (i in 1..len - 2) dt[i] = dtInt.toFloat()
        if (len >= 2) dt[len - 1] = (totalMinutes - dtInt * (len - 2)).toFloat()
        return dt
    }

    /**
     * `MPC::GetBIRpump` (0x148e44): the pump basal for a local time = `profile48[hour*2 + (min>=30?1:0)]`,
     * i.e. the half-hour slot `floor(localMinuteOfDay/30) mod 48` (the DST offset at this+0x198/0x1a0 is zero
     * in-sanity). [localMinuteOfDay] may be negative or ≥1440 (samples reach back before midnight); wrap it.
     */
    fun birPump(profile48: FloatArray, localMinuteOfDay: Int): Float {
        val m = ((localMinuteOfDay % 1440) + 1440) % 1440
        return profile48[m / 30]
    }

    /**
     * One horizon step's projected BIR: [Optimiser.getBIRStepsSmoothed] over the 8 pump samples at
     * `stepMinuteOfDay − 30·j` (j=0..7). [floorBase] = MPC this+0xc, [cap] = this+0x14.
     */
    fun birStep(profile48: FloatArray, stepMinuteOfDay: Int, floorBase: Float, cap: Float): Float {
        val samples = FloatArray(8) { j -> birPump(profile48, stepMinuteOfDay - 30 * j) }
        return Optimiser.getBIRStepsSmoothed(samples, floorBase, cap, 1)
    }

    /**
     * The full `bir` horizon array: `bir[k] = birStep(now − dtInt + Σ_{j≤k} dt[j])`. [nowMinuteOfDay] is the
     * local time GDFO is called with (`param_2`/`param_3`); [dtInt] is the seed offset (GDFO line 205).
     */
    fun buildBir(profile48: FloatArray, nowMinuteOfDay: Int, dt: FloatArray, dtInt: Int,
                 floorBase: Float, cap: Float): FloatArray {
        val bir = FloatArray(dt.size)
        var t = nowMinuteOfDay - dtInt
        for (k in dt.indices) { t += dt[k].toInt(); bir[k] = birStep(profile48, t, floorBase, cap) }
        return bir
    }

    /**
     * Assemble a complete control-horizon segment. [insulinInfusion] = `GetInsulinInfusion` (ins[0]);
     * [bolusPerStep]/[mealCarbsPerStep]/[mealBioPerStep] come from the DB reads (all 0 in the happy path).
     * cgm/cgmObs carry the −999.9 no-measurement sentinel. Returns the arrays PFO/Optimise consume.
     */
    fun getDataForOptimisation(
        profile48: FloatArray, nowMinuteOfDay: Int, controlStep: Int, dtInt: Int, totalMinutes: Int,
        nSteps: Int, floorBase: Float, cap: Float, insulinInfusion: Float,
        bolusPerStep: FloatArray? = null, mealCarbsPerStep: FloatArray? = null, mealBioPerStep: FloatArray? = null,
    ): Horizon {
        val len = horizonLength(nSteps)
        val dt = buildDt(controlStep, dtInt, totalMinutes, len)
        val bir = buildBir(profile48, nowMinuteOfDay, dt, dtInt, floorBase, cap)
        val ins = FloatArray(len); ins[0] = insulinInfusion
        val insApp = FloatArray(len) { bolusPerStep?.getOrElse(it) { 0f } ?: 0f }
        val mealin = FloatArray(len) { mealCarbsPerStep?.getOrElse(it) { 0f } ?: 0f }
        val mealBio = FloatArray(len) { mealBioPerStep?.getOrElse(it) { 0f } ?: 0f }
        val cgm = FloatArray(len) { NO_MEASUREMENT }
        val cgmObs = FloatArray(len)
        return Horizon(len, dt, bir, ins, insApp, cgm, cgmObs, mealin, mealBio)
    }

    /** The CGM "no measurement" sentinel used across the horizon (−999.9). */
    const val NO_MEASUREMENT: Float = -999.9f

    /** One assembled horizon segment. */
    data class Horizon(
        val len: Int, val dt: FloatArray, val bir: FloatArray, val ins: FloatArray, val insApp: FloatArray,
        val cgm: FloatArray, val cgmObs: FloatArray, val mealin: FloatArray, val mealBio: FloatArray,
    )
}

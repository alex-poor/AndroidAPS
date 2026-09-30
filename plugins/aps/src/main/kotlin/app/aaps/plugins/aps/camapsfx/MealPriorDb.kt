package app.aaps.plugins.aps.camapsfx

import kotlin.math.sqrt

/**
 * `Model1`'s prior-meal-info database (`this+0x90`) — the per-tick running memory of meal bioavailability and
 * mode-probability that `Model1::GetRunningMealBio`/`GetRunningMealProb` (0x13d3b8 / 0x13d540) read and
 * `Model1::UpdateRunningMealProbAndBio` (0x13d6c8) write. It is re-initialised to the config default at each
 * tick's cold-init and rebuilt *within* `runLearn` as the history horizon's meals are finalised: a meal's learned
 * fBio is banked at its finalisation, and a later meal in the same time-bucket/size-cat reads it back.
 *
 * Layout per (bucket, submodel): a 24-float vector — [0..3] bio by size-cat, [4..7] prob, [8..11] count,
 * [12..15] sum, [16..19] sumsq, [20..23] SD. All emulator-confirmed (tick_0021, DB base 0x9bdf0).
 *
 * Indexing:
 *  - bucket = `GetMealTypeIdx` = `mealPeriods[localMinOfDay/30] − 1` (4 time-of-day periods).
 *  - size-cat (0..3) = weight-category thresholds: 0 if size ≤ thr0, else 1/2/3 by thr1/thr2.
 *  All tables are bucket-invariant config, embedded below (captured once; same every tick/patient).
 */
class MealPriorDb {
    // mealPeriodsGlb (0x23570): 48 half-hours → period 1..4 → bucket 0..3
    private val mealPeriods = intArrayOf(
        1,1,1,1,1,1,1,1,1,1,1, 2,2,2,2,2,2,2,2,2,2,2, 3,3,3,3,3,3,3,3,3,3,3,3, 4,4,4,4,4,4,4,4,4,4,4,4,4,4)
    // weight-category size thresholds (0x23084): [thr0, thr1, thr2] per wcat (0..4)
    private val thr = arrayOf(
        floatArrayOf(6f, 11f, 36f), floatArrayOf(6f, 16f, 51f), floatArrayOf(11f, 31f, 71f),
        floatArrayOf(16f, 41f, 71f), floatArrayOf(21f, 51f, 81f))
    // per-submodel prob defaults [size-cat 0..3] (bucket-invariant; captured from the default DB)
    private val probDefault = arrayOf(
        floatArrayOf(0.1f, 0.1f, 0.2f, 0.2f), floatArrayOf(0f, 0f, 0f, 0f),
        floatArrayOf(0.1f, 0.1f, 0.3f, 0.4f), floatArrayOf(0f, 0f, 0f, 0f),
        floatArrayOf(0.2f, 0.2f, 0.125f, 0.1f), floatArrayOf(0.2f, 0.2f, 0.125f, 0.1f),
        floatArrayOf(0.2f, 0.2f, 0.125f, 0.1f), floatArrayOf(0.2f, 0.2f, 0.125f, 0.1f))
    private val PRIOR_SD = 0.3f    // fPriorSDN cap

    // live per-tick DB: [bucket 0..4][submodel 0..7] → 24 floats
    private val db = Array(5) { Array(8) { FloatArray(24) } }

    init { reset() }

    /** Re-initialise to the config default (called at each tick's cold-init). */
    fun reset() {
        for (b in 0 until 5) for (s in 0 until 8) {
            val v = db[b][s]
            for (k in 0 until 4) { v[k] = 1f; v[4 + k] = probDefault[s][k]; v[20 + k] = PRIOR_SD }
            for (k in 8 until 20) v[k] = 0f
        }
    }

    fun bucket(localMinOfDay: Int): Int {
        val hh = ((localMinOfDay % 1440 + 1440) % 1440) / 30
        return mealPeriods[hh] - 1
    }

    private fun wcat(weight: Float) = when { weight <= 13f -> 0; weight <= 25f -> 1; weight <= 50f -> 2; weight <= 85f -> 3; else -> 4 }

    /** size-cat 0..3 (matches GetRunningMealBio's uVar4). */
    fun sizeCat(mealSize: Float, weight: Float): Int {
        if (mealSize <= 0f) return 0
        val t = thr[wcat(weight)]
        if (t[0] < mealSize) return if (mealSize <= t[1]) 1 else if (mealSize <= t[2]) 2 else 3
        return 0
    }

    /** `GetRunningMealBio(mealSize, time, submodel)` — submodel 1..8. */
    fun getBio(bucket: Int, mealSize: Float, weight: Float, submodel1: Int): Float =
        db[bucket][submodel1 - 1][sizeCat(mealSize, weight)]

    /** `GetRunningMealProb(mealSize, time, submodel)` — prob at index 4+size-cat. */
    fun getProb(bucket: Int, mealSize: Float, weight: Float, submodel1: Int): Float =
        db[bucket][submodel1 - 1][4 + sizeCat(mealSize, weight)]

    /**
     * `UpdateRunningMealProbAndBio` — bank the finalised meal (multiplicative bio update, 0.6/0.4 prob blend,
     * running count/sum/sumsq → sample SD clamped [0.05, PRIOR_SD]). Returns the SD (→ the fBio covariance
     * reset P[3][3] = SD²). No-op returning the current SD when mealSize ≤ 0.
     */
    fun update(bucket: Int, mealSize: Float, weight: Float, submodel1: Int, learnedBio: Float, mealProb: Float): Float {
        val sc = sizeCat(mealSize, weight)
        val v = db[bucket][submodel1 - 1]
        if (mealSize <= 0f) return v[20 + sc]
        v[8 + sc] += 1f
        val prod = v[sc] * learnedBio
        v[sc] = if (prod <= 2.5f) (if (prod < 0.4f) 0.4f else prod) else 2.5f
        v[4 + sc] = mealProb * 0.6f + v[4 + sc] * 0.39999998f
        var count = v[8 + sc]
        if (count > 20000f) {
            v[16 + sc] = (v[16 + sc] / count) * 20000f
            v[12 + sc] = (v[12 + sc] / v[8 + sc]) * 20000f
            v[8 + sc] = 20000f
        }
        v[16 + sc] += learnedBio * learnedBio
        val sum = v[12 + sc] + learnedBio; v[12 + sc] = sum
        count = v[8 + sc]
        if (count >= 5f) {
            val sumsq = v[16 + sc]
            val meanSq = (sum * sum) / count
            val hi = if (meanSq <= sumsq) sumsq else meanSq
            var sd = sqrt((hi - meanSq) / (count - 1f))
            sd = if (sd >= 0.05f) sd else 0.05f
            v[20 + sc] = if (PRIOR_SD < sd) PRIOR_SD else sd
        }
        return v[20 + sc]
    }
}

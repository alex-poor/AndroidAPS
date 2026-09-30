package app.aaps.plugins.aps.camapsfx

/**
 * The CamAPS output pipeline (guide §8) — `MPC::GetRate` (0x14540c) turns the optimiser's raw control into
 * the delivered basal rate. After [Optimiser.optimise] produces the block-1 advice, GetRate runs SEVEN rule
 * modifiers (the safety layer) and then converts + rounds the result to the pump's 0.05 U/h grid.
 *
 * ## The seven rule modifiers (each mutates the rate in place, in this order)
 *  1. `MaximumPersonalRange`        (0x146b08) — cap at a glucose-scaled multiple (2.0/2.5/3.0 by glucose
 *       ≤8/≤12/else) of max(0.7·mean-profile-basal, 0.48·TDD/24) [transcribed, DRIVEN-valid: cap 2.7].
 *  2. `ModifyRateGlucoseLevel`      (0x146dcc) — SUSPEND when glucose < target−1.3 [transcribed, DRIVEN-valid].
 *  3. `ModifyRateGlucoseRate`       (0x146f10) — the TREND BRAKE [transcribed; brake curve, sanity no-op
 *       reproduced]. Two paths: (a) sustained-fall guard → 0.2·profile; (b) `rate ×= (100 − brake%)/100`.
 *       The brake% curve is transcribed; its inputs (roc = near-term slope / `ProgressModel` 2-min prediction)
 *       still need `GetSlope` (OLS over CGM) + `ProgressModel` to drive, so the roc→brake path isn't driven.
 *  4. `ModifyRateDeltaBIR`          (0x147590) — occlusion guard: when the occlusion ΔBIR is large and its
 *       `LowestBGIfOcclusion` < 3.9, clamp down to the smoothed profile [transcribed, DRIVEN-valid].
 *  5. `ModifyExercise`              (0x14771c) — SUSPEND during exercise when glucose ≤ 8 [transcribed, DRIVEN-valid].
 *  6. `ModifyEnoughGlucoseMeasurements` (0x1478e4) — CGM-staleness fallback to profile [transcribed].
 *  7. `RescueCarbReduction`         (0x147a70) — hypo-rescue cut when recent min-BG ≤ 4.2 [transcribed,
 *       DRIVEN-valid: suspend at min-BG 4.2].
 *
 * In the sanity run (normal glucose) ALL seven are NO-OPS — the rate passes through unchanged (u[1] =
 * 1.315053). ALL SEVEN are now transcribed; #1/#2/#4/#5/#7 are validated by DRIVING the emulator (re-run the
 * sanity per sweep value, mocking each modifier's glucose/BG/occlusion source scoped to inside that modifier,
 * read the rate at the next modifier's entry). #3's brake curve is transcribed and reproduces the sanity
 * no-op; its rate-of-change input still needs `GetSlope` (OLS over CGM) + `ProgressModel` to drive fully.
 *
 * ## Dynamic move-penalty weights
 * GetRate SETS `lambdaBase`/`lambdaBaseMeal` before calling Optimise (lines 298-303): the defaults are 1.6/1.2,
 * but when the (offset) mean glucose is low (`meanGlucose ≤ targetOffset + 6.5`) they jump to 3.2/2.4. The
 * sanity run uses 1.6/1.2 (what [Optimiser.buildM2] hard-codes) — a real controller must set them per tick.
 */
object OutputPipeline {

    const val LN2 = 0.6931472f
    private fun expf(x: Float) = kotlin.math.exp(x.toDouble()).toFloat()
    private fun clamp(x: Float, lo: Float, hi: Float) = if (x < lo) lo else if (x > hi) hi else x

    /**
     * `MPC::GetSlope` (0x149994) — the OLS regression slope of glucose vs time over a CGM window (the [t],[g]
     * pairs are gathered by `get_t_g` from the CGM history; [n] points). `slope = (n·Σt·g − Σg·Σt)/(n·Σt² −
     * (Σt)²)`, 0 when the denominator vanishes. Bit-exact vs the emulator (a real 5-point window: 0.01451671).
     * 🔴 The binary's vectorised loop sums the even prefix with PLAIN mul+add but the odd scalar remainder's
     * Σt·g with a FUSED `fmadd`, and the final numerator/denominator are `fmadd`s — replicated here.
     */
    fun getSlopeOLS(t: FloatArray, g: FloatArray, n: Int): Float {
        var sumT = 0f; var sumG = 0f; var sumTT = 0f; var sumTG = 0f
        val even = (n / 2) * 2
        for (i in 0 until even) {
            val ti = t[i]; val gi = kotlin.math.abs(g[i])
            sumT += ti; sumG += gi; sumTT += ti * ti; sumTG += ti * gi         // plain (vectorised prefix)
        }
        if (n and 1 == 1) {
            val ti = t[n - 1]; val gi = kotlin.math.abs(g[n - 1])
            sumT += ti; sumG += gi; sumTT += ti * ti
            sumTG = Submodel.fmadd(ti, gi, sumTG)                              // fused (scalar remainder)
        }
        val den = Submodel.fmadd(n.toFloat(), sumTT, -(sumT * sumT))
        if (kotlin.math.abs(den) <= 1e-5f) return 0f
        return Submodel.fmadd(n.toFloat(), sumTG, sumG * (-sumT)) / den
    }

    /**
     * `MPC::ModifyRateGlucoseRate` (0x146f10) — the TREND BRAKE, in two parts.
     *
     * [sustainedFall] path: when the estimator sees a sustained rapid fall (`GetSlope` over the 1 h / 1 h+ /
     * 2 h+ / 3 h+ windows all steeper than −1.2 mmol/L/h) AND `fVar9` (= min(CGM, starting glucose)) < 8,
     * the rate is clamped to `0.2 · profileBasal` — the caller passes that in as [sustainedFallCap] (≥0 to
     * apply). Otherwise the proportional brake below runs.
     *
     * Proportional brake: two brake curves combined and applied as `rate ×= (100 − brake%)/100`.
     *  - rate-of-change brake from [rateOfChange] (mmol/L/h, from the near-term `GetSlope`/`ProgressModel`
     *    2-min prediction): `f = (e^{k(roc+2.2)} − e^k)·(10/(1−e^k))`, k = ln2/−3.2.
     *  - glucose brake from [glucose] (current CGM): `g = (10/(1−e^{−7.5·ln2/4.5}))·(e^{−(gluc−4.5)·ln2/4.5}
     *    − e^{−7.5·ln2/4.5})`.
     *  - brake% = clamp( max( clamp(f·0.5,0,100), clamp(f,0,50)·clamp(g,0,10) ), 0, 100).
     * No-op in the sanity run (glucose ≈14 → both curves clamp to 0). Transcribed from the decompile; the
     * inputs need `GetSlope` (OLS over CGM) + `ProgressModel`, so the brake%/roc selection isn't driven yet.
     */
    fun modifyRateGlucoseRate(rate: Float, rateOfChange: Float, glucose: Float,
                              sustainedFall: Boolean, sustainedFallCap: Float): Float {
        if (sustainedFall && sustainedFallCap < rate) return sustainedFallCap
        val k = LN2 / -3.2f
        val e0 = expf(k)
        val f = (expf(k * (rateOfChange + 2.2f)) - e0) * (10f / (1f - e0))
        val brake50 = clamp(f, 0f, 50f)
        val brakeHalf = clamp(f * 0.5f, 0f, 100f)
        val gk = LN2 / 4.5f
        val gb0 = expf(gk * -7.5f)
        val g = (10f / (1f - gb0)) * (expf(-((glucose - 4.5f) * gk)) - gb0)
        val gluBrake = clamp(g, 0f, 10f)
        var brake = brakeHalf
        if (brake <= brake50 * gluBrake) brake = brake50 * gluBrake
        brake = clamp(brake, 0f, 100f)
        return ((100f - brake) / 100f) * rate
    }

    /**
     * `MPC::MaximumPersonalRange` (0x146b08) — the max-rate SAFETY CAP (first modifier). Clamps the rate to
     * `[0, cap]`, where `cap` is a glucose-scaled multiple ([mult] = 2.0/2.5/3.0 for glucose ≤8/≤12/else) of
     * the basal envelope: `base = max(mean(profile48)·0.7, (TDD·0.48)/24)`; `cap = max(mult·base, mult·GetBIRpump
     * combined)` unless a calc-TDD term ([calcTDD]) dominates (only used when `minsSinceMeal < 120` and it
     * exceeds the profile term). A small-basal safety keeps ≥ 5× the floored profile mean. `profile48` = the
     * 48 half-hour basal slots (this+0x18); [tddC]/[tddC10] = this+0xc/0x10. Validated: cap = 2.7 U/h in the
     * sanity run (glucose ≈14 → mult 3, `3·max(0.463,0.9)=2.7`), matching the driven rate-sweep clip.
     */
    fun modifyMaximumPersonalRange(rate: Float, glucose: Float, profile48: FloatArray, tddC: Float, tddC10: Float,
                                   birPump: Float, calcTDD: Float, minsSinceMeal: Float): Float {
        var r = if (rate < 0f) 0f else rate
        val mult = if (glucose <= 8f) 2.0f else if (glucose <= 12f) 2.5f else 3.0f
        var sum = 0f; for (x in profile48) sum += x
        val floor1 = (tddC * 0.48f) / 24f
        var fVar10 = (sum / 48f) * 0.7f
        if (fVar10 <= floor1) fVar10 = floor1
        fVar10 = mult * fVar10
        val mb = mult * birPump
        var fVar13 = (mb + fVar10) * 0.5f
        if (mb <= fVar10) fVar13 = fVar10
        var cap = (calcTDD / (500f / tddC)) * 0.5f
        val floor2 = (tddC10 * 0.48f) / 24f
        var fVar10b = (sum / 48f) * 0.7f
        if (minsSinceMeal >= 120f || cap <= fVar13) cap = fVar13   // bVar3 || bVar2!=bVar4
        if (fVar10b <= floor2) fVar10b = floor2
        if (fVar10b * 5f < cap) {
            var f = (sum / 48f) * 0.7f
            if (f <= floor2) f = floor2
            cap = f * 5f
        }
        if (cap < r) r = cap
        return r
    }

    /**
     * `MPC::ModifyRateGlucoseLevel` (0x146dcc) — the SUSPEND rule: deliver 0 when glucose is below target.
     * [glucose] = min(the last CGM (or 5.5 if it is stale, >90 min old), the estimator's starting glucose);
     * suspends when `glucose < finalTargetGlucose − (1.5 with a recent meal within 60 min, else 1.3)`. In the
     * sanity setup (target 5.8, no meal) the threshold is exactly 4.5 — **validated by driving the emulator**
     * (rate→0 for glucose<4.5, unchanged at ≥4.5, exact at the 4.49/4.50 boundary).
     */
    fun modifyRateGlucoseLevel(rate: Float, glucose: Float, finalTarget: Float, recentMeal: Boolean): Float {
        val margin = if (recentMeal) 1.5f else 1.3f
        return if (glucose < finalTarget - margin) 0f else rate
    }

    /**
     * `MPC::ModifyRateDeltaBIR` (0x147590) — the OCCLUSION guard. When no meal is active, the occlusion
     * projection [lowestBGIfOcclusion] would fall below 3.9, and the estimator's occlusion ΔBIR [deltaBIR]
     * is large relative to the smoothed profile (`smoothed·mult < smoothed + deltaBIR`, mult = 1.3/1.6/2.0
     * by starting glucose <9/<12/else), a rate above the smoothed profile is clamped down to it — so a
     * suspected occlusion cannot drive over-delivery. (C-derived; driving needs mocked ΔBIR/occlusion signals.)
     */
    fun modifyRateDeltaBIR(rate: Float, smoothedProfile: Float, deltaBIR: Float, startGlucose: Float,
                           lowestBGIfOcclusion: Float, mealActive: Boolean): Float {
        if (mealActive) return rate
        val mult = if (startGlucose >= 12f) 2.0f else if (startGlucose >= 9f) 1.6f else 1.3f
        if (lowestBGIfOcclusion < 3.9f && smoothedProfile * mult < smoothedProfile + deltaBIR && rate > smoothedProfile)
            return smoothedProfile
        return rate
    }

    /**
     * `MPC::ModifyEnoughGlucoseMeasurements` (0x1478e4) — the CGM-STALENESS fallback: when recent CGM is
     * missing/sparse, fall back toward the pump profile basal [profileBasal] (`GetBIRpump`). No-op when there
     * is a CGM in the last 20 min AND one in the 30-min window ending 20 min ago ([recentContinuous]); else,
     * if there is any CGM in the last 180 min ([cgmWithin180]) the rate is only clamped DOWN to the profile,
     * otherwise it is set to the profile outright. (C-derived; driving needs mocked CGMMeasurementExist.)
     */
    fun modifyEnoughGlucoseMeasurements(rate: Float, profileBasal: Float, recentContinuous: Boolean, cgmWithin180: Boolean): Float {
        if (recentContinuous) return rate
        if (cgmWithin180 && rate <= profileBasal) return rate
        return profileBasal
    }

    /**
     * `MPC::RescueCarbReduction` (0x147a70) — the hypo-RESCUE cut (last modifier). Cuts the rate to 0 when
     * recent min-glucose/min-BG is low: if the 30-min min-BG ≤ 4.2 it suspends outright; above that it
     * suspends unless the 1-h min-BG (or the 18-min min) clears 4.2/6.0 AND the 2-h min clears 4.2 (or the
     * meal is > 2 h old), in which case it at most clamps down to the pump profile. Validated by driving
     * (all-windows sweep): rate→0 for min-BG ≤ 4.2, unchanged above, exact at the 4.20/4.21 boundary.
     */
    fun rescueCarbReduction(rate: Float, minGluc2h: Float, minGluc18: Float, minBG2h: Float, minBG1h: Float,
                            minBG18: Float, minBG30: Float, minsSinceMeal: Float, birPump: Float): Float {
        var fVar9 = 0f
        val fVar5 = if (minGluc2h <= minBG2h) minGluc2h else minBG2h
        if (minBG30 > 4.2f) {
            val fVar7 = if (minGluc18 <= minBG18) minGluc18 else minBG18
            if (minBG1h > 4.2f || fVar7 >= 6.0f) {
                if (minsSinceMeal >= 120f || fVar5 > 4.2f || fVar7 >= 6.0f) return rate
                fVar9 = birPump
                if (rate <= fVar9) return rate
            }
        }
        return fVar9
    }

    /**
     * `MPC::ModifyExercise` (0x14771c) — SUSPEND during exercise: when exercise is active (recorded within
     * the back-period or upcoming within the forward-period) and a positive rate would be delivered, suspend
     * if [glucose] (= min(last CGM or 5.5, starting glucose)) ≤ 8.0.
     */
    fun modifyExercise(rate: Float, glucose: Float, exerciseActive: Boolean): Float {
        if (!exerciseActive || rate <= 0f) return rate
        return if (glucose <= 8.0f) 0f else rate
    }

    /**
     * The final rate conversion + rounding (`MPC::GetRate` lines 328-350). The optimiser's raw insulin
     * [rate] is divided by the basal→rate factor [conv] (this+0x63f4) and rounded to the pump's 0.05 U/h
     * grid; a rate below 0.2 U/h becomes 0 (suspend). Validated bit-exact against the sanity run (1.315053 /
     * 0.980392 → 1.35 U/h). The additional "min-rate bump to 0.2" (when the current infusion is near-zero,
     * gated on body-mass this+0xc) needs the live pump infusion and is applied by the caller.
     */
    fun finalRate(rate: Float, conv: Float): Float {
        val r = (((rate / conv) * 20f + 0.5f).toInt()).toFloat() / 20f    // round to 0.05 U/h
        return if (r >= 0.2f) r else 0f
    }
}

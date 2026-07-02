package app.aaps.plugins.aps.hovorka

/**
 * Common surface for a glucose state-estimator, so the closed-loop harness can run either the single
 * EKF (HovorkaEkf) or the IMM bank (HovorkaImmBank) unchanged. The MPC only ever sees [x] — the
 * estimator's best current state estimate — so swapping estimators is estimation-only (report/
 * hovorka-plugin-plan.md "IMM KALMAN BANK — design (improvement 3a)").
 */
interface GlucoseEstimator {
    /** Best current state estimate (for the single EKF: the state; for the IMM: the probability-weighted mix). */
    val x: DoubleArray
    /** Estimated plasma glucose (mmol/L). */
    fun glucoseMmol(): Double
    /** Predict one dt-minute step under insulin infusion u (mU/min). */
    fun predict(u: Double, dtMin: Double)
    /** Correct with a CGM measurement (mmol/L). Returns the measurement's Gaussian log-likelihood. */
    fun update(gMeasMmol: Double): Double
    /** Known meal input (grams CHO). */
    fun meal(carbsG: Double)
    /** Known insulin bolus (units U) — deposit into the SC insulin compartment. */
    fun bolus(unitsU: Double)
    /**
     * Forecast plasma glucose (mmol/L) `minutes` ahead under constant infusion u, WITHOUT mutating the
     * estimator. This is what the MPC consumes — and where model quality (absorption regime) actually
     * matters, unlike the directly-measured current glucose. The IMM forecasts with each submodel's own
     * dynamics and combines, so it predicts the true absorption regime; the single EKF uses one model.
     */
    fun forecastGlucoseMmol(u: Double, minutes: Int): Double

    /**
     * The model the MPC should roll out with, for an estimator that IDENTIFIES the patient's regime
     * (IMM → its currently best-fit submodel). Null = no opinion; the MPC keeps its fixed controller
     * model. Coupling this into the optimiser is what converts the IMM's forecast advantage into a
     * control advantage (CamAPS ModelIMM1::PredictForOptimise).
     */
    fun rolloutModel(): HovorkaModel? = null
}
